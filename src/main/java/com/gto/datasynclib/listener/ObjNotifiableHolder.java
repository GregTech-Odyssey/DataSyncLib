package com.gto.datasynclib.listener;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.LogicalSide;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

@Setter
@Accessors(chain = true)
/**
 * Mutable object holder with integrated sync notification support.
 * Extends ObjectSerializableHolder to add listener notification on sync events.
 * Implements both IDataSerializable and ISyncNotifiable.
 */
public final class ObjNotifiableHolder<T> extends ObjSerializableHolder<T> implements ISyncNotifiable<ObjNotifiableHolder, ObjSyncListener<T>> {

    public static <T> ObjNotifiableHolder<T> create(DataSyncCodec<T> codec) {
        return new ObjNotifiableHolder<>(codec);
    }

    public static <T> ObjNotifiableHolder<T> create(DataSyncCodec<T> codec, T value) {
        return new ObjNotifiableHolder<>(codec, value);
    }

    private ObjSyncListener<T> receiverListener = ObjSyncListener.EMPTY;
    private ObjSyncListener<T> senderListener = ObjSyncListener.EMPTY;

    private ObjNotifiableHolder(DataSyncCodec<T> codec) {
        super(codec);
    }

    private ObjNotifiableHolder(DataSyncCodec<T> codec, T value) {
        super(codec, value);
    }

    @Override
    public void writeBuffer(LogicalSide side, @NotNull FriendlyByteBuf data) {
        if (value == null) {
            data.writeBoolean(false);
        } else {
            data.writeBoolean(true);
            codec.streamWriter.encode(data, value);
        }
        senderListener.onSync(side, lastValue, value);
        lastValue = value;
    }

    @Override
    public void readBuffer(LogicalSide side, @NotNull FriendlyByteBuf data) {
        var oldValue = value;
        if (data.readBoolean()) {
            value = codec.streamReader.decode(data);
        } else {
            value = null;
        }
        receiverListener.onSync(side, oldValue, value);
    }
}
