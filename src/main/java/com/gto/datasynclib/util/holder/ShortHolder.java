package com.gto.datasynclib.util.holder;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public class ShortHolder {

    public short value;

    public ShortHolder(short value) {
        this.value = value;
    }

    public final short get() {
        return value;
    }

    public final void set(short value) {
        this.value = value;
    }
}
