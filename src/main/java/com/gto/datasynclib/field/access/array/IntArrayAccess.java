package com.gto.datasynclib.field.access.array;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.IntArrayData;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.field.access.AbstractFieldAccess;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Synchronizes an int[] primitive array.
 */
public final class IntArrayAccess extends AbstractFieldAccess<int[]> {

    private int hashCode;

    public IntArrayAccess(DataFieldDefinition<int[]> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, int @NotNull [] instance, boolean autoOnly) {
        var hashCode = Arrays.hashCode(instance);
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, int @NotNull [] instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        for (var element : instance) {
            data.writeInt(element);
        }
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, int @NotNull [] instance, @NotNull FriendlyByteBuf data) {
        var length = instance.length;
        for (int i = 0; i < length; i++) {
            instance[i] = data.readInt();
        }
    }

    @Override
    protected @NotNull Data doWriteData(@NotNull Object source, int @NotNull [] instance) {
        if (definition.hasDefaultValue() && Arrays.equals(instance, definition.getDefaultValue(source)))
            return NullData.NONE;
        return IntArrayData.valueOf(instance);
    }

    @Override
    protected void doReadData(int @NotNull [] instance, @NotNull Data data, int dataVersion) {
        var list = data.getIntArray();
        var length = Math.min(list.length, instance.length);
        System.arraycopy(list, 0, instance, 0, length);
    }
}
