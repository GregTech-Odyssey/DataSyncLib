package com.gto.datasynclib.util.holder;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public class ByteHolder {

    public byte value;

    public ByteHolder(byte value) {
        this.value = value;
    }

    public final byte get() {
        return value;
    }

    public final void set(byte value) {
        this.value = value;
    }
}
