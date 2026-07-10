package com.gto.datasynclib.util.holder;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public class LongHolder {

    public long value;

    public LongHolder(long value) {
        this.value = value;
    }

    public final long get() {
        return value;
    }

    public final void set(long value) {
        this.value = value;
    }
}
