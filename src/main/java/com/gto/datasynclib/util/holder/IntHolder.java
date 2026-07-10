package com.gto.datasynclib.util.holder;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public class IntHolder {

    public int value;

    public IntHolder(int value) {
        this.value = value;
    }

    public final int get() {
        return value;
    }

    public final void set(int value) {
        this.value = value;
    }
}
