package com.gto.datasynclib.field.access.array;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.ByteArrayData;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.field.access.AbstractFieldAccess;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Synchronizes a byte[] primitive array.
 */
public final class ByteArrayAccess extends AbstractFieldAccess<byte[]> {

    private int hashCode;

    public ByteArrayAccess(DataFieldDefinition<byte[]> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, byte @NotNull [] instance, boolean auto) {
        var hashCode = Arrays.hashCode(instance);
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void writeBuffer(@NotNull LogicalSide side, byte @NotNull [] instance, @NotNull FriendlyByteBuf data, boolean force) {
        for (var element : instance) {
            data.writeByte(element);
        }
    }

    @Override
    protected void readBuffer(@NotNull LogicalSide side, byte @NotNull [] instance, @NotNull FriendlyByteBuf data) {
        var length = instance.length;
        for (int i = 0; i < length; i++) {
            instance[i] = data.readByte();
        }
    }

    @Override
    protected @NotNull Data writeData(@NotNull Object source, byte @NotNull [] instance) {
        if (definition.hasDefaultValue() && Arrays.equals(instance, definition.getDefaultValue(source)))
            return NullData.NONE;
        return ByteArrayData.valueOf(instance);
    }

    @Override
    protected void readData(byte @NotNull [] instance, @NotNull Data data, int dataVersion) {
        var list = data.getByteArray();
        var length = Math.min(list.length, instance.length);
        System.arraycopy(list, 0, instance, 0, length);
    }
}
