package com.gto.datasynclib.util.holder;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public class CharHolder {

    public char value;

    public CharHolder(char value) {
        this.value = value;
    }

    public final char get() {
        return value;
    }

    public final void set(char value) {
        this.value = value;
    }
}
