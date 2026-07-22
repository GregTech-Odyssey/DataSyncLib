package com.gto.datasynclib.field.access.array;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.codec.DataCodec;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.field.access.AbstractFieldAccess;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Synchronizes a boolean[] primitive array.
 */
public final class BooleanArrayAccess extends AbstractFieldAccess<boolean[]> {

    private int hashCode;

    public BooleanArrayAccess(DataFieldDefinition<boolean[]> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, boolean @NotNull [] instance, boolean autoOnly) {
        var hashCode = Arrays.hashCode(instance);
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, boolean @NotNull [] instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        for (var element : instance) {
            data.writeBoolean(element);
        }
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, boolean @NotNull [] instance, @NotNull FriendlyByteBuf data) {
        var length = instance.length;
        for (int i = 0; i < length; i++) {
            instance[i] = data.readBoolean();
        }
    }

    @Override
    protected @NotNull Data doWriteData(@NotNull Object source, boolean @NotNull [] instance) {
        if (definition.hasDefaultValue() && Arrays.equals(instance, definition.getDefaultValue(source)))
            return NullData.NONE;
        return DataCodec.BOOLEANS_CODEC.encode(instance);
    }

    @Override
    protected void doReadData(boolean @NotNull [] instance, @NotNull Data data, int dataVersion) {
        var list = DataCodec.BOOLEANS_CODEC.decode(data, dataVersion);
        var length = Math.min(list.length, instance.length);
        System.arraycopy(list, 0, instance, 0, length);
    }
}
