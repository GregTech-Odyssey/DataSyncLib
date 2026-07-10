package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Accessor for objects implementing IDataSerializable.
 * Delegates all change detection and serialization to the IDataSerializable instance itself.
 */
public final class SerializableAccess extends AbstractFieldAccess<IDataSerializable> {

    public SerializableAccess(DataFieldDefinition<IDataSerializable> definition) {
        super(definition);
    }

    @Override
    public void markAsChanged(@NotNull Object source) {
        changed = true;
        var instance = getInstance(source);
        if (instance == null) return;
        instance.markAsChanged();
    }

    @Override
    public void clearChanged(@NotNull Object source) {
        changed = false;
        var instance = getInstance(source);
        if (instance == null) return;
        instance.clearChanged();
    }

    @Override
    public boolean isChanged(@NotNull Object source) {
        var instance = getInstance(source);
        if (instance == null) return changed;
        return changed || instance.isChanged();
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull IDataSerializable instance, boolean auto) {
        return instance.detectChange();
    }

    @Override
    protected void writeBuffer(@NotNull LogicalSide side, @NotNull IDataSerializable instance, @NotNull FriendlyByteBuf data, boolean force) {
        instance.writeBuffer(side, data);
    }

    @Override
    protected void readBuffer(@NotNull LogicalSide side, @NotNull IDataSerializable instance, @NotNull FriendlyByteBuf data) {
        instance.readBuffer(side, data);
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
