package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.ValueOps;
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
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull LongCollection instance, boolean autoDetectOnly) {
        var hashCode = instance.hashCode();
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, @NotNull LongCollection instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        data.writeVarInt(instance.size());
        instance.forEach(data::writeLong);
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, @NotNull LongCollection instance, @NotNull FriendlyByteBuf data) {
        var length = data.readVarInt();
        instance.clear();
        for (int i = 0; i < length; i++) {
            instance.add(data.readLong());
        }
    }

    @Override
    protected @NotNull Object doWriteValue(@NotNull Object source, @NotNull LongCollection instance, @NotNull ValueOps ops) {
        if (instance.isEmpty()) return ops.createNull();
        return ops.createLongArray(instance.toLongArray());
    }

    @Override
    protected void doReadValue(@NotNull LongCollection instance, @NotNull Object data, @NotNull ValueOps ops) {
        instance.clear();
        if (!ops.isLongArray(data)) return;
        var array = ops.getLongArray(data);
        for (var element : array) {
            instance.add(element);
        }
    }
}
