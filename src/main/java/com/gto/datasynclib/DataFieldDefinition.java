package com.gto.datasynclib;

import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.util.ReflectUtil;
import it.unimi.dsi.fastutil.Hash;
import net.minecraft.network.FriendlyByteBuf;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;

/**
 * Defines the metadata, accessors, and serialization behavior for a single field managed by the
 * {@link FieldDataManager}. Each instance is created from a class field annotated with
 * {@code @SaveToDisk}, {@code @SyncToClient}, or {@code @SyncToServer}.
 *
 * <p>Provides typed getters/setters for all primitive types plus Object, change-detection
 * strategies, skip-sync/save condition handling, and encoding/decoding via both network buffers
 * ({@link FriendlyByteBuf}) and persistent {@link Data} objects.</p>
 *
 * @param <T> the declared type of the underlying field
 */
public final class DataFieldDefinition<T> {

    /**
     * Default fallback strategy using standard {@link Object#hashCode()} and {@link Object#equals(Object)}.
     * Handles null safely for both hash and equality checks.
     */
    public static final Hash.Strategy OBJECT_STRATEGY = new Hash.Strategy<>() {

        @Override
        public int hashCode(Object o) {
            return o == null ? 0 : o.hashCode();
        }

        @SuppressWarnings("EqualsReplaceableByObjectsCall")
        @Override
        public boolean equals(Object a, Object b) {
            return (a == b) || (a != null && a.equals(b));
        }
    };

    /**
     * Identity function used as the top-level source extractor for the root object.
     */
    static Function<Object, Object> SOURCE = Function.identity();
    /**
     * Sorts definitions by their key for deterministic ordering.
     */
    static final Comparator<DataFieldDefinition<?>> COMPARATOR = Comparator.comparing(d -> d.key);

    /**
     * The reflected Java field.
     */
    public final Field field;
    /**
     * Whether the field is declared {@code final}.
     */
    public final boolean isFinal;
    /**
     * Whether the access layer should create a new instance on read (vs. mutate in-place).
     */
    public final boolean createInstance;
    /**
     * The hash/equality strategy for change detection. Defaults to {@link #OBJECT_STRATEGY}.
     */
    public final Hash.Strategy<T> strategy;
    /**
     * Generic type arguments of the field, if any.
     */
    public final Class<?>[] genericType;
    /**
     * Codecs resolved for each generic type argument.
     */
    public final DataSyncCodec<?>[] genericCodecs;
    /**
     * Factory that creates the {@link DataField} implementation for this definition.
     */
    public final DataField.Factory<T> factory;
    /**
     * Function that extracts the owning object from the root holder. Supports nested {@code @AdditionalHolder}.
     */
    public final Function<Object, Object> source;
    /**
     * Whether this field is persisted to disk (annotated with {@code @SaveToDisk}).
     */
    public final boolean isSave;
    /**
     * The storage key for this field (annotation value or field name).
     */
    public final String key;
    /**
     * Whether null values should be persisted to disk.
     */
    public final boolean saveNull;
    /**
     * Whether this field syncs from server to client.
     */
    public final boolean isSyncToClient;
    /**
     * Whether this field syncs from client to server.
     */
    public final boolean isSyncToServer;

    private final Object defaultValue;
    private final MethodHandle defaultValueHandle;

    private final MethodHandle saveCondition;
    private final MethodHandle syncToClientCondition;
    private final MethodHandle syncToServerCondition;

    private final boolean notifyClientUpdate;
    private final boolean notifyServerUpdate;
    private final boolean autoSyncToClient;
    private final boolean autoSyncToServer;

    private final DataSyncCodec<T> codec;
    private final MethodHandle writeToData;
    private final MethodHandle readFromData;
    private final MethodHandle writeToBuffer;
    private final MethodHandle readFromBuffer;
    private final MethodHandle getter;
    private final MethodHandle setter;
    private final MethodHandle clientListenerHandle;
    private final MethodHandle serverListenerHandle;

