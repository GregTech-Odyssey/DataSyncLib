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
 * Synchronizes a {@code long[]} primitive array with <strong>in-place</strong> mutation.
 *
 * <h3>Change detection:</h3>
 * <p>Uses {@link Arrays#hashCode(long[])} for fast content-based change detection; the array
 * reference is expected to be fixed (a {@code final} field), so only content changes are
 * tracked.</p>
 *
 * <h3>Network format:</h3>
 * <p>Individual {@code long} elements, one per array slot ({@code writeLong}/{@code readLong}).
 * The array length is NOT transmitted — both sides must agree on the length (typically
 * fixed-size arrays). Each element is written/read in order, overwriting the existing array
 * contents in-place.</p>
 *
 * <h3>Persistence format:</h3>
 * <p>Writes as {@link LongArrayData}. Skips writing if the array matches the configured
 * default value. On read, copies up to {@code min(dataLength, arrayLength)} elements, so
 * length mismatches from data migration are handled safely.</p>
 */
public final class LongArrayAccess extends AbstractFieldAccess<long[]> {

    /**
     * Last seen contents, compared with {@link Arrays#equals(long[], long[])}.
     */
    private long[] snapshot = ArrayUtils.EMPTY_LONG_ARRAY;

    public LongArrayAccess(DataFieldDefinition<long[]> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, long @NotNull [] instance, boolean autoDetectOnly) {
        if (!Arrays.equals(snapshot, instance)) {
            snapshot = instance.clone();
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
