package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datasream.data.Data;
import com.gto.datasynclib.datasream.data.IntArrayData;
import com.gto.datasynclib.datasream.data.NullData;
import it.unimi.dsi.fastutil.ints.IntCollection;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public final class IntCollectionAccess extends AbstractFieldAccess<IntCollection> {

    private int hashCode;

    public IntCollectionAccess(DataFieldDefinition<IntCollection> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull IntCollection instance, boolean auto) {
        var hashCode = instance.hashCode();
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void writeBuffer(@NotNull LogicalSide side, @NotNull IntCollection instance, @NotNull FriendlyByteBuf data, boolean force) {
        data.writeVarInt(instance.size());
        instance.forEach(data::writeInt);
    }

    @Override
    protected void readBuffer(@NotNull LogicalSide side, @NotNull IntCollection instance, @NotNull FriendlyByteBuf data) {
        var length = data.readVarInt();
        instance.clear();
        for (int i = 0; i < length; i++) {
            instance.add(data.readInt());
        }
    }

    @Override
    protected @NotNull Data writeData(@NotNull Object source, @NotNull IntCollection instance) {
        if (instance.isEmpty()) return NullData.INSTANCE;
        return new IntArrayData(instance.toIntArray());
    }

    @Override
    protected void readData(@NotNull IntCollection instance, @NotNull Data data, int dataVersion) {
        instance.clear();
        var array = data.getIntArray();
        for (var element : array) {
            instance.add(element);
        }
    }
}
