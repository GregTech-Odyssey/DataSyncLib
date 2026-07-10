package com.gto.datasynclib.datasream.data;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.ApiStatus;

public enum NullData implements ImmutableData {

    INSTANCE,

    /**
     * Do not save, runtime check
     * Generally used to prevent it from being saved
     */
    @ApiStatus.Internal
    NONE;

    @Override
    public byte[] writeToBytes() {
        return NULL_BYTES;
    }

    @Override
    public boolean isNull() {
        return true;
    }

    @Override
    public void write(ByteBuf stream) {
    }

    @Override
    public byte getId() {
        return NULL;
    }

    @Override
    public String toString() {
        return "null";
    }
}
