package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datasream.data.Data;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public final class SerializableAccess extends AbstractFieldAccess<IDataSerializable> {

    public SerializableAccess(DataFieldDefinition<IDataSerializable> definition) {
        super(definition);
    }

    @Override
    public void markAsChanged(@NotNull Object source) {
        syncChange = true;
        var instance = getInstance(source);
        if (instance == null) return;
        instance.markAsChanged();
    }

    @Override
    public void clearChanged(@NotNull Object source) {
        syncChange = false;
        var instance = getInstance(source);
        if (instance == null) return;
        instance.clearChanged();
    }

    @Override
    public boolean isChanged(@NotNull Object source) {
        var instance = getInstance(source);
        if (instance == null) return syncChange;
        return syncChange || instance.isChanged();
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull IDataSerializable instance, boolean auto) {
        return instance.detectChange();
    }

    @Override
    protected void writeBuffer(@NotNull LogicalSide side, @NotNull IDataSerializable instance, @NotNull FriendlyByteBuf data, boolean force) {
        instance.writeBuf(side, data);
    }

    @Override
    protected void readBuffer(@NotNull LogicalSide side, @NotNull IDataSerializable instance, @NotNull FriendlyByteBuf data) {
        instance.readBuf(side, data);
    }

    @Override
    protected @NotNull Data writeData(@NotNull Object source, @NotNull IDataSerializable instance) {
        return instance.writeData();
    }

    @Override
    protected void readData(@NotNull IDataSerializable instance, @NotNull Data data, int dataVersion) {
        instance.readData(data, dataVersion);
    }
}
