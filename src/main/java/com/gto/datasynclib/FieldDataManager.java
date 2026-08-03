package com.gto.datasynclib;

import com.gto.datasynclib.datastream.codec.ByteStreamDecoder;
import com.gto.datasynclib.datastream.codec.ByteStreamEncoder;
import com.gto.datasynclib.datastream.codec.DataDecoder;
import com.gto.datasynclib.datastream.codec.DataEncoder;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.datastream.data.StringMapData;
import com.gto.datasynclib.util.FieldDataCodec;
import com.gto.datasynclib.util.ReflectUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import lombok.Getter;
import net.minecraft.network.FriendlyByteBuf;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;

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
 * </ul>
 *
 * <p><strong>Re-entrancy guards:</strong> The {@code updating} and {@code writing} volatile flags
 * prevent recursive calls that could occur if a listener or condition method triggers another
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
                if (definition.field == field) return definition;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public DataFieldDefinition<?> getFieldDefinition(String fieldName) {
        return storage.definitionMap.get(fieldName);
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
     * @param side     the logical side
     * @param autoOnly if {@code true}, only fields with {@code autoUpdate = true}
     *                 are checked; if {@code false}, all sync fields are checked
     *                 regardless of their {@code autoUpdate} setting
     * @return true if any changes were detected
     */
    public boolean updateFieldDirtyFlags(LogicalSide side, boolean autoOnly) {
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
                    if ((!autoOnly || d.autoUpdate(side)) && field.detectChange(side, holder.getSource(d), autoOnly)) {
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
                    if (d.notifyUpdate(side)) {
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
                    if (result != NullData.INSTANCE || d.saveNull) data.put(d.key, result);
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
     * Writes field data to MapData
     *
     * @return MapData containing all saveable field data
     */
    @NotNull
    public Data writeToData() {
        StringMapData data = new StringMapData();
        holder.writeCustomSaveData(data);
        for (var field : saveFields) {
            var d = field.getDefinition();
            var result = field.writeToData(holder.getSource(d));
            if (result.isNone()) continue;
            if (result != NullData.INSTANCE || d.saveNull) data.put(d.key, result);
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
            if (result != NullData.INSTANCE || d.saveNull) data.put(d.key, result);
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
