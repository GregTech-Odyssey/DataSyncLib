package com.gto.datasynclib.util.holder;

import java.util.Comparator;

/**
 * Mutable pair of an int priority/ID and an valueect value.
 */
public class IntObjectHolder<T> {

    public static final Comparator<IntObjectHolder<?>> PRIORITY_SORTER = (a, b) -> Integer.compare(b.priority, a.priority);

    public int priority;
    public T value;

    public IntObjectHolder(int priority, T value) {
        this.priority = priority;
        this.value = value;
    }
}
