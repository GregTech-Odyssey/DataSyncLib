package com.gto.datasynclib.field.access.array;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.LongArrayData;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.field.access.AbstractFieldAccess;
import net.minecraft.network.FriendlyByteBuf;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Synchronizes a {@code double[]} primitive array with <strong>in-place</strong> mutation.
 *
 * <h3>Change detection:</h3>
 * <p>Compares the array against a retained snapshot with {@link Arrays#equals(double[], double[])}, so the common (unchanged) case is an exact, vectorized, allocation-free check (no hash collisions); the snapshot costs one extra {@code double[]} per holder, and a detected change copies the array into it.
 * The array reference is fixed ({@code final} field), so only content changes are tracked.</p>
 *
 * <h3>Network format:</h3>
 * <p>Individual {@code double} elements, one per array slot ({@code writeDouble}/{@code readDouble}).
 * The array length is NOT transmitted — both sides must agree on the length (typically
 * fixed-size arrays). Each element is written/read in order, overwriting the existing array
 * contents in-place.</p>
 *
 * <h3>Persistence format:</h3>
 * <p>Writes as {@link LongArrayData}. Skips writing
 * if the array matches the configured default value. On read, uses
 * {@link System#arraycopy} to copy up to {@code min(dataLength, arrayLength)} elements,
 * safely handling length mismatches from data migration.</p>
 */
public final class DoubleArrayAccess extends AbstractFieldAccess<double[]> {

    /**
     * Last seen contents, compared with {@link Arrays#equals(double[], double[])}.
     */
    private double[] snapshot = ArrayUtils.EMPTY_DOUBLE_ARRAY;

    public DoubleArrayAccess(DataFieldDefinition<double[]> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, double @NotNull [] instance, boolean autoOnly) {
        if (!Arrays.equals(snapshot, instance)) {
            snapshot = instance.clone();
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, double @NotNull [] instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        for (var element : instance) {
            data.writeDouble(element);
        }
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, double @NotNull [] instance, @NotNull FriendlyByteBuf data) {
        var length = instance.length;
        for (int i = 0; i < length; i++) {
            instance[i] = data.readDouble();
        }
    }

    @Override
    protected @NotNull Data doWriteData(@NotNull Object source, double @NotNull [] instance) {
        if (definition.hasDefaultValue() && Arrays.equals(instance, definition.getDefaultValue(source)))
            return NullData.NONE;
        return Data.valueOf(instance);
    }

    @Override
    protected void doReadData(double @NotNull [] instance, @NotNull Data data, int dataVersion) {
        var list = data.getDoubleArray();
        var length = Math.min(list.length, instance.length);
        System.arraycopy(list, 0, instance, 0, length);
    }
}
