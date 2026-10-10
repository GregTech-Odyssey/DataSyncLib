package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.codec.ValueOps;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

/**
 * Synchronizes a FastUtil Reference2LongMap (identity-based key comparison).
 */

public class Reference2LongMapAccess<K> extends AbstractFieldAccess<Reference2LongMap> {

    private final DataSyncCodec<K> keyCodec;
    private int hashCode;

    @SuppressWarnings("unchecked")
    public Reference2LongMapAccess(DataFieldDefinition<Reference2LongMap> definition) {
        super(definition);
        if (definition.genericType.length < 1) throw new IllegalArgumentException("Map type parameters not found");
        this.keyCodec = (DataSyncCodec<K>) definition.genericCodecs[0];
        if (this.keyCodec == null)
            throw new IllegalArgumentException("Codec not found for key type " + definition.genericType[0]);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull Reference2LongMap instance, boolean autoDetectOnly) {
        var hashCode = instance.hashCode();
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, @NotNull Reference2LongMap instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        data.writeVarInt(instance.size());
        Reference2LongMaps.fastForEach(instance, e -> {
            keyCodec.streamWriter.encode(data, (K) e.getKey());
            data.writeLong(e.getLongValue());
        });
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, @NotNull Reference2LongMap instance, @NotNull FriendlyByteBuf data) {
        var length = data.readVarInt();
        instance.clear();
        for (int i = 0; i < length; i++) {
            var key = keyCodec.streamReader.decode(data);
            var value = data.readLong();
            instance.put(key, value);
        }
    }

    @Override
    protected @NotNull Object doWriteValue(@NotNull Object source, @NotNull Reference2LongMap instance, @NotNull ValueOps ops) {
        if (instance.isEmpty()) return ops.createNull();
        var list = new ArrayList<>(instance.size() * 2);
        Reference2LongMaps.fastForEach(instance, e -> {
            list.add(keyCodec.encode(ops, (K) e.getKey()));
            list.add(e.getLongValue());
        });
        return ops.createList(list);
    }

    @Override
    protected void doReadValue(@NotNull Reference2LongMap instance, @NotNull Object data, @NotNull ValueOps ops) {
        // an empty container was stored as the null value, which is a missing payload, not a failure
        if (ops.isNull(data)) return;
        var list = ops.getList(data);
        var size = list.size();
        instance.clear();
        for (int i = 0; i < size; i++) {
            instance.put(keyCodec.decode(ops, list.get(i++)), ops.getLong(list.get(i)));
        }
    }
}