    @SuppressWarnings("unchecked")
    DataFieldDefinition(Field field, DataField.Factory<T> factory, Function<Object, Object> source, FieldAnnotationMetadata fieldAnnotations, Class<?>[] genericType, boolean isFinal, boolean createInstance, Map<Class<?>, Hash.Strategy<?>> strategies) {
        this.field = field;
        this.factory = factory;
        this.source = source;
        this.key = fieldAnnotations.key();
        this.saveNull = fieldAnnotations.saveNull();
        this.defaultValue = fieldAnnotations.defaultValue();
        this.isSave = fieldAnnotations.isSave();
        this.isSyncToClient = fieldAnnotations.isSyncToClient();
        this.isSyncToServer = fieldAnnotations.isSyncToServer();
        this.notifyClientUpdate = fieldAnnotations.notifyClientUpdate();
        this.notifyServerUpdate = fieldAnnotations.notifyServerUpdate();
        this.autoSyncToClient = fieldAnnotations.autoSyncToClient();
        this.autoSyncToServer = fieldAnnotations.autoSyncToServer();
        this.createInstance = createInstance;
        this.codec = (isFinal || !createInstance) ? null : fieldAnnotations.dataCodec() != null ? DataSyncCodec.of(fieldAnnotations.streamCodec(), fieldAnnotations.dataCodec()) : (DataSyncCodec<T>) DataSyncCodec.get(field.getType());
        this.genericType = genericType;
        this.genericCodecs = new DataSyncCodec[genericType.length];
        this.isFinal = isFinal;
        this.strategy = fieldAnnotations.strategy() != null ? fieldAnnotations.strategy() : (Hash.Strategy<T>) strategies.getOrDefault(field.getType(), OBJECT_STRATEGY);
        for (int i = 0; i < genericType.length; i++) {
            this.genericCodecs[i] = DataSyncCodec.get(genericType[i]);
        }

        this.defaultValueHandle = ReflectUtil.createAdaptedMethodHandle(fieldAnnotations.defaultValueGetter());

        this.getter = ReflectUtil.createAdaptedGetter(field);
        this.setter = isFinal ? null : ReflectUtil.createAdaptedSetter(field);

        this.writeToData = ReflectUtil.createAdaptedMethodHandle(fieldAnnotations.writeToData(), Data.class);
        this.readFromData = ReflectUtil.createAdaptedMethodHandle(fieldAnnotations.readFromData());
        this.writeToBuffer = ReflectUtil.createAdaptedMethodHandle(fieldAnnotations.writeToBuffer());
        this.readFromBuffer = ReflectUtil.createAdaptedMethodHandle(fieldAnnotations.readFromBuffer());

        this.clientListenerHandle = ReflectUtil.createAdaptedMethodHandle(fieldAnnotations.clientUpdateListener());
        this.serverListenerHandle = ReflectUtil.createAdaptedMethodHandle(fieldAnnotations.serverUpdateListener());
        this.saveCondition = ReflectUtil.createAdaptedMethodHandle(fieldAnnotations.saveCondition(), boolean.class);
        this.syncToClientCondition = ReflectUtil.createAdaptedMethodHandle(fieldAnnotations.syncToClientCondition(), boolean.class);
        this.syncToServerCondition = ReflectUtil.createAdaptedMethodHandle(fieldAnnotations.syncToServerCondition(), boolean.class);
    }

    public boolean hasDefaultValue() {
        return defaultValue != null || defaultValueHandle != null;
    }

