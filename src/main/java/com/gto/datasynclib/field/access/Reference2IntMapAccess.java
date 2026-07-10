package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.IntData;
import com.gto.datasynclib.datastream.data.ListData;
import com.gto.datasynclib.datastream.data.NullData;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMaps;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Synchronizes a FastUtil Reference2IntMap (identity-based key comparison).
 */
public class Reference2IntMapAccess<K> extends AbstractFieldAccess<Reference2IntMap> {

    private final DataSyncCodec<K> keyCodec;
    private int hashCode;

    @SuppressWarnings("unchecked")
    public Reference2IntMapAccess(DataFieldDefinition<Reference2IntMap> definition) {
        super(definition);
        if (definition.genericType.length < 1) throw new IllegalArgumentException("Map type parameters not found");
        this.keyCodec = (DataSyncCodec<K>) definition.genericCodecs[0];
        if (this.keyCodec == null)
            throw new IllegalArgumentException("Codec not found for key type " + definition.genericType[0]);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull Reference2IntMap instance, boolean auto) {
        var hashCode = instance.hashCode();
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void writeBuffer(@NotNull LogicalSide side, @NotNull Reference2IntMap instance, @NotNull FriendlyByteBuf data, boolean force) {
        data.writeVarInt(instance.size());
        Reference2IntMaps.fastForEach(instance, e -> {
            keyCodec.streamWriter.encode(data, (K) e.getKey());
            data.writeInt(e.getIntValue());
        });
    }

    @Override
    protected void readBuffer(@NotNull LogicalSide side, @NotNull Reference2IntMap instance, @NotNull FriendlyByteBuf data) {
        var length = data.readVarInt();
        instance.clear();
        for (int i = 0; i < length; i++) {
            var key = keyCodec.streamReader.decode(data);
            var value = data.readInt();
            instance.put(key, value);
        }
    }

    @Override
    protected @NotNull Data writeData(@NotNull Object source, @NotNull Reference2IntMap instance) {
        if (instance.isEmpty()) return NullData.INSTANCE;
        var list = new ListData();
        Reference2IntMaps.fastForEach(instance, e -> {
            list.add(keyCodec.dataWriter.encode((K) e.getKey()));
            list.add(IntData.valueOf(e.getIntValue()));
        });
        return list;
    }

    @Override
    protected void readData(@NotNull Reference2IntMap instance, @NotNull Data data, int dataVersion) {
        var list = data.getList();
        var size = list.size();
        instance.clear();
        for (int i = 0; i < size; i++) {
            instance.put(keyCodec.dataReader.decode(list.get(i++), dataVersion), list.get(i).getInt());
        }
    }
}
