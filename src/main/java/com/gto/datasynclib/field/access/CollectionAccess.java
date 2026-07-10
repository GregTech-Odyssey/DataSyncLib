package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.ListData;
import com.gto.datasynclib.datastream.data.NullData;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Synchronizes a generic java.util.Collection with element-level codec support.
 * Change detection uses hashCode() comparison.
 * Each element is individually encoded/decoded with null support.
 */
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
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull Collection instance, boolean auto) {
        var hashCode = instance.hashCode();
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void writeBuffer(@NotNull LogicalSide side, @NotNull Collection instance, @NotNull FriendlyByteBuf data, boolean force) {
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
    protected void readBuffer(@NotNull LogicalSide side, @NotNull Collection instance, @NotNull FriendlyByteBuf data) {
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
    protected @NotNull Data writeData(@NotNull Object source, @NotNull Collection instance) {
        if (instance.isEmpty()) return NullData.INSTANCE;
        var list = new ListData();
        instance.forEach(element -> {
            if (element == null) {
                list.addNull();
            } else {
                list.add(elementCodec.dataWriter.encode((E) element));
            }
        });
        return list;
    }

    @Override
    protected void readData(@NotNull Collection instance, @NotNull Data data, int dataVersion) {
        var list = data.getList();
        instance.clear();
        for (var element : list) {
            if (element != NullData.INSTANCE) {
                instance.add(elementCodec.dataReader.decode(element, dataVersion));
            } else {
                instance.add(null);
            }
        }
    }
}