    public boolean getDefaultBooleanValue(Object source) {
        if (defaultValueHandle != null) {
            try {
                return (boolean) defaultValueHandle.invokeExact(source);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        return (Boolean) defaultValue;
    }

    public byte getDefaultByteValue(Object source) {
        if (defaultValueHandle != null) {
            try {
                return (Byte) defaultValueHandle.invokeExact(source);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        return (Byte) defaultValue;
    }

    public short getDefaultShortValue(Object source) {
        if (defaultValueHandle != null) {
            try {
                return (Short) defaultValueHandle.invokeExact(source);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        return (Short) defaultValue;
    }

    public int getDefaultIntValue(Object source) {
        if (defaultValueHandle != null) {
            try {
                return (int) defaultValueHandle.invokeExact(source);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        return (Integer) defaultValue;
    }

    public long getDefaultLongValue(Object source) {
        if (defaultValueHandle != null) {
            try {
                return (long) defaultValueHandle.invokeExact(source);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        return (Long) defaultValue;
    }

    public float getDefaultFloatValue(Object source) {
        if (defaultValueHandle != null) {
            try {
                return (float) defaultValueHandle.invokeExact(source);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        return (Float) defaultValue;
    }

    public double getDefaultDoubleValue(Object source) {
        if (defaultValueHandle != null) {
            try {
                return (double) defaultValueHandle.invokeExact(source);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        return (Double) defaultValue;
    }

    public char getDefaultCharValue(Object source) {
        if (defaultValueHandle != null) {
            try {
                return (char) defaultValueHandle.invokeExact(source);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        return (Character) defaultValue;
    }

    public T getDefaultValue(Object source) {
        if (defaultValueHandle != null) {
            try {
                return (T) defaultValueHandle.invokeExact(source);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        return (T) defaultValue;
    }

    public boolean skipSync(LogicalSide side, Object source, boolean value) {
        var conditions = side == LogicalSide.CLIENT ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, byte value) {
        var conditions = side == LogicalSide.CLIENT ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, short value) {
        var conditions = side == LogicalSide.CLIENT ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, int value) {
        var conditions = side == LogicalSide.CLIENT ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, long value) {
        var conditions = side == LogicalSide.CLIENT ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, float value) {
        var conditions = side == LogicalSide.CLIENT ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, double value) {
        var conditions = side == LogicalSide.CLIENT ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, char value) {
        var conditions = side == LogicalSide.CLIENT ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, T value) {
        var conditions = side == LogicalSide.CLIENT ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSave(Object source, boolean value) {
        var conditions = this.saveCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSave(Object source, byte value) {
        var conditions = this.saveCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSave(Object source, short value) {
        var conditions = this.saveCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSave(Object source, int value) {
        var conditions = this.saveCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSave(Object source, long value) {
        var conditions = this.saveCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSave(Object source, float value) {
        var conditions = this.saveCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSave(Object source, double value) {
        var conditions = this.saveCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSave(Object source, char value) {
        var conditions = this.saveCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSave(Object source, T value) {
        var conditions = this.saveCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public T get(Object source) {
        try {
            return (T) getter.invokeExact(source);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get field: " + field, e);
        }
    }

    public void set(Object source, T value) {
        if (setter == null) return;
        try {
            setter.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to set field: " + field, e);
        }
    }

    public int getInt(Object source) {
        try {
            return (int) getter.invokeExact(source);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get int field: " + field, e);
        }
    }

    public void setInt(Object source, int value) {
        try {
            setter.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to set int field: " + field, e);
        }
    }

    public long getLong(Object source) {
        try {
            return (long) getter.invokeExact(source);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get long field: " + field, e);
        }
    }

    public void setLong(Object source, long value) {
        try {
            setter.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to set long field: " + field, e);
        }
    }

    public float getFloat(Object source) {
        try {
            return (float) getter.invokeExact(source);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get float field: " + field, e);
        }
    }

    public void setFloat(Object source, float value) {
        try {
            setter.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to set float field: " + field, e);
        }
    }

    public double getDouble(Object source) {
        try {
            return (double) getter.invokeExact(source);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get double field: " + field, e);
        }
    }

    public void setDouble(Object source, double value) {
        try {
            setter.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to set double field: " + field, e);
        }
    }

    public boolean getBoolean(Object source) {
        try {
            return (boolean) getter.invokeExact(source);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get boolean field: " + field, e);
        }
    }

    public void setBoolean(Object source, boolean value) {
        try {
            setter.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to set boolean field: " + field, e);
        }
    }

    public short getShort(Object source) {
        try {
            return (short) getter.invokeExact(source);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get short field: " + field, e);
        }
    }

    public void setShort(Object source, short value) {
        try {
            setter.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to set short field: " + field, e);
        }
    }

    public byte getByte(Object source) {
        try {
            return (byte) getter.invokeExact(source);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get byte field: " + field, e);
        }
    }

    public void setByte(Object source, byte value) {
        try {
            setter.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to set byte field: " + field, e);
        }
    }

    public char getChar(Object source) {
        try {
            return (char) getter.invokeExact(source);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get char field: " + field, e);
        }
    }

    public void setChar(Object source, char value) {
        try {
            setter.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to set char field: " + field, e);
        }
    }

    public MethodHandle getListener(LogicalSide side) {
        return side == LogicalSide.CLIENT ? clientListenerHandle : serverListenerHandle;
    }

    boolean notifyUpdate(LogicalSide side) {
        return side == LogicalSide.CLIENT ? notifyClientUpdate : notifyServerUpdate;
    }

    boolean autoUpdate(LogicalSide side) {
        return side == LogicalSide.CLIENT ? autoSyncToServer : autoSyncToClient;
    }

    public Data encode(Object source, T obj) {
        if (writeToData != null) {
            try {
                return (Data) writeToData.invokeExact(source, obj);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        } else {
            return codec.dataWriter.encode(obj);
        }
    }

    public T decode(Object source, Data data, int dataVersion) {
        if (readFromData != null) {
            try {
                return (T) readFromData.invokeExact(source, (Object) data, dataVersion);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        } else {
            return codec.dataReader.decode(data, dataVersion);
        }
    }

    public void encode(Object source, T obj, FriendlyByteBuf data) {
        if (writeToBuffer != null) {
            try {
                writeToBuffer.invokeExact(source, (Object) data, obj);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        } else {
            codec.streamWriter.encode(data, obj);
        }
    }

    public T decode(Object source, FriendlyByteBuf data) {
        if (readFromBuffer != null) {
            try {
                return (T) readFromBuffer.invokeExact(source, (Object) data);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        } else {
            return codec.streamReader.decode(data);
        }
    }
}
