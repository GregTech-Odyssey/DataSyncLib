package com.gto.datasynclib.util;

import it.unimi.dsi.fastutil.Hash;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Hash/equality helpers used to build array-shaped cache keys.
 *
 * <p>{@link #arrayWrapper(Object[])} wraps an array with {@link java.util.Arrays}-style
 * (value-based) {@code equals}/{@code hashCode}, while {@link #arrayIdentityWrapper(Object[])}
 * compares the elements by <em>reference</em> ({@link System#identityHashCode}). Both return a
 * {@link java.util.function.Supplier} so the wrapped array can be passed straight to a cache
 * lookup. The wrapper classes themselves are private implementation details.</p>
 *
 * <p>{@link #arrayStrategy(Hash.Strategy)} is the {@link Hash.Strategy} counterpart of the same
 * idea: it turns the strategy of an element type into the strategy of its array type, which is what
 * a change-detecting array field needs (see
 * {@link com.gto.datasynclib.FieldDefinitionStorage#registerStrategy(Class, Hash.Strategy)}).</p>
 */
@UtilityClass
public class HashUtil {

    /**
     * Builds the {@code T[]} counterpart of a scalar strategy: the array is hashed and compared slot
     * by slot with {@code element}, so both a content-sensitive element strategy (a custom
     * {@code equals} that the element type does not have itself) and a coarser one (ignore part of
     * the state) carry over to arrays of that element.
     *
     * <p>Register the result for the field's <b>exact</b> array type — an array does not inherit the
     * element type's registration:</p>
     *
     * <pre>{@code
     * FieldDefinitionStorage.registerStrategy(MyKey[].class, HashUtil.arrayStrategy(MyKeyHashStrategy.ALL));
     * }</pre>
     *
     * <p>A {@code null} array hashes to 0; for individual slots it is up to {@code element} to decide
     * what {@code null} means, so a strategy that treats {@code null} as "empty" keeps doing so inside
     * an array. {@link com.gto.datasynclib.util.ItemStackArrayHashStrategy} and
     * {@link com.gto.datasynclib.util.FluidStackArrayHashStrategy} are built on this method.</p>
     *
     * @param element the strategy applied to every slot
     */
    public <T> Hash.Strategy<T[]> arrayStrategy(Hash.Strategy<T> element) {
        return new Hash.Strategy<>() {

            @Override
            public int hashCode(@Nullable T[] a) {
                if (a == null) return 0;
                int result = 1;
                for (var o : a) {
                    result = 31 * result + element.hashCode(o);
                }
                return result;
            }

            @Override
            public boolean equals(@Nullable T[] a, @Nullable T[] b) {
                if (a == b) return true;
                if (a == null || b == null || a.length != b.length) return false;
                for (int i = 0; i < a.length; i++) {
                    if (!element.equals(a[i], b[i])) return false;
                }
                return true;
            }
        };
    }

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
