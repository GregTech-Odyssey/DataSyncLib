package com.gto.datasynclib.field.access.array;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.ListData;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.field.access.AbstractFieldAccess;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Synchronizes a generic object array with element codec support.
 * Change detection uses Arrays.hashCode().
 * Null elements are encoded with boolean prefix.
 */
public final class ArrayAccess<T> extends AbstractFieldAccess<T[]> {

    private final DataSyncCodec<T> elementCodec;
    private int hashCode;

    public ArrayAccess(DataFieldDefinition<T[]> definition, DataSyncCodec<T> elementCodec) {
        super(definition);
        this.elementCodec = elementCodec;
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, T @NotNull [] instance, boolean autoOnly) {
        var hashCode = Arrays.hashCode(instance);
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, T @NotNull [] instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        for (var element : instance) {
            if (element == null) {
                data.writeBoolean(false);
            } else {
                data.writeBoolean(true);
                elementCodec.streamWriter.encode(data, element);
            }
        }
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, T @NotNull [] instance, @NotNull FriendlyByteBuf data) {
        var length = instance.length;
        for (int i = 0; i < length; i++) {
            if (data.readBoolean()) {
                instance[i] = elementCodec.streamReader.decode(data);
            } else {
                instance[i] = null;
            }
        }
    }

    @Override
    protected @NotNull Data doWriteData(@NotNull Object source, T @NotNull [] instance) {
        if (definition.hasDefaultValue() && Arrays.equals(instance, definition.getDefaultValue(source)))
            return NullData.NONE;
        var list = new ListData();
        for (T element : instance) {
            if (element != null) {
                list.add(elementCodec.dataWriter.encode(element));
            } else {
                list.addNull();
            }
        }
        if (definition.saveNull) return list;
        for (var data : list) {
            if (data != NullData.INSTANCE) return list;
        }
        return NullData.NONE;
    }

    @Override
    protected void doReadData(T @NotNull [] instance, @NotNull Data data, int dataVersion) {
        var list = data.getList();
        var length = Math.min(list.size(), instance.length);
        for (int i = 0; i < length; i++) {
            var element = list.get(i);
            if (element != NullData.INSTANCE) {
                instance[i] = elementCodec.dataReader.decode(element, dataVersion);
            } else {
                instance[i] = null;
            }
        }
    }
}
