package com.gto.datasynclib.datasream;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

public final class DataComponentMap extends AbstractDataComponentMap<DataComponentKey<?>, Object> {

    public DataComponentMap() {
        super(2, 0.75F);
    }

    public DataComponentMap(int size) {
        super(size, 0.75F);
    }

    public DataComponentMap(DataComponentMap map) {
        super(map.size, 0.75F);
        map.fastForEach(this::put);
    }

    public void fastForEach(BiConsumer<DataComponentKey, Object> consumer) {
        final Object[] key = this.key;
        final Object[] value = this.value;
        final int len = key.length;
        int pos = 0;
        Object curr;
        while (pos < len) {
            if ((curr = key[pos]) != null) consumer.accept((DataComponentKey) curr, value[pos]);
            pos++;
        }
    }

    public <T> T getData(DataComponentKey<T> dataKey) {
        final Object[] key = this.key;
        Object curr;
        int pos;
        if ((curr = key[pos = dataKey.mixCode & this.mask]) == null) {
            return null;
        } else if (dataKey == curr) {
            return (T) this.value[pos];
        } else {
            while ((curr = key[pos = pos + 1 & this.mask]) != null) {
                if (dataKey == curr) {
                    return (T) this.value[pos];
                }
            }
            return null;
        }
    }

    public <T> T getOrDefaultData(DataComponentKey<T> dataKey, T defaultValue) {
        final Object[] key = this.key;
        Object curr;
        int pos;
        if ((curr = key[pos = dataKey.mixCode & this.mask]) == null) {
            return defaultValue;
        } else if (dataKey == curr) {
            return (T) this.value[pos];
        } else {
            while ((curr = key[pos = pos + 1 & this.mask]) != null) {
                if (dataKey == curr) {
                    return (T) this.value[pos];
                }
            }
            return defaultValue;
        }
    }

    public <T> T getOrCreateData(DataComponentKey<T> dataKey, Supplier<T> creator) {
        final Object[] key = this.key;
        int pos;
        Object curr;
        if ((curr = key[pos = dataKey.mixCode & mask]) != null) {
            do if (curr == dataKey) {
                final Object oldValue = value[pos];
                if (oldValue != null) return (T) oldValue;
                var v = creator.get();
                value[pos] = v;
                return v;
            }
            while ((curr = key[pos = (pos + 1) & mask]) != null);
        }
        key[pos] = dataKey;
        var v = creator.get();
        value[pos] = v;
        if (size++ >= maxFill) rehash(arraySize(size + 1, f));
        return v;
    }

    public <T> T getOrPut(DataComponentKey<T> dataKey, T data) {
        final Object[] key = this.key;
        int pos;
        Object curr;
        if ((curr = key[pos = dataKey.mixCode & mask]) != null) {
            do if (curr == dataKey) {
                final Object oldValue = value[pos];
                if (oldValue != null) return (T) oldValue;
                value[pos] = data;
                return data;
            }
            while ((curr = key[pos = (pos + 1) & mask]) != null);
        }
        key[pos] = dataKey;
        value[pos] = data;
        if (size++ >= maxFill) rehash(arraySize(size + 1, f));
        return data;
    }

    @Override
    public DataComponentMap clone() {
        return (DataComponentMap) super.clone();
    }

    public Object merge(DataComponentKey<?> dataKey, Object v) {
        final Object[] key = this.key;
        int pos;
        Object curr;
        if ((curr = key[pos = dataKey.mixCode & mask]) != null) {
            do if (curr == dataKey) {
                final Object oldValue = value[pos];
                if (oldValue != null) {
                    value[pos] = ((DataComponentKey) dataKey).mergeFunction.apply(oldValue, v);
                } else {
                    value[pos] = ((DataComponentKey) dataKey).createFunction.apply(v);
                }
                return oldValue;
            }
            while ((curr = key[pos = (pos + 1) & mask]) != null);
        }
        key[pos] = dataKey;
        value[pos] = ((DataComponentKey) dataKey).createFunction.apply(v);
        if (size++ >= maxFill) rehash(arraySize(size + 1, f));
        return null;
    }

    public void merge(DataComponentMap map) {
        map.fastForEach(this::merge);
    }

    public boolean contains(DataComponentKey<?> dataKey) {
        final Object[] key = this.key;
        Object curr;
        int pos;
        if ((curr = key[pos = dataKey.mixCode & this.mask]) == null) {
            return false;
        } else if (dataKey == curr) {
            return true;
        } else {
            while ((curr = key[pos = pos + 1 & this.mask]) != null) {
                if (dataKey == curr) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean getBoolean(DataComponentKey<Boolean> dataKey) {
        var data = getData(dataKey);
        return data != null && data;
    }

    public byte getByte(DataComponentKey<Byte> dataKey) {
        var data = getData(dataKey);
        return data == null ? 0 : data;
    }

    public short getShort(DataComponentKey<Short> dataKey) {
        var data = getData(dataKey);
        return data == null ? 0 : data;
    }

    public char getChar(DataComponentKey<Character> dataKey) {
        var data = getData(dataKey);
        return data == null ? 0 : data;
    }

    public int getInt(DataComponentKey<Integer> dataKey) {
        var data = getData(dataKey);
        return data == null ? 0 : data;
    }

    public long getLong(DataComponentKey<Long> dataKey) {
        var data = getData(dataKey);
        return data == null ? 0 : data;
    }

    public double getDouble(DataComponentKey<Double> dataKey) {
        var data = getData(dataKey);
        return data == null ? 0 : data;
    }

    public float getFloat(DataComponentKey<Float> dataKey) {
        var data = getData(dataKey);
        return data == null ? 0 : data;
    }

    public String getString(DataComponentKey<String> dataKey) {
        var data = getData(dataKey);
        return data == null ? "" : data;
    }
}
