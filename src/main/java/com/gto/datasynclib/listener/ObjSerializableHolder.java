package com.gto.datasynclib.listener;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.codec.ValueOps;
import com.gto.datasynclib.util.holder.ObjHolder;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Mutable object holder with codec-based serialization support.
 * Implements IDataSerializable using a DataSyncCodec for encoding/decoding.
 * Supports nullable values with null-vs-value boolean prefix encoding.
 */
public class ObjSerializableHolder<T> extends ObjHolder<T> implements IDataSerializable {

    public static <T> ObjSerializableHolder<T> create(DataSyncCodec<T> codec) {
        return new ObjSerializableHolder<>(codec);
    }

    public static <T> ObjSerializableHolder<T> create(DataSyncCodec<T> codec, T value) {
        return new ObjSerializableHolder<>(codec, value);
    }

    protected T lastValue;
    protected int lastHash;
    private boolean changed;

    protected final DataSyncCodec<T> codec;

    protected ObjSerializableHolder(DataSyncCodec<T> codec) {
        this.codec = codec;
    }

    protected ObjSerializableHolder(DataSyncCodec<T> codec, T value) {
        super(value);
        this.codec = codec;
    }

    @Override
    public void markAsChanged() {
        changed = true;
    }

    @Override
    public void clearChanged() {
        changed = false;
    }

    @Override
    public boolean isChanged() {
        return changed;
    }

    @Override
    public boolean detectChange() {
        var hash = Objects.hashCode(value);
        if (hash != lastHash) {
            lastHash = hash;
            return true;
        }
        return !Objects.equals(lastValue, value);
    }

    @Override
    public void writeBuffer(LogicalSide side, @NotNull FriendlyByteBuf data) {
        if (value == null) {
            data.writeBoolean(false);
        } else {
            data.writeBoolean(true);
            codec.streamWriter.encode(data, value);
        }
        lastValue = value;
    }

    @Override
    public void readBuffer(LogicalSide side, @NotNull FriendlyByteBuf data) {
        if (data.readBoolean()) {
            value = codec.streamReader.decode(data);
        } else {
            value = null;
        }
    }

    @Override
    public @NotNull Object writeValue(@NotNull ValueOps ops) {
        if (value == null) {
            return ops.createNull();
        } else {
            return codec.encode(ops, value);
        }
    }

    @Override
    public void readValue(@NotNull Object data, @NotNull ValueOps ops) {
        if (ops.isNull(data)) {
            value = null;
        } else {
            value = codec.decode(ops, data);
        }
    }
}
