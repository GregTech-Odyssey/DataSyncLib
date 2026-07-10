package com.gto.datasynclib.util;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.function.Supplier;

@UtilityClass
public class HashUtil {

    public int arrayIdentityHashCode(@Nullable Object[] a) {
        if (a == null) return 0;
        int result = 1;
        for (var o : a) {
            result = 31 * result + System.identityHashCode(o);
        }
        return result;
    }

    public boolean arrayIdentityEquals(@Nullable Object[] a, @Nullable Object[] a2) {
        if (a == a2) return true;
        if (a == null || a2 == null) return false;
        int length = a.length;
        if (a2.length != length) return false;
        for (int i = 0; i < length; i++) {
            if (a[i] != a2[i]) return false;
        }
        return true;
    }

    public <T> Supplier<T[]> arrayIdentityWrapper(T[] array) {
        return new ArrayIdentityWrapper<>(array);
    }

    public <T> Supplier<T[]> arrayWrapper(T[] array) {
        return new ArrayWrapper<>(array);
    }

    private static class ArrayWrapper<T> implements Supplier<T[]> {

        final T[] array;

        private ArrayWrapper(T[] array) {
            this.array = array;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(array);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ArrayWrapper && Arrays.equals(array, ((ArrayWrapper<?>) obj).array);
        }

        @Override
        public final T[] get() {
            return array;
        }
    }

    private static final class ArrayIdentityWrapper<T> extends ArrayWrapper<T> {

        private ArrayIdentityWrapper(T[] array) {
            super(array);
        }

        @Override
        public int hashCode() {
            return arrayIdentityHashCode(array);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ArrayIdentityWrapper && arrayIdentityEquals(array, ((ArrayIdentityWrapper<?>) obj).array);
        }
    }
}
