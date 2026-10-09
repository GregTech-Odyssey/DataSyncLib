package com.gto.datasynclib;

import com.gto.datasynclib.annotations.RemoteCall;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.ValueOps;
import com.gto.datasynclib.datastream.codec.StreamDecoder;
import com.gto.datasynclib.datastream.codec.StreamEncoder;
import com.gto.datasynclib.remote.RemoteInvoker;
import com.gto.datasynclib.remote.RemoteMethod;
import com.gto.datasynclib.remote.RemoteRouting;
import com.gto.datasynclib.util.FieldDataCodec;
import com.gto.datasynclib.util.ReflectUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import lombok.Getter;
import net.minecraft.network.FriendlyByteBuf;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Per-instance manager that drives the field synchronization and persistence lifecycle for a single
 * {@link IFieldDataHolder} object.
 *
 * <p>Each holder receives a {@code FieldDataManager} (typically via {@link LazyFieldDataManager}) that:
 * <ul>
 *   <li><strong>Discovers fields</strong> — reads the class-level metadata from {@link FieldDefinitionStorage}</li>
 *   <li><strong>Creates DataField instances</strong> — instantiates the appropriate {@link DataField}
 *       implementation for each managed field</li>
 *   <li><strong>Detects changes</strong> — via {@link #updateFieldDirtyFlags(LogicalSide, boolean)},
 *       which iterates sync fields and calls {@link DataField#detectChange} on each</li>
 *   <li><strong>Serializes for network</strong> — via {@link #writeToNetworkBuffer(LogicalSide, boolean)}
 *       and {@link #readFromNetworkBuffer(LogicalSide, byte[])}, using an index-addressing protocol
 *       where only changed fields are written (each prefixed by a VarInt field index)</li>
 *   <li><strong>Serializes for disk</strong> — via {@link #writeToValue(ValueOps)} and
 *       {@link #readFromValue(Object, ValueOps)}, building one carrier string map that
 *       {@link JavaValueOps#toBytes(Object)} turns into the bytes of a save file</li>
 *   <li><strong>Serializes remote calls</strong> — {@link #writeRemoteCall(Object, Object, String, Object...)}
 *       produces the {@code byte[]} for a {@link RemoteCall @RemoteCall}
 *       invocation (validated against the lazily cached {@link #getRemoteMethods()} table) and
 *       {@link IFieldDataHolder#handleRemoteCall(Object, byte[])} decodes and invokes one; as with the
 *       sync methods, sending the bytes is up to the packet layer</li>
 * </ul>
 *
 * <p><strong>Re-entrancy guards:</strong> The {@code updating} and {@code writing} volatile flags
 * prevent recursive calls that could occur if a listener or skip-predicate method triggers another
 * update/write cycle during the same operation.
 *
 * <p><strong>Network wire format:</strong> Custom sync data is written first (via
 * {@link IFieldDataHolder#writeCustomSyncData}), followed by a sequence of (VarInt fieldIndex,
 * fieldPayload) pairs for each dirty field. The receiver reads entries until the buffer is exhausted.
 *
 * @see FieldDefinitionStorage
 * @see DataField
 * @see LazyFieldDataManager
 */
public class FieldDataManager {

    public static <T> FieldDataCodec<T> createCodec(Class<T> objClass, Supplier<T> constructor) {
        return new FieldDataCodec<>(objClass, constructor);
    }

    public static <T> FieldDataCodec<T> createCodec(Class<T> objClass, Supplier<T> constructor, StreamEncoder<FriendlyByteBuf, ? super T> extraStreamWriter, StreamDecoder<FriendlyByteBuf, ? extends T> extraStreamReader) {
        return new FieldDataCodec<>(objClass, constructor, extraStreamWriter, extraStreamReader);
    }

    public final IFieldDataHolder holder;
    public final FieldDefinitionStorage storage;
    private final Reference2ReferenceOpenHashMap<DataFieldDefinition<?>, DataField<?>> allFieldMap;
    private final DataField<?>[] allFields;
    private final DataField<?>[] syncToClientFields;
    private final DataField<?>[] syncToServerFields;
    private final DataField<?>[] saveFields;
    @Getter
    private boolean changed;
    private volatile boolean updating;
    private volatile boolean writing;
    /**
     * Lazily resolved {@code @RemoteCall} table of this manager's holder, see
     * {@link #getRemoteMethods()}. The scan itself is cached per class by
     * {@link com.gto.datasynclib.remote.RemoteInvoker}; this field memoizes the map for this manager
     * so a call costs a plain map lookup and a name can be validated locally.
     */
    @Nullable
    private Object2ReferenceOpenHashMap<String, RemoteMethod> remoteMethods;

    /**
     * The last (root, index) pair {@link #indexIn(Object)} resolved this holder against — see there.
     */
    private volatile HolderIndex holderIndex;

    public FieldDataManager(IFieldDataHolder holder) {
        this(holder, holder.getClass());
    }

    public FieldDataManager(IFieldDataHolder holder, Class<?> holderClass) {
        this.holder = holder;
        this.storage = FieldDefinitionStorage.get(holderClass);
        var length = storage.allDefinitions.length;
        this.allFieldMap = new Reference2ReferenceOpenHashMap<>(length);
        this.allFields = new DataField[length];
        for (int i = 0; i < length; i++) {
            var definition = storage.allDefinitions[i];
            var field = definition.factory.create(definition);
            allFields[i] = field;
            allFieldMap.put(definition, field);
        }
        length = storage.syncToClientDefinitions.length;
        syncToClientFields = new DataField[length];
        for (int i = 0; i < length; i++) {
            syncToClientFields[i] = allFieldMap.get(storage.syncToClientDefinitions[i]);
        }
        length = storage.syncToServerDefinitions.length;
        syncToServerFields = new DataField[length];
        for (int i = 0; i < length; i++) {
            syncToServerFields[i] = allFieldMap.get(storage.syncToServerDefinitions[i]);
        }
        length = storage.saveDefinitions.length;
        saveFields = new DataField[length];
        for (int i = 0; i < length; i++) {
            saveFields[i] = allFieldMap.get(storage.saveDefinitions[i]);
        }
    }

    public DataFieldDefinition<?> getFieldDefinition(Object fieldObject) {
        return getFieldDefinition(fieldObject.getClass(), fieldObject);
    }

    public DataFieldDefinition<?> getFieldDefinition(Class<?> type, Object fieldObject) {
        for (var definition : storage.typeDefinitions.getOrDefault(type, Collections.emptyList())) {
            try {
                if (definition.get(holder.getSource(definition)) == fieldObject) return definition;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public DataFieldDefinition<?> getFieldDefinition(Field field) {
        for (var definition : storage.typeDefinitions.getOrDefault(field.getType(), Collections.emptyList())) {
            try {
                if (definition.field.equals(field)) return definition;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public DataFieldDefinition<?> getFieldDefinition(String fieldName) {
        return storage.definitionMap.get(fieldName);
    }

    // ==================== Remote calls ====================

    /**
     * Returns the {@code @RemoteCall} methods of <strong>this manager's holder</strong>, resolving
     * them lazily on the first request.
     *
     * <p>The table belongs to the holder that owns this manager and is the very instance
     * {@link com.gto.datasynclib.remote.RemoteInvoker#methods(Class)} caches for that class — a holder
     * without annotated methods yields the shared empty table. It is handed out as is (no
     * {@code unmodifiableMap} wrapper and no copy): treat it as read-only, since it is shared with
     * every other holder of the class.</p>
     */
    public Object2ReferenceOpenHashMap<String, RemoteMethod> getRemoteMethods() {
        var methods = remoteMethods;
        if (methods == null) {
            methods = RemoteInvoker.methods(holder.getClass());
            remoteMethods = methods;
        }
        return methods;
    }

    /**
     * Looks up one {@code @RemoteCall} method of this manager's holder by its wire name.
     *
     * @param method the wire name ({@link RemoteCall#value()} or the Java
     *               method name)
     * @return the method, or {@code null} if the holder does not expose that name
     */
    @Nullable
    public RemoteMethod getRemoteMethod(String method) {
        return getRemoteMethods().get(method);
    }

    /**
     * Encodes a remote call and returns the payload a packet carries — the remote call counterpart of
     * {@link #writeToNetworkBuffer(LogicalSide, boolean)}. Like the sync path, this class only produces
     * the bytes: addressing and sending them is the packet layer's job (see
     * {@link com.gto.datasynclib.remote.RemoteNetwork}, or a packet of your own).
     *
     * <p>Both objects are given explicitly: {@code root} is the object the packet will address (so the
     * receiver resolves the call against exactly that tree) and {@code target} is the holder inside it
     * the call is for — the root itself when the call is for the addressed object. The target's index is
     * looked up in the flattened tree of the root's class
     * ({@link com.gto.datasynclib.remote.RemoteRouting#indexOf(Object, Object)}), so neither side has to
     * know where a holder sits; that lookup is a few field reads on a table built once per class.</p>
     *
     * <p>The name is validated against the target's {@link #getRemoteMethods()} table first, so a typo
     * fails here instead of being logged on the receiver.</p>
     *
     * @param root   the object the packet will address (a block entity, an entity, or any holder)
     * @param target the holder the call is for; may be {@code root} itself
     * @param method the wire name of the {@code @RemoteCall} method
     * @param args   the arguments, in declaration order
     * @return the serialized call, ready for
     * {@link com.gto.datasynclib.remote.RemoteBlockEntityPacket} /
     * {@link com.gto.datasynclib.remote.RemoteEntityPacket}
     * @throws IllegalArgumentException if {@code target} is not part of {@code root}'s holder tree, has
     *                                  no such remote method, or the arguments do not match its
     *                                  parameters
     */
    public static byte[] writeRemoteCall(Object root, Object target, String method, Object... args) {
        if (!(target instanceof IFieldDataHolder holder))
            throw new IllegalArgumentException("Remote call target " + target.getClass().getName()
                    + " is not an IFieldDataHolder");
        var index = holder.getFieldDataManager().indexIn(root);
        if (index < 0)
            throw new IllegalArgumentException("Remote call target " + target.getClass().getSimpleName()
                    + " is not a holder inside " + root.getClass().getSimpleName() + " (nodes: "
                    + Arrays.toString(RemoteRouting.holderNodeNames(root.getClass())) + ")");
        var remote = holder.getFieldDataManager().getRemoteMethods().get(method);
        if (remote == null)
            throw new IllegalArgumentException("No @RemoteCall method '" + method + "' on " + target.getClass().getName());
        // The resolved method is handed over as is, so the call costs one table lookup, not two.
        return RemoteInvoker.write(remote, index, args);
    }

    /**
     * Where this manager's holder sits in {@code root}'s flattened tree — the index a call for it is
     * addressed with, resolved once per root and then cached.
     *
     * <p>The lookup itself is an identity scan over the root's cached node paths
     * ({@link RemoteRouting#indexOf(Object, Object)}); caching it turns the send path into one volatile
     * read. The cache is per (root, holder) pair and assumes the tree is fixed, which it is for holders
     * assigned when their owner is built — see the class documentation on nested holders.</p>
     *
     * @param root the object a packet will address
     * @return the index, or {@code -1} when this holder is not part of {@code root}'s tree
     */
    public int indexIn(Object root) {
        var cached = holderIndex;
        if (cached != null && cached.root() == root) return cached.index();
        var index = root == holder ? 0 : RemoteRouting.indexOf(root, holder);
        if (index >= 0) holderIndex = new HolderIndex(root, index);
        return index;
    }

    /**
     * One cached (root, index) pair, published as a whole so two threads sending from the same holder can
     * never observe a mismatched pair.
     */
    private record HolderIndex(Object root, int index) {
    }

    /**
     * Decodes a received call and invokes it on the node of the addressed object's holder tree the payload
     * names — the receive half of
     * {@link #writeRemoteCall(Object, Object, String, Object...)}, and the whole of what a packet handler
     * needs.
     *
     * <p>It is package-private on purpose: the object the packet addressed <em>is</em> the tree a call
     * index refers to, and letting a caller pass something else is exactly what silently shifts every
     * index. The public entry point is {@link IFieldDataHolder#handleRemoteCall(Object, byte[])}, which is
     * handed the object the packet addressed.</p>
     *
     * @param target  the object the packet addressed
     * @param payload the call payload from {@link #writeRemoteCall(Object, Object, String, Object...)};
     *                empty input is ignored
     */
    static void dispatchRemoteCall(Object target, byte[] payload) {
        if (target == null || payload == null || payload.length == 0) return;
        RemoteInvoker.handle(target, payload);
    }

    /**
     * The side-aware form of {@link #dispatchRemoteCall(Object, byte[])} — for a packet handler that
     * already knows which logical side the call arrived on, so {@link RemoteCall#side()} is enforced even
     * for a target that is not in a level.
     *
     * @param target  the object the packet addressed
     * @param payload the call payload from {@link #writeRemoteCall(Object, Object, String, Object...)}
     * @param side    the side the call arrived on, or {@code null} when it is not known
     */
    static void dispatchRemoteCall(Object target, byte[] payload, @Nullable LogicalSide side) {
        if (target == null || payload == null || payload.length == 0) return;
        RemoteInvoker.handle(target, payload, side);
    }

    public boolean hasSyncFields(LogicalSide side) {
        var fields = side.isServer() ? syncToClientFields : syncToServerFields;
        return fields.length > 0;
    }

    public boolean hasSaveFields() {
        return saveFields.length > 0;
    }

    /**
     * Marks specified fields as dirty for synchronization
     *
     * @param fields the field definition to mark for sync
     */
    public void markFieldsForSync(@NotNull DataFieldDefinition<?>... fields) {
        for (var field : fields) {
            var f = allFieldMap.get(field);
            if (f != null) {
                f.markAsChanged(holder.getSource(field));
            } else {
                throw ReflectUtil.createFieldNotFoundException(field.field.getName());
            }
        }
    }

    /**
     * Marks specified fields as dirty for synchronization
     *
     * @param fields the field names to mark for sync
     */
    public void markFieldsForSync(@NotNull String... fields) {
        for (var field : fields) {
            var d = storage.definitionMap.get(field);
            if (d != null) {
                var f = allFieldMap.get(d);
                if (f != null) {
                    f.markAsChanged(holder.getSource(d));
                } else {
                    throw ReflectUtil.createFieldNotFoundException(field);
                }
            } else {
                throw ReflectUtil.createFieldNotFoundException(field);
            }
        }
    }

    public void clearAllChangeMarks() {
        allFieldMap.values().forEach(f -> f.clearChanged(holder.getSource(f.getDefinition())));
    }

    public void markAsChanged() {
        changed = true;
    }

    public void clearChanged() {
        changed = false;
    }

    /**
     * Updates dirty flags for fields based on changes
     *
     * @param side           the logical side
     * @param autoDetectOnly if {@code true}, only fields with {@code autoDetect = true}
     *                       are checked; if {@code false}, all sync fields are checked
     *                       regardless of their {@code autoDetect} setting
     * @return true if any changes were detected
     */
    public boolean updateFieldDirtyFlags(LogicalSide side, boolean autoDetectOnly) {
        if (updating) return false;
        updating = true;
        try {
            final var fields = side.isServer() ? syncToClientFields : syncToServerFields;
            boolean hasChange = false;
            for (DataField<?> field : fields) {
                var d = field.getDefinition();
                var source = holder.getSource(d);
                if (!field.mustDetect() && field.isChanged(source)) {
                    hasChange = true;
                } else {
                    if ((!autoDetectOnly || d.autoDetect(side)) && field.detectChange(side, holder.getSource(d), autoDetectOnly)) {
                        hasChange = true;
                    }
                }
            }
            return changed = hasChange || changed;
        } finally {
            updating = false;
        }
    }

    /**
     * Writes field data to network buffer.
     *
     * @param side     the logical side determining which fields to serialize
     * @param writeAll if {@code true}, all managed fields are written regardless of
     *                 dirty state (full sync); if {@code false}, only changed fields
     *                 that have been marked dirty are written (incremental sync)
     * @return byte array containing the serialized data, empty if re-entrant call
     */
    public byte @NotNull [] writeToNetworkBuffer(LogicalSide side, boolean writeAll) {
        if (writing) return ArrayUtils.EMPTY_BYTE_ARRAY;
        writing = true;
        var buf = Unpooled.buffer();
        var wrapper = new FriendlyByteBuf(buf);
        try {
            final var fields = side.isBoth() ? allFields : side.isServer() ? syncToClientFields : syncToServerFields;
            holder.writeCustomSyncData(wrapper, writeAll);
            for (int i = 0; i < fields.length; i++) {
                var field = fields[i];
                var d = field.getDefinition();
                var source = holder.getSource(d);
                if (writeAll || field.isChanged(source)) {
                    wrapper.writeVarInt(i);
                    field.writeToBuffer(side, source, wrapper, writeAll);
                    field.clearChanged(source);
                }
            }
            buf.readerIndex(0);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            changed = false; // Only clear after successful serialization
            return data;
        } finally {
            buf.release();
            writing = false;
        }
    }

    /**
     * Reads field data from network buffer
     *
     * @param side the logical side
     * @param data the byte array to read from
     */
    public void readFromNetworkBuffer(LogicalSide side, byte @NotNull [] data) {
        if (data.length > 0) {
            var buf = Unpooled.wrappedBuffer(data);
            var wrapper = new FriendlyByteBuf(buf);
            try {
                final var fields = side.isBoth() ? allFields : side.isClient() ? syncToClientFields : syncToServerFields;
                holder.readCustomSyncData(wrapper);
                boolean update = false;
                while (buf.readableBytes() > 0) {
                    var index = wrapper.readVarInt();
                    if (index < 0 || index >= fields.length) {
                        throw new IndexOutOfBoundsException("Field index " + index + " out of bounds. Expected 0-" + (fields.length - 1) + " for holder " + holder.getClass().getName());
                    }
                    var f = fields[index];
                    var d = f.getDefinition();
                    f.readFromBuffer(side, holder.getSource(d), wrapper);
                    if (d.scheduleUpdate(side)) {
                        update = true;
                    }
                }
                if (update) holder.scheduleUpdate(side);
            } finally {
                buf.release();
            }
        }
    }

    /**
     * Serializes one field for disk persistence, on the native carrier.
     *
     * @param field the field name (as declared, or its {@code @SaveToDisk(key = ...)} override)
     * @return the field's value, {@code null} for an explicit null, or {@link DataField#NOT_PERSISTED}
     */
    @NotNull
    public Object writeFieldToValue(String field, @NotNull ValueOps ops) {
        var d = storage.definitionMap.get(field);
        if (d != null) {
            var f = allFieldMap.get(d);
            if (f != null) return f.writeToValue(holder.getSource(d), ops);
        }
        throw ReflectUtil.createFieldNotFoundException(field);
    }

    /**
     * Reads one field back from a carrier value — the counterpart of
     * {@link #writeFieldToValue(String, ValueOps)}.
     */
    public void readFieldFromValue(@NotNull Object data, @NotNull ValueOps ops, String field) {
        var d = storage.definitionMap.get(field);
        if (d != null) {
            var f = allFieldMap.get(d);
            if (f != null) {
                f.readFromValue(holder.getSource(d), data, ops);
                return;
            }
        }
        throw ReflectUtil.createFieldNotFoundException(field);
    }

    /**
     * Serializes the named fields into a carrier string map, skipping the ones that have nothing to
     * store ({@link DataField#NOT_PERSISTED}) and the explicit nulls of fields that do not
     * {@code saveEmpty}.
     *
     * @return the map, or {@code null} when no field contributed
     */
    @NotNull
    public Object writeFieldsToValue(@NotNull ValueOps ops, String... fields) {
        Map<String, Object> data = new HashMap<>();
        for (var field : fields) {
            var d = storage.definitionMap.get(field);
            if (d == null) throw ReflectUtil.createFieldNotFoundException(field);
            var f = allFieldMap.get(d);
            if (f == null) throw ReflectUtil.createFieldNotFoundException(field);
            var result = f.writeToValue(holder.getSource(d), ops);
            if (result == DataField.NOT_PERSISTED) continue;
            if (!ops.isNull(result) || d.saveEmpty) data.put(d.key, result);
        }
        return data.isEmpty() ? ops.createNull() : ops.createStringMap(data);
    }

    /**
     * Reads the named fields back from a carrier string map; an absent key leaves the field alone.
     */
    public void readFieldsFromValue(@NotNull Object data, @NotNull ValueOps ops, String... fields) {
        if (!ops.isStringMap(data)) return;
        var map = ops.getStringMap(data);
        for (var field : fields) {
            var d = storage.definitionMap.get(field);
            if (d == null) throw ReflectUtil.createFieldNotFoundException(field);
            if (!map.containsKey(d.key)) continue;
            var f = allFieldMap.get(d);
            if (f == null) throw ReflectUtil.createFieldNotFoundException(field);
            f.readFromValue(holder.getSource(d), map.get(d.key), ops);
        }
    }

    /**
     * Serializes every {@code @SaveToDisk} field (plus the holder's custom save data) into a carrier
     * string map — the form a save file holds, and the one
     * {@link com.gto.datasynclib.datastream.codec.JavaValueOps#toBytes(Object)} turns into bytes.
     *
     * <p>Fields are written in definition order, which is sorted by storage key: a save is therefore
     * byte-for-byte reproducible, where the Data-era map was a hash map.</p>
     *
     * @return the map, or the carrier's null value when there is nothing to write
     */
    @NotNull
    public Object writeToValue(@NotNull ValueOps ops) {
        return writeToValue(ops, saveFields);
    }

    /**
     * The {@link #writeToValue(ValueOps)} of every managed field, including the ones that are
     * only {@code @AddToManager} and therefore never saved by the incremental path.
     */
    @NotNull
    public Object writeAllToValue(@NotNull ValueOps ops) {
        return writeToValue(ops, allFields);
    }

    private Object writeToValue(ValueOps ops, DataField<?>[] fields) {
        Map<String, Object> data = new HashMap<>(fields.length);
        holder.writeCustomSaveData(data, ops);
        for (var field : fields) {
            var d = field.getDefinition();
            var result = field.writeToValue(holder.getSource(d), ops);
            if (result == DataField.NOT_PERSISTED) continue;
            if (!ops.isNull(result) || d.saveEmpty) data.put(d.key, result);
        }
        return data.isEmpty() ? ops.createNull() : ops.createStringMap(data);
    }

    /**
     * Restores the {@code @SaveToDisk} fields from {@link #writeToValue(ValueOps)}.
     */
    public void readFromValue(@NotNull Object data, @NotNull ValueOps ops) {
        readFromValue(data, ops, saveFields);
    }

    /**
     * Restores every managed field from {@link #writeAllToValue(ValueOps)}.
     */
    public void readAllFromValue(@NotNull Object data, @NotNull ValueOps ops) {
        readFromValue(data, ops, allFields);
    }

    private void readFromValue(Object data, ValueOps ops, DataField<?>[] fields) {
        if (!ops.isStringMap(data)) return;
        var map = ops.getStringMap(data);
        holder.readCustomSaveData(map, ops);
        for (var field : fields) {
            var d = field.getDefinition();
            if (!map.containsKey(d.key)) continue;
            field.readFromValue(holder.getSource(d), map.get(d.key), ops);
        }
    }
}
