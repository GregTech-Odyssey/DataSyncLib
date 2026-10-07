package com.gto.datasynclib;

import com.gto.datasynclib.annotations.RemoteCall;
import com.gto.datasynclib.datastream.codec.ByteStreamDecoder;
import com.gto.datasynclib.datastream.codec.ByteStreamEncoder;
import com.gto.datasynclib.datastream.codec.DataDecoder;
import com.gto.datasynclib.datastream.codec.DataEncoder;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.datastream.data.StringMapData;
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
import java.util.Collections;
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
 *   <li><strong>Serializes for disk</strong> — via {@link #writeToData()} and
 *       {@link #readFromData(com.gto.datasynclib.datastream.data.Data, int)} using {@link com.gto.datasynclib.datastream.data.StringMapData}</li>
 *   <li><strong>Serializes remote calls</strong> — {@link #writeRemoteCall(String, Object...)}
 *       produces the {@code byte[]} for a {@link RemoteCall @RemoteCall}
 *       invocation (validated against the lazily cached {@link #getRemoteMethods()} table) and
 *       {@link #readRemoteCall(byte[])} decodes and invokes one; as with the sync methods, sending
 *       the bytes is up to the packet layer</li>
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

    public static <T> FieldDataCodec<T> createCodec(Class<T> objClass, Supplier<T> constructor, ByteStreamEncoder<? super T> extraStreamWriter, ByteStreamDecoder<? extends T> extraStreamReader, DataEncoder<? super T> extraDataWriter, DataDecoder<? extends T> extraDataReader) {
        return new FieldDataCodec<>(objClass, constructor, extraStreamWriter, extraStreamReader, extraDataWriter, extraDataReader);
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
     * The field indices leading from the root of this holder tree to this holder — what
     * {@link #writeRemoteCall(String, Object...)} writes into the call prefix, resolved on first use and
     * then cached. {@code null} means "not resolved yet"; a root resolves to
     * {@link RemoteRouting#EMPTY_PATH}.
     */
    private int @Nullable [] remotePath;

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
     * Encodes a remote call for this holder and returns the payload a packet carries — the remote
     * call counterpart of {@link #writeToNetworkBuffer(LogicalSide, boolean)}. Like the sync path,
     * this class only produces the bytes: addressing and sending them is the packet layer's job
     * (see {@link com.gto.datasynclib.remote.RemoteNetwork}, or a packet of your own).
     *
     * <p>The name is validated against the lazily cached {@link #getRemoteMethods()} table first, so a
     * typo fails here instead of being logged on the receiver.</p>
     *
     * @param method the wire name of the {@code @RemoteCall} method
     * @param args   the arguments, in declaration order
     * @return the serialized call, ready for
     * {@link com.gto.datasynclib.remote.RemoteBlockEntityPacket} /
     * {@link com.gto.datasynclib.remote.RemoteEntityPacket}
     * @throws IllegalArgumentException if this holder has no such remote method, or the arguments do
     *                                  not match its parameters
     */
    public byte[] writeRemoteCall(String method, Object... args) {
        var remote = getRemoteMethods().get(method);
        if (remote == null)
            throw new IllegalArgumentException("No @RemoteCall method '" + method + "' on " + holder.getClass().getName());
        // The holder's own position travels with the call, so the receiver can walk straight to it; the
        // resolved method is handed over as is, so the call costs one table lookup, not two.
        return RemoteInvoker.write(remote, remotePath(), args);
    }

    /**
     * Decodes a remote call payload and invokes it on this holder — the remote call counterpart of
     * {@link #readFromNetworkBuffer(LogicalSide, byte[])}, called by a packet handler once it has
     * resolved the target object.
     *
     * <p>The call is routed with the wire identity of the payload, so a method declared on a
     * <strong>nested</strong> holder of this one (an {@code @AdditionalHolder} sub-object, or any
     * field whose type implements {@link IFieldDataHolder}) is invoked on that nested object instead of
     * failing on this holder. That is what lets {@code module.remoteCall("configure", 3)} — encoded by
     * the nested holder's own manager — arrive on the block entity/entity that carries the module.</p>
     *
     * <p>A remote method is {@code void} ({@link RemoteCall}), so nothing
     * comes back out; the callee's effects are observed through field synchronization.</p>
     *
     * @param data the payload produced by {@link #writeRemoteCall(String, Object...)}; empty input and
     *             a payload whose prefix cannot be read are ignored
     * @throws IllegalArgumentException if the payload's identity and index match no method of this
     *                                  holder or of a nested one — normally a call from another version
     * @throws RuntimeException         if the argument section is truncated, or the invoked method
     *                                  itself throws; the original failure is the cause
     */
    public void readRemoteCall(byte[] data) {
        dispatchRemoteCall(holder, data);
    }

    /**
     * Decodes a received call and invokes it on the holder its field path addresses — the static core of
     * {@link #readRemoteCall(byte[])}, shared with
     * {@link IFieldDataHolder#handleRemoteCall(Object, byte[])} so a target that is <em>not</em> a holder
     * (but still carries nested ones) can be dispatched the same way.
     *
     * <p>The payload is wrapped into a {@link FriendlyByteBuf} once and decoded once: the call prefix
     * (field path + method index, see {@link RemoteInvoker#readRoute(FriendlyByteBuf)}) leads it, the
     * receiving side walks the addressed object's holder fields — index by index, through
     * {@link RemoteRouting} — and then invokes the method at that index of the target's table. Nothing
     * is searched for and nothing is built on the way.</p>
     *
     * @param target  the object the packet addressed
     * @param payload the call payload from {@link #writeRemoteCall(String, Object...)}; empty input is
     *                ignored
     */
    static void dispatchRemoteCall(Object target, byte[] payload) {
        if (target == null || payload == null || payload.length == 0) return;
        RemoteInvoker.handle(target, payload);
    }

    // ==================== Sending: the holder's own position ====================

    /**
     * The field indices leading from the root of this holder tree to this holder. Empty when this holder
     * is a root.
     *
     * <p>Derived by walking <em>up</em> the owner chain, one holder at a time: each level asks
     * {@link IFieldDataHolder#getParentHolder()} for its owner and finds the field index by scanning that
     * owner's holder fields for the very object
     * ({@link RemoteRouting#indexOfHolder(Class, Object, Object)}), so nothing has to be pushed down the
     * tree and no holder field is ever read on the send path beyond that one scan.</p>
     *
     * <p>The walk stops at a holder that answers {@code null} (a root), after
     * {@link RemoteRouting#MAX_DEPTH} levels, or as soon as an owner does not actually hold the holder —
     * the last case leaves the path uncached so that the next call picks up an owner link or an
     * assignment that happened in between.</p>
     */
    public int[] remotePath() {
        var path = remotePath;
        if (path != null) return path;
        var resolved = resolveRemotePath();
        return resolved == null ? RemoteRouting.EMPTY_PATH : (remotePath = resolved);
    }

    /**
     * Walks the owner chain upwards and returns the path, or {@code null} when a level could not be
     * resolved — the caller then leaves the cache empty and tries again later.
     */
    private int @Nullable [] resolveRemotePath() {
        // One slot per level, plus the root itself; the walk stops there, so a broken owner chain can
        // neither loop nor allocate more than this.
        var indices = new int[RemoteRouting.MAX_DEPTH + 1];
        var depth = 0;
        var node = holder;
        var owner = node.getParentHolder();
        while (depth <= RemoteRouting.MAX_DEPTH) {
            if (owner == null || owner == node) break; // a root
            var index = RemoteRouting.indexOfHolder(owner.getClass(), owner, node);
            if (index < 0) return null; // that owner does not hold it: not resolvable (yet)
            indices[depth++] = index;
            node = owner;
            owner = node.getParentHolder();
        }
        if (depth == 0) return RemoteRouting.EMPTY_PATH;
        var path = new int[depth];
        for (int i = 0; i < depth; i++) {
            path[i] = indices[depth - 1 - i];
        }
        return path;
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

    @NotNull
    public Data writeFieldToData(String field) {
        var d = storage.definitionMap.get(field);
        if (d != null) {
            var f = allFieldMap.get(d);
            if (f != null) return f.writeToData(holder.getSource(d));
        }
        throw ReflectUtil.createFieldNotFoundException(field);
    }

    public void readFieldFromData(@NotNull Data data, int dataVersion, String field) {
        var d = storage.definitionMap.get(field);
        if (d != null) {
            var f = allFieldMap.get(d);
            if (f != null) {
                f.readFromData(holder.getSource(d), data, dataVersion);
                return;
            }
        }
        throw ReflectUtil.createFieldNotFoundException(field);
    }

    @NotNull
    public Data writeFieldsToData(String... fields) {
        StringMapData data = new StringMapData();
        for (var field : fields) {
            var d = storage.definitionMap.get(field);
            if (d != null) {
                var f = allFieldMap.get(d);
                if (f != null) {
                    var result = f.writeToData(holder.getSource(d));
                    if (result.isNone()) continue;
                    if (result != NullData.INSTANCE || d.saveEmpty) data.put(d.key, result);
                } else {
                    throw ReflectUtil.createFieldNotFoundException(field);
                }
            } else {
                throw ReflectUtil.createFieldNotFoundException(field);
            }
        }
        return data.isEmpty() ? NullData.INSTANCE : data;
    }

    public void readFieldsFromData(@NotNull Data data, int dataVersion, String... fields) {
        if (data instanceof StringMapData(Map<String, Data> map)) {
            for (var field : fields) {
                var d = storage.definitionMap.get(field);
                if (d != null) {
                    var tag = map.get(d.key);
                    if (tag != null) {
                        var f = allFieldMap.get(d);
                        if (f != null) {
                            f.readFromData(holder.getSource(d), tag, dataVersion);
                        } else {
                            throw ReflectUtil.createFieldNotFoundException(field);
                        }
                    }
                } else {
                    throw ReflectUtil.createFieldNotFoundException(field);
                }
            }
        }
    }

    /**
     * Serializes every {@code @SaveToDisk} field (plus custom save data) into a Data tree.
     *
     * @return a {@link StringMapData} keyed by each field's storage key, or
     * {@link NullData#INSTANCE} when there is nothing to write
     */
    @NotNull
    public Data writeToData() {
        StringMapData data = new StringMapData();
        holder.writeCustomSaveData(data);
        for (var field : saveFields) {
            var d = field.getDefinition();
            var result = field.writeToData(holder.getSource(d));
            if (result.isNone()) continue;
            if (result != NullData.INSTANCE || d.saveEmpty) data.put(d.key, result);
        }
        return data.isEmpty() ? NullData.INSTANCE : data;
    }

    /**
     * Reads field data from MapData
     *
     * @param data the MapData to read from
     */
    public void readFromData(@NotNull Data data, int dataVersion) {
        if (data instanceof StringMapData mapData) {
            holder.readCustomSaveData(mapData, dataVersion);
            var map = mapData.value();
            for (var field : saveFields) {
                var d = field.getDefinition();
                var tag = map.get(d.key);
                if (tag != null) field.readFromData(holder.getSource(d), tag, dataVersion);
            }
        }
    }

    @NotNull
    public Data writeAllToData() {
        StringMapData data = new StringMapData();
        holder.writeCustomSaveData(data);
        for (var field : allFields) {
            var d = field.getDefinition();
            var result = field.writeToData(holder.getSource(d));
            if (result.isNone()) continue;
            if (result != NullData.INSTANCE || d.saveEmpty) data.put(d.key, result);
        }
        return data.isEmpty() ? NullData.INSTANCE : data;
    }

    public void readAllFromData(@NotNull Data data, int dataVersion) {
        if (data instanceof StringMapData mapData) {
            holder.readCustomSaveData(mapData, dataVersion);
            var map = mapData.value();
            for (var field : allFields) {
                var d = field.getDefinition();
                var tag = map.get(d.key);
                if (tag != null) field.readFromData(holder.getSource(d), tag, dataVersion);
            }
        }
    }
}
