package com.gto.datasynclib.listener;

import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datasream.data.Data;
import com.gto.datasynclib.datasream.data.IntData;
import com.gto.datasynclib.util.holder.IntHolder;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public final class IntNotifiableHolder extends IntHolder implements IDataSerializable, ISyncNotifiable<IntNotifiableHolder, IntSyncListener> {

    public static IntNotifiableHolder create() {
        return new IntNotifiableHolder();
    }

    public static IntNotifiableHolder create(int value) {
        return new IntNotifiableHolder(value);
    }

    @Setter
    @Accessors(chain = true)
    private IntSyncListener receiverListener = IntSyncListener.EMPTY;
    @Setter
    @Accessors(chain = true)
    private IntSyncListener senderListener = IntSyncListener.EMPTY;

    private int lastValue;
    private boolean syncChange;

    private IntNotifiableHolder() {
    }

    private IntNotifiableHolder(int value) {
        super(value);
    }

    @Override
    public void markAsChanged() {
        syncChange = true;
    }

    @Override
    public void clearChanged() {
        syncChange = false;
    }

    @Override
    public boolean isChanged() {
        return syncChange;
    }

    @Override
    public boolean detectChange() {
        return value != lastValue;
    }

    @Override
    public void writeBuf(LogicalSide side, @NotNull FriendlyByteBuf data) {
        data.writeInt(value);
        senderListener.onSync(side, lastValue, value);
        lastValue = value;
    }

    @Override
    public void readBuf(LogicalSide side, @NotNull FriendlyByteBuf data) {
        var oldValue = value;
        value = data.readInt();
        receiverListener.onSync(side, oldValue, value);
    }

    @Override
    public Data writeData() {
        return IntData.valueOf(value);
    }

    @Override
    public void readData(@NotNull Data data, int dataVersion) {
        value = data.getInt();
    }
}
