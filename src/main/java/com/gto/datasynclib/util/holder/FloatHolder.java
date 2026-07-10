package com.gto.datasynclib.util.holder;

import lombok.NoArgsConstructor;

/**
 * Mutable wrapper for float values.
 */
@NoArgsConstructor
public class FloatHolder {

    public float value;

    public FloatHolder(float value) {
        this.value = value;
    }

    public final float get() {
        return value;
    }

    public final void set(float value) {
        this.value = value;
    }
}
