package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.ValueOps;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.ArrayList;

/**
 * Synchronizes a generic java.util.Map with separate codecs for keys and values.
 * Change detection uses hashCode() comparison.
 * Entries are written as alternating key-value pairs.
 */

public final class MapAccess<K, V> extends AbstractFieldAccess<Map> {

    private final DataSyncCodec<K> keyCodec;
    private final DataSyncCodec<V> valueCodec;
    private int hashCode;

    @SuppressWarnings("unchecked")
    public MapAccess(DataFieldDefinition<Map> definition) {
        super(definition);
        if (definition.genericType.length < 2)
            throw new IllegalArgumentException("Map type parameters not found " + definition.field);
        this.keyCodec = (DataSyncCodec<K>) definition.genericCodecs[0];
        this.valueCodec = (DataSyncCodec<V>) definition.genericCodecs[1];
        if (this.keyCodec == null)
            throw new IllegalArgumentException("Codec not found for key type " + definition.genericType[0]);
        if (this.valueCodec == null)
            throw new IllegalArgumentException("Codec not found for value type " + definition.genericType[1]);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull Map instance, boolean autoDetectOnly) {
        var hashCode = instance.hashCode();
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, @NotNull Map instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        data.writeVarInt(instance.size());
        instance.forEach((k, v) -> {
            keyCodec.streamWriter.encode(data, (K) k);
            valueCodec.streamWriter.encode(data, (V) v);
        });
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, @NotNull Map instance, @NotNull FriendlyByteBuf data) {
        var length = data.readVarInt();
        instance.clear();
        for (int i = 0; i < length; i++) {
            K key = keyCodec.streamReader.decode(data);
            V value = valueCodec.streamReader.decode(data);
            instance.put(key, value);
        }
    }

    @Override
    protected @NotNull Object doWriteValue(@NotNull Object source, @NotNull Map instance, @NotNull ValueOps ops) {
        if (instance.isEmpty()) return ops.createNull();
        var list = new ArrayList<>(instance.size() * 2);
        instance.forEach((k, v) -> {
            list.add(ops.isNull(k) ? ops.createNull() : keyCodec.encode(ops, (K) k));
            list.add(ops.isNull(v) ? ops.createNull() : valueCodec.encode(ops, (V) v));
        });
        return ops.createList(list);
    }

    @Override
    protected void doReadValue(@NotNull Map instance, @NotNull Object data, @NotNull ValueOps ops) {
        // an empty container was stored as the null value, which is a missing payload, not a failure
        if (ops.isNull(data)) return;
        var list = ops.getList(data);
        var size = list.size();
        instance.clear();
        for (int i = 0; i < size; i++) {
            instance.put(keyCodec.decode(ops, list.get(i++)), valueCodec.decode(ops, list.get(i)));
        }
    }
}
