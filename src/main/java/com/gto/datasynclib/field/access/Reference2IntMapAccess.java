package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.ValueOps;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMaps;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Synchronizes a FastUtil {@link it.unimi.dsi.fastutil.objects.Reference2IntMap} (identity-based key comparison).
 *
 * <p>Identical to {@link Object2IntMapAccess} in serialization format, but works with
 * reference-identity maps ({@code ==} key comparison instead of {@code equals()}).
 * Uses the generic type's key codec for key serialization and VarInts for values.</p>
 *
 * <h3>Network format:</h3>
 * <p>{@code VarInt(size) + forEach(keyCodec.encode(key), VarInt(value))}.</p>
 *
 * <h3>Persistence format:</h3>
 * <p>Interleaved key-value pairs in a carrier list.</p>
 *
 * @see Object2IntMapAccess
 */

import java.util.ArrayList;

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
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull Reference2IntMap instance, boolean autoDetectOnly) {
        var hashCode = instance.hashCode();
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, @NotNull Reference2IntMap instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        data.writeVarInt(instance.size());
        Reference2IntMaps.fastForEach(instance, e -> {
            keyCodec.streamWriter.encode(data, (K) e.getKey());
            data.writeVarInt(e.getIntValue());
        });
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, @NotNull Reference2IntMap instance, @NotNull FriendlyByteBuf data) {
        var length = data.readVarInt();
        instance.clear();
        for (int i = 0; i < length; i++) {
            var key = keyCodec.streamReader.decode(data);
            var value = data.readVarInt();
            instance.put(key, value);
        }
    }

    @Override
    protected @NotNull Object doWriteValue(@NotNull Object source, @NotNull Reference2IntMap instance, @NotNull ValueOps ops) {
        if (instance.isEmpty()) return ops.createNull();
        var list = new ArrayList<>(instance.size() * 2);
        Reference2IntMaps.fastForEach(instance, e -> {
            list.add(keyCodec.encode(ops, (K) e.getKey()));
            list.add(e.getIntValue());
        });
        return ops.createList(list);
    }

    @Override
    protected void doReadValue(@NotNull Reference2IntMap instance, @NotNull Object data, @NotNull ValueOps ops) {
        // an empty container was stored as the null value, which is a missing payload, not a failure
        if (ops.isNull(data)) return;
        var list = ops.getList(data);
        var size = list.size();
        instance.clear();
        for (int i = 0; i < size; i++) {
            instance.put(keyCodec.decode(ops, list.get(i++)), ops.getInt(list.get(i)));
        }
    }
}
