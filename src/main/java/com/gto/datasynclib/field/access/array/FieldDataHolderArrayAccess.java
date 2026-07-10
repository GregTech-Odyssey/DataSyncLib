package com.gto.datasynclib.field.access.array;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datasream.data.Data;
import com.gto.datasynclib.datasream.data.ListData;
import com.gto.datasynclib.datasream.data.NullData;
import com.gto.datasynclib.field.access.AbstractFieldAccess;
import com.gto.datasynclib.util.HashUtil;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public final class FieldDataHolderArrayAccess extends AbstractFieldAccess<IFieldDataHolder[]> {

    private int hashCode;

    public FieldDataHolderArrayAccess(DataFieldDefinition<IFieldDataHolder[]> definition) {
        super(definition);
    }

    @Override
    public boolean mustDetected() {
        return true;
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, IFieldDataHolder @NotNull [] instance, boolean auto) {
        var hashCode = HashUtil.arrayIdentityHashCode(instance);
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            for (var element : instance) {
                element.getFieldDataManager().updateFieldDirtyFlags(side, auto);
                element.getFieldDataManager().markAsChanged();
            }
            return true;
        }
        boolean hasChanges = false;
        for (var element : instance) {
            if (element != null && element.getFieldDataManager().updateFieldDirtyFlags(side, auto)) {
                element.getFieldDataManager().markAsChanged();
                hasChanges = true;
            }
        }
        return hasChanges;
    }

    @Override
    protected void writeBuffer(@NotNull LogicalSide side, IFieldDataHolder @NotNull [] instance, @NotNull FriendlyByteBuf data, boolean force) {
        for (var element : instance) {
            if (element == null || !element.getFieldDataManager().isChanged()) {
                data.writeBoolean(false);
            } else {
                data.writeBoolean(true);
                data.writeByteArray(element.getFieldDataManager().writeToNetworkBuffer(side, force));
            }
        }
    }

    @Override
    protected void readBuffer(@NotNull LogicalSide side, IFieldDataHolder @NotNull [] instance, @NotNull FriendlyByteBuf data) {
        for (var element : instance) {
            if (data.readBoolean()) {
                if (element != null) element.getFieldDataManager().readFromNetworkBuffer(side, data.readByteArray());
            }
        }
    }

    @Override
    protected @NotNull Data writeData(@NotNull Object source, IFieldDataHolder @NotNull [] instance) {
        var list = new ListData();
        for (var element : instance) {
            if (element != null) {
                list.add(element.getFieldDataManager().writeToData());
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
    protected void readData(IFieldDataHolder @NotNull [] instance, @NotNull Data data, int dataVersion) {
        var list = data.getList();
        var length = Math.min(list.size(), instance.length);
        for (int i = 0; i < length; i++) {
            var d = list.get(i);
            if (d != NullData.INSTANCE) {
                var element = instance[i];
                if (element != null) element.getFieldDataManager().readFromData(d, dataVersion);
            }
        }
    }
}
