package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.ValueOps;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Synchronizes a generic java.util.Collection with element-level codec support.
 * Change detection uses hashCode() comparison.
 * Each element is individually encoded/decoded with null support.
 */

import java.util.ArrayList;

public final class CollectionAccess<E> extends AbstractFieldAccess<Collection> {

    private final DataSyncCodec<E> elementCodec;
    private int hashCode;

    @SuppressWarnings("unchecked")
    public CollectionAccess(DataFieldDefinition<Collection> definition) {
        super(definition);
        if (definition.genericCodecs.length == 0) throw new IllegalArgumentException("Collection type not found");
        this.elementCodec = (DataSyncCodec<E>) definition.genericCodecs[0];
        if (this.elementCodec == null)
            throw new IllegalArgumentException("Codec not found for type " + definition.genericType[0]);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull Collection instance, boolean autoDetectOnly) {
        var hashCode = instance.hashCode();
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, @NotNull Collection instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        data.writeVarInt(instance.size());
        instance.forEach(element -> {
            if (element == null) {
                data.writeBoolean(false);
            } else {
                data.writeBoolean(true);
                elementCodec.streamWriter.encode(data, (E) element);
            }
        });
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, @NotNull Collection instance, @NotNull FriendlyByteBuf data) {
        var length = data.readVarInt();
        instance.clear();
        for (int i = 0; i < length; i++) {
            if (data.readBoolean()) {
                instance.add(elementCodec.streamReader.decode(data));
            } else {
                instance.add(null);
            }
        }
    }

    @Override
    protected @NotNull Object doWriteValue(@NotNull Object source, @NotNull Collection instance, @NotNull ValueOps ops) {
        if (instance.isEmpty()) return ops.createNull();
        var list = new ArrayList<>(instance.size());
        instance.forEach(element -> list.add(ops.isNull(element) ? ops.createNull() : elementCodec.encode(ops, (E) element)));
        return ops.createList(list);
    }

    @Override
    protected void doReadValue(@NotNull Collection instance, @NotNull Object data, @NotNull ValueOps ops) {
        // an empty container was stored as the null value, which is a missing payload, not a failure
        if (!ops.isList(data)) return;
        var list = ops.getList(data);
        instance.clear();
        for (var element : list) {
            instance.add(ops.isNull(element) ? null : elementCodec.decode(ops, element));
        }
    }
}
