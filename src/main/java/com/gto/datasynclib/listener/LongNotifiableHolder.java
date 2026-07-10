package com.gto.datasynclib.listener;

import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datasream.data.Data;
import com.gto.datasynclib.datasream.data.LongData;
import com.gto.datasynclib.util.holder.LongHolder;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public final class LongNotifiableHolder extends LongHolder implements IDataSerializable, ISyncNotifiable<LongNotifiableHolder, LongSyncListener> {

    public static LongNotifiableHolder create() {
        return new LongNotifiableHolder();
    }

    public static LongNotifiableHolder create(long value) {
        return new LongNotifiableHolder(value);
    }

    @Setter
    @Accessors(chain = true)
    private LongSyncListener receiverListener = LongSyncListener.EMPTY;
    @Setter
    @Accessors(chain = true)
    private LongSyncListener senderListener = LongSyncListener.EMPTY;

    private long lastValue;
    private boolean syncChange;

    private LongNotifiableHolder() {
    }

    private LongNotifiableHolder(long value) {
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
        data.writeLong(value);
        senderListener.onSync(side, lastValue, value);
        lastValue = value;
    }

    @Override
    public void readBuf(LogicalSide side, @NotNull FriendlyByteBuf data) {
        var oldValue = value;
        value = data.readLong();
        receiverListener.onSync(side, oldValue, value);
    }

    @Override
    public Data writeData() {
        return LongData.valueOf(value);
    }

    @Override
    public void readData(@NotNull Data data, int dataVersion) {
        value = data.getLong();
    }
}
