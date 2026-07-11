package com.gto.datasynclib;

import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.datastream.data.StringMapData;
import com.gto.datasynclib.util.ReflectUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import lombok.Getter;
import net.minecraft.network.FriendlyByteBuf;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;

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
public final class FieldDataManager {

    public final IFieldDataHolder holder;
    public final FieldDefinitionStorage storage;
    private final Reference2ReferenceOpenHashMap<DataFieldDefinition<?>, DataField<?>> allFields;
    private final DataField<?>[] syncToClientFields;
    private final DataField<?>[] syncToServerFields;
    private final DataField<?>[] saveFields;
    @Getter
    private boolean changed;
    private volatile boolean updating;
    private volatile boolean writing;

    public FieldDataManager(IFieldDataHolder holder) {
        this.holder = holder;
        this.storage = FieldDefinitionStorage.get(holder.getClass());
        this.allFields = new Reference2ReferenceOpenHashMap<>(storage.allDefinitions.size());
        storage.allDefinitions.values().forEach(d -> allFields.put(d, d.factory.create(d)));
        syncToClientFields = new DataField[storage.syncToClientDefinitions.length];
        for (int i = 0; i < syncToClientFields.length; i++) {
            syncToClientFields[i] = allFields.get(storage.syncToClientDefinitions[i]);
        }
        syncToServerFields = new DataField[storage.syncToServerDefinitions.length];
        for (int i = 0; i < syncToServerFields.length; i++) {
            syncToServerFields[i] = allFields.get(storage.syncToServerDefinitions[i]);
        }
        saveFields = new DataField[storage.saveDefinitions.length];
        for (int i = 0; i < saveFields.length; i++) {
            saveFields[i] = allFields.get(storage.saveDefinitions[i]);
        }
    }

    public DataFieldDefinition<?> getFieldDefinition(Object fieldObject) {
        return getFieldDefinition(fieldObject.getClass(), fieldObject);
    }

    public DataFieldDefinition<?> getFieldDefinition(Class<?> type, Object fieldObject) {
        for (var definition : storage.typeDefinitions.getOrDefault(type, Collections.emptyList())) {
            try {
                if (definition.get(definition.source.apply(holder)) == fieldObject) return definition;
            } catch (Throwable ignored) {
            }
        }
        return null;
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
            var f = allFields.get(field);
            if (f != null) {
                f.markAsChanged(field.source.apply(holder));
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
            var d = storage.allDefinitions.get(field);
            if (d != null) {
                var f = allFields.get(d);
                if (f != null) {
                    f.markAsChanged(d.source.apply(holder));
                } else {
                    throw ReflectUtil.createFieldNotFoundException(field);
                }
            } else {
                throw ReflectUtil.createFieldNotFoundException(field);
            }
        }
    }

    public void clearAllChangeMarks() {
        allFields.values().forEach(f -> f.clearChanged(f.getDefinition().source.apply(holder)));
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
     * @param side the logical side
     * @param auto whether to use auto-update mode
     * @return true if any changes were detected
     */
    public boolean updateFieldDirtyFlags(LogicalSide side, boolean auto) {
        if (updating) return false;
        updating = true;
        try {
            final var fields = side.isServer() ? syncToClientFields : syncToServerFields;
            boolean hasChange = false;
            for (DataField<?> field : fields) {
                var d = field.getDefinition();
                var source = d.source.apply(holder);
                if (!field.mustDetect() && field.isChanged(source)) {
                    hasChange = true;
                } else {
                    if ((!auto || d.autoUpdate(side)) && field.detectChange(side, d.source.apply(holder), auto)) {
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
     * Writes field data to network buffer
     *
     * @param side  the logical side
     * @param force whether to force write all fields
     * @return byte array containing the serialized data
     */
    public byte @NotNull [] writeToNetworkBuffer(LogicalSide side, boolean force) {
        if (writing) return ArrayUtils.EMPTY_BYTE_ARRAY;
        writing = true;
        changed = false;
        var buf = Unpooled.buffer();
        var wrapper = new FriendlyByteBuf(buf);
        try {
            final var fields = side.isServer() ? syncToClientFields : syncToServerFields;
            holder.writeCustomSyncData(wrapper, force);
            for (int i = 0; i < fields.length; i++) {
                var field = fields[i];
                var d = field.getDefinition();
                var source = d.source.apply(holder);
                if (force || field.isChanged(source)) {
                    wrapper.writeVarInt(i);
                    field.writeToBuffer(side, source, wrapper, force);
                    field.clearChanged(source);
                }
            }
            buf.readerIndex(0);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
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
                final var fields = side.isClient() ? syncToClientFields : syncToServerFields;
                holder.readCustomSyncData(wrapper);
                boolean update = false;
                while (buf.readableBytes() > 0) {
                    var index = wrapper.readVarInt();
                    if (index < 0 || index >= fields.length) {
                        throw new IndexOutOfBoundsException("Field index " + index + " out of bounds. Expected 0-" + (fields.length - 1) + " for holder " + holder.getClass().getName());
                    }
                    var f = fields[index];
                    var d = f.getDefinition();
                    f.readFromBuffer(side, d.source.apply(holder), wrapper);
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
        var d = storage.allDefinitions.get(field);
        if (d != null) {
            var f = allFields.get(d);
            if (f != null) return f.writeToData(d.source.apply(holder));
        }
        throw ReflectUtil.createFieldNotFoundException(field);
    }

    public void readFieldFromData(@NotNull Data data, int dataVersion, String field) {
        var d = storage.allDefinitions.get(field);
        if (d != null) {
            var f = allFields.get(d);
            if (f != null) {
                f.readFromData(d.source.apply(holder), data, dataVersion);
                return;
            }
        }
        throw ReflectUtil.createFieldNotFoundException(field);
    }

    @NotNull
    public Data writeFieldsToData(String... fields) {
        StringMapData data = new StringMapData();
        for (var field : fields) {
            var d = storage.allDefinitions.get(field);
            if (d != null) {
                var f = allFields.get(d);
                if (f != null) {
                    var result = f.writeToData(d.source.apply(holder));
                    if (result == NullData.NONE) continue;
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
                var d = storage.allDefinitions.get(field);
                if (d != null) {
                    var tag = map.get(d.key);
                    if (tag != null) {
                        var f = allFields.get(d);
                        if (f != null) {
                            f.readFromData(d.source.apply(holder), tag, dataVersion);
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
            var result = field.writeToData(d.source.apply(holder));
            if (result == NullData.NONE) continue;
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
                if (tag != null) field.readFromData(d.source.apply(holder), tag, dataVersion);
            }
        }
    }
}
