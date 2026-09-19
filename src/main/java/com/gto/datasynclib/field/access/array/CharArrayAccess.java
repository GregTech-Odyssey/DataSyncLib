package com.gto.datasynclib.field.access.array;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.IntArrayData;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.field.access.AbstractFieldAccess;
import net.minecraft.network.FriendlyByteBuf;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Synchronizes an {@code char[]} primitive array with <strong>in-place</strong> mutation.
 *
 * <h3>Change detection:</h3>
 * <p>Compares the array against a retained snapshot with {@link Arrays#equals(char[], char[])}, so the common (unchanged) case is an exact, vectorized, allocation-free check (no hash collisions); the snapshot costs one extra {@code char[]} per holder, and a detected change copies the array into it.
 * The array reference is fixed ({@code final} field), so only content changes are tracked.</p>
 *
 * <h3>Network format:</h3>
 * <p>Individual {@code char} elements, one per array slot ({@code writeChar}/{@code readChar}).
 * The array length is NOT transmitted — both sides must agree on the length (typically
 * fixed-size arrays). Each element is written/read in order, overwriting the existing array
 * contents in-place.</p>
 *
 * <h3>Persistence format:</h3>
 * <p>Writes as {@link IntArrayData}. Skips writing
 * if the array matches the configured default value. On read, uses
 * {@link System#arraycopy} to copy up to {@code min(dataLength, arrayLength)} elements,
 * safely handling length mismatches from data migration.</p>
 */
public final class CharArrayAccess extends AbstractFieldAccess<char[]> {

    /**
     * Last seen contents, compared with {@link Arrays#equals(char[], char[])}.
     */
    private char[] snapshot = ArrayUtils.EMPTY_CHAR_ARRAY;

    public CharArrayAccess(DataFieldDefinition<char[]> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, char @NotNull [] instance, boolean autoOnly) {
        if (!Arrays.equals(snapshot, instance)) {
            snapshot = instance.clone();
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, char @NotNull [] instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        for (var element : instance) {
            data.writeChar(element);
        }
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, char @NotNull [] instance, @NotNull FriendlyByteBuf data) {
        var length = instance.length;
        for (int i = 0; i < length; i++) {
            instance[i] = data.readChar();
        }
    }

    @Override
    protected @NotNull Data doWriteData(@NotNull Object source, char @NotNull [] instance) {
        if (definition.hasDefaultValue() && Arrays.equals(instance, definition.getDefaultValue(source)))
            return NullData.NONE;
        return Data.valueOf(instance);
    }

    @Override
    protected void doReadData(char @NotNull [] instance, @NotNull Data data, int dataVersion) {
        var list = data.getCharArray();
        var length = Math.min(list.length, instance.length);
        System.arraycopy(list, 0, instance, 0, length);
    }
}
