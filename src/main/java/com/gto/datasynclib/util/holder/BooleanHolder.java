package com.gto.datasynclib.util.holder;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public class BooleanHolder {

    public boolean value;

    public BooleanHolder(boolean value) {
        this.value = value;
    }

    public final boolean get() {
        return value;
    }

    public final void set(boolean value) {
        this.value = value;
    }
}
