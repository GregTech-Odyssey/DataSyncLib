package com.gto.datasynclib.util.holder;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public class DoubleHolder {

    public double value;

    public DoubleHolder(double value) {
        this.value = value;
    }

    public final double get() {
        return value;
    }

    public final void set(double value) {
        this.value = value;
    }
}
