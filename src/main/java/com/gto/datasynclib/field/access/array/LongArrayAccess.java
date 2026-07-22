package com.gto.datasynclib.field.access.array;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.LongArrayData;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.field.access.AbstractFieldAccess;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Synchronizes a long[] primitive array.
 */
public final class LongArrayAccess extends AbstractFieldAccess<long[]> {

    private int hashCode;

    public LongArrayAccess(DataFieldDefinition<long[]> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, long @NotNull [] instance, boolean autoOnly) {
        var hashCode = Arrays.hashCode(instance);
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, long @NotNull [] instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        for (var element : instance) {
            data.writeLong(element);
        }
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, long @NotNull [] instance, @NotNull FriendlyByteBuf data) {
        var length = instance.length;
        for (int i = 0; i < length; i++) {
            instance[i] = data.readLong();
        }
    }

    @Override
    protected @NotNull Data doWriteData(@NotNull Object source, long @NotNull [] instance) {
        if (definition.hasDefaultValue() && Arrays.equals(instance, definition.getDefaultValue(source)))
            return NullData.NONE;
        return LongArrayData.valueOf(instance);
    }

    @Override
    protected void doReadData(long @NotNull [] instance, @NotNull Data data, int dataVersion) {
        var list = data.getLongArray();
        var length = Math.min(list.length, instance.length);
        System.arraycopy(list, 0, instance, 0, length);
    }
}
