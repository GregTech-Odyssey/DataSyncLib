package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datasream.data.Data;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public final class FieldDataHolderAccess extends AbstractFieldAccess<IFieldDataHolder> {

    public FieldDataHolderAccess(DataFieldDefinition<IFieldDataHolder> definition) {
        super(definition);
    }

    @Override
    public boolean mustDetected() {
        return true;
    }

    @Override
    public void markAsChanged(@NotNull Object source) {
        syncChange = true;
        var instance = getInstance(source);
        if (instance == null) return;
        instance.getFieldDataManager().markAsChanged();
    }

    @Override
    public void clearChanged(@NotNull Object source) {
        syncChange = false;
        var instance = getInstance(source);
        if (instance == null) return;
        instance.getFieldDataManager().clearChanged();
    }

    @Override
    public boolean isChanged(@NotNull Object source) {
        var instance = getInstance(source);
        if (instance == null) return syncChange;
        return syncChange || instance.getFieldDataManager().isChanged();
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull IFieldDataHolder instance, boolean auto) {
        return instance.getFieldDataManager().updateFieldDirtyFlags(side, auto);
    }

    @Override
    protected void writeBuffer(@NotNull LogicalSide side, @NotNull IFieldDataHolder instance, @NotNull FriendlyByteBuf data, boolean force) {
        data.writeByteArray(instance.getFieldDataManager().writeToNetworkBuffer(side, force));
    }

    @Override
    protected void readBuffer(@NotNull LogicalSide side, @NotNull IFieldDataHolder instance, @NotNull FriendlyByteBuf data) {
        instance.getFieldDataManager().readFromNetworkBuffer(side, data.readByteArray());
    }

    @Override
    protected @NotNull Data writeData(@NotNull Object source, @NotNull IFieldDataHolder instance) {
        return instance.getFieldDataManager().writeToData();
    }

    @Override
    protected void readData(@NotNull IFieldDataHolder instance, @NotNull Data data, int dataVersion) {
        instance.getFieldDataManager().readFromData(data, dataVersion);
    }
}
