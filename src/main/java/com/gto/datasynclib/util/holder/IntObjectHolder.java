package com.gto.datasynclib.util.holder;

import java.util.Comparator;

public class IntObjectHolder<T> {

    public static final Comparator<IntObjectHolder<?>> PRIORITY_SORTER = (a, b) -> Integer.compare(b.number, a.number);

    public int number;
    public T obj;

    public IntObjectHolder(int number, T obj) {
        this.number = number;
        this.obj = obj;
    }
}
