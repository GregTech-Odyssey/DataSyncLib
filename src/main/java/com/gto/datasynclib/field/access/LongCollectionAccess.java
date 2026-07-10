package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.LongArrayData;
import com.gto.datasynclib.datastream.data.NullData;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Synchronizes a FastUtil LongCollection (primitive long collection).
 * Change detection uses hashCode() comparison.
 * Data serialization uses LongArrayData.
 */
public final class LongCollectionAccess extends AbstractFieldAccess<LongCollection> {

    private int hashCode;

    public LongCollectionAccess(DataFieldDefinition<LongCollection> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull LongCollection instance, boolean auto) {
        var hashCode = instance.hashCode();
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void writeBuffer(@NotNull LogicalSide side, @NotNull LongCollection instance, @NotNull FriendlyByteBuf data, boolean force) {
        data.writeVarInt(instance.size());
        instance.forEach(data::writeLong);
    }

    @Override
    protected void readBuffer(@NotNull LogicalSide side, @NotNull LongCollection instance, @NotNull FriendlyByteBuf data) {
        var length = data.readVarInt();
        instance.clear();
        for (int i = 0; i < length; i++) {
            instance.add(data.readLong());
        }
    }

    @Override
    protected @NotNull Data writeData(@NotNull Object source, @NotNull LongCollection instance) {
        if (instance.isEmpty()) return NullData.INSTANCE;
        return new LongArrayData(instance.toLongArray());
    }

    @Override
    protected void readData(@NotNull LongCollection instance, @NotNull Data data, int dataVersion) {
        instance.clear();
        var array = data.getLongArray();
        for (var element : array) {
            instance.add(element);
        }
    }
}
