package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datasream.data.Data;
import com.gto.datasynclib.datasream.data.ListData;
import com.gto.datasynclib.datasream.data.NullData;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class MapAccess<K, V> extends AbstractFieldAccess<Map> {

    private final DataSyncCodec<K> keyCodec;
    private final DataSyncCodec<V> valueCodec;
    private int hashCode;

    @SuppressWarnings("unchecked")
    public MapAccess(DataFieldDefinition<Map> definition) {
        super(definition);
        if (definition.genericType.length < 2) throw new IllegalArgumentException("Map type parameters not found");
        this.keyCodec = (DataSyncCodec<K>) definition.genericCodec[0];
        this.valueCodec = (DataSyncCodec<V>) definition.genericCodec[1];
        if (this.keyCodec == null)
            throw new IllegalArgumentException("Codec not found for key type " + definition.genericType[0]);
        if (this.valueCodec == null)
            throw new IllegalArgumentException("Codec not found for value type " + definition.genericType[1]);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull Map instance, boolean auto) {
        var hashCode = instance.hashCode();
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void writeBuffer(@NotNull LogicalSide side, @NotNull Map instance, @NotNull FriendlyByteBuf data, boolean force) {
        data.writeVarInt(instance.size());
        instance.forEach((k, v) -> {
            keyCodec.streamWriter.encode(data, (K) k);
            valueCodec.streamWriter.encode(data, (V) v);
        });
    }

    @Override
    protected void readBuffer(@NotNull LogicalSide side, @NotNull Map instance, @NotNull FriendlyByteBuf data) {
        var length = data.readVarInt();
        instance.clear();
        for (int i = 0; i < length; i++) {
            K key = keyCodec.streamReader.decode(data);
            V value = valueCodec.streamReader.decode(data);
            instance.put(key, value);
        }
    }

    @Override
    protected @NotNull Data writeData(@NotNull Object source, @NotNull Map instance) {
        if (instance.isEmpty()) return NullData.INSTANCE;
        var list = new ListData();
        instance.forEach((k, v) -> {
            list.add(keyCodec.dataWriter.encode((K) k));
            list.add(valueCodec.dataWriter.encode((V) v));
        });
        return list;
    }

    @Override
    protected void readData(@NotNull Map instance, @NotNull Data data, int dataVersion) {
        var list = data.getList();
        var size = list.size();
        instance.clear();
        for (int i = 0; i < size; i++) {
            instance.put(keyCodec.dataReader.decode(list.get(i++), dataVersion), valueCodec.dataReader.decode(list.get(i), dataVersion));
        }
    }
}
