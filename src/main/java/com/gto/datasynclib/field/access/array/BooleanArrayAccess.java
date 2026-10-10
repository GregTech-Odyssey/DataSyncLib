package com.gto.datasynclib.field.access.array;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.codec.ValueOps;
import com.gto.datasynclib.field.access.AbstractFieldAccess;
import net.minecraft.network.FriendlyByteBuf;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Synchronizes a {@code boolean[]} primitive array with <strong>in-place</strong> mutation.
 *
 * <h3>Change detection:</h3>
 * <p>Uses {@link Arrays#hashCode(boolean[])} for fast content-based change detection; the
 * array reference is expected to be fixed (a {@code final} field), so only content changes
 * are tracked.</p>
 *
 * <h3>Network format:</h3>
 * <p>Individual {@code boolean} elements, one per array slot ({@code writeBoolean}/
 * {@code readBoolean}). The array length is NOT transmitted — both sides must agree on the
 * length (typically fixed-size arrays). Each element is written/read in order, overwriting
 * the existing array contents in-place.</p>
 *
 * <h3>Persistence format:</h3>
 * <p>Writes as the carrier''s {@code BOOLEAN_ARRAY} value. Skips writing if the array
 * matches the configured default value. On read, copies up to
 * {@code min(dataLength, arrayLength)} elements, so length mismatches from data migration
 * are handled safely.</p>
 */
public final class BooleanArrayAccess extends AbstractFieldAccess<boolean[]> {

    /**
     * Last seen contents, compared with {@link Arrays#equals(boolean[], boolean[])}.
     */
    private boolean[] snapshot = ArrayUtils.EMPTY_BOOLEAN_ARRAY;

    public BooleanArrayAccess(DataFieldDefinition<boolean[]> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, boolean @NotNull [] instance, boolean autoDetectOnly) {
        if (!Arrays.equals(snapshot, instance)) {
            snapshot = instance.clone();
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
    protected @NotNull Object doWriteValue(@NotNull Object source, boolean @NotNull [] instance, @NotNull ValueOps ops) {
        if (definition.hasDefaultValue() && Arrays.equals(instance, definition.getDefaultValue(source)))
            return NOT_PERSISTED;
        if (instance.length == 0) return ops.createNull();
        return ops.createBooleanArray(instance);
    }

    @Override
    protected void doReadValue(boolean @NotNull [] instance, @NotNull Object data, @NotNull ValueOps ops) {
        if (ops.isNull(data)) return;
        var array = ops.getBooleanArray(data);
        System.arraycopy(array, 0, instance, 0, Math.min(array.length, instance.length));
    }
}