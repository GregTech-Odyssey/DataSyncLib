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
 * Synchronizes an {@code int[]} primitive array with <strong>in-place</strong> mutation.
 *
 * <h3>Change detection:</h3>
 * <p>Uses {@link Arrays#hashCode(int[])} for fast content-based change detection.
 * The array reference is fixed ({@code final} field), so only content changes are tracked.</p>
 *
 * <h3>Network format:</h3>
 * <p>Individual VarInt-encoded elements, one per array slot. The array length is NOT
 * transmitted — both sides must agree on the length (typically fixed-size arrays).
 * Each element is written/read in order, overwriting the existing array contents in-place.</p>
 *
 * <h3>Persistence format:</h3>
 * <p>Writes as {@link com.gto.datasynclib.datastream.data.IntArrayData}. Skips writing
 * if the array matches the configured default value. On read, uses
 * {@link System#arraycopy} to copy up to {@code min(dataLength, arrayLength)} elements,
 * safely handling length mismatches from data migration.</p>
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
            data.writeVarInt(element);
        }
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, int @NotNull [] instance, @NotNull FriendlyByteBuf data) {
        var length = instance.length;
        for (int i = 0; i < length; i++) {
            instance[i] = data.readVarInt();
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
