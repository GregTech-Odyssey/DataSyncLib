package com.gto.datasynclib;

import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.util.ReflectUtil;
import it.unimi.dsi.fastutil.Hash;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
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
    @Nullable
    public final Function<Object, Object> source;

    /**
     * Forward conversion function resolved from {@link com.gto.datasynclib.annotations.Conversion @Conversion}
     * annotation's {@code getFunction}. Converts from the field's declared type to the managed type
     * when <strong>reading</strong> the field value (e.g., {@code CompoundTag → Map<String, Tag>}).
     *
     * <p>This is applied automatically in {@link #get(Object)}: if non-null, the raw value from
     * the VarHandle getter is passed through this function before being returned. The downstream
     * codecs, strategies, and factories all operate on the <em>converted</em> type.</p>
     *
     * <p>{@code null} if the field has no {@code @Conversion} annotation.</p>
     */
    @Nullable
    public final Function<Object, T> conversionGet;

    /**
     * Reverse conversion function resolved from {@link com.gto.datasynclib.annotations.Conversion @Conversion}
     * annotation's {@code setFunction}. Converts from the managed type back to the field's
     * declared type when <strong>writing</strong> the field value (e.g., {@code Map<String, Tag> → CompoundTag}).
     *
     * <p>This is applied automatically in {@link #set(Object, Object)}: if non-null, the incoming
     * value is passed through this function before being written to the field via the VarHandle setter.</p>
     *
     * <p>{@code null} if the field has no {@code @Conversion} annotation, or if the annotation's
     * {@code setFunction} was empty/absent (common for {@code final} or access-mode fields).</p>
     */
    @Nullable
    public final Function<T, Object> conversionSet;

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
    private final VarHandle getter;
    private final VarHandle setter;
    private final MethodHandle clientListenerHandle;
    private final MethodHandle serverListenerHandle;

    @SuppressWarnings("unchecked")
    DataFieldDefinition(MethodHandles.Lookup lookup, Field field, Class<?> type, DataField.Factory<T> factory, @Nullable Function<Object, Object> source, FieldAnnotationMetadata fieldAnnotations, Class<?>[] genericType, boolean isFinal, boolean createInstance, Map<Class<?>, Hash.Strategy<?>> strategies, @Nullable Function<Object, T> conversionGet, @Nullable Function<T, Object> conversionSet) {
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
        this.conversionGet = conversionGet;
        this.conversionSet = conversionSet;
        this.codec = (isFinal || !createInstance) ? null : fieldAnnotations.dataCodec() != null ? DataSyncCodec.of(fieldAnnotations.streamCodec(), fieldAnnotations.dataCodec()) : (DataSyncCodec<T>) DataSyncCodec.get(type);
        this.genericType = genericType;
        this.genericCodecs = new DataSyncCodec[genericType.length];
        this.isFinal = isFinal;
        this.strategy = fieldAnnotations.strategy() != null ? fieldAnnotations.strategy() : (Hash.Strategy<T>) strategies.getOrDefault(type, OBJECT_STRATEGY);
        for (int i = 0; i < genericType.length; i++) {
            this.genericCodecs[i] = DataSyncCodec.get(genericType[i]);
        }

        this.defaultValueHandle = ReflectUtil.createAdaptedMethodHandle(lookup, fieldAnnotations.defaultValueGetter());

        this.getter = ReflectUtil.createVarHandle(lookup, field);
        this.setter = isFinal ? null : ReflectUtil.createVarHandle(lookup, field);

        this.writeToData = ReflectUtil.createAdaptedMethodHandle(lookup, fieldAnnotations.writeToData(), Data.class);
        this.readFromData = ReflectUtil.createAdaptedMethodHandle(lookup, fieldAnnotations.readFromData());
        this.writeToBuffer = ReflectUtil.createAdaptedMethodHandle(lookup, fieldAnnotations.writeToBuffer());
        this.readFromBuffer = ReflectUtil.createAdaptedMethodHandle(lookup, fieldAnnotations.readFromBuffer());

        this.clientListenerHandle = ReflectUtil.createAdaptedMethodHandle(lookup, fieldAnnotations.clientUpdateListener());
        this.serverListenerHandle = ReflectUtil.createAdaptedMethodHandle(lookup, fieldAnnotations.serverUpdateListener());
        this.saveCondition = ReflectUtil.createAdaptedMethodHandle(lookup, fieldAnnotations.saveCondition(), boolean.class);
        this.syncToClientCondition = ReflectUtil.createAdaptedMethodHandle(lookup, fieldAnnotations.syncToClientCondition(), boolean.class);
        this.syncToServerCondition = ReflectUtil.createAdaptedMethodHandle(lookup, fieldAnnotations.syncToServerCondition(), boolean.class);
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
                return (byte) defaultValueHandle.invokeExact(source);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        return (Byte) defaultValue;
    }

    public short getDefaultShortValue(Object source) {
        if (defaultValueHandle != null) {
            try {
                return (short) defaultValueHandle.invokeExact(source);
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
        var conditions = side.isClient() ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, byte value) {
        var conditions = side.isClient() ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, short value) {
        var conditions = side.isClient() ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, int value) {
        var conditions = side.isClient() ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, long value) {
        var conditions = side.isClient() ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, float value) {
        var conditions = side.isClient() ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, double value) {
        var conditions = side.isClient() ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, char value) {
        var conditions = side.isClient() ? this.syncToServerCondition : this.syncToClientCondition;
        if (conditions == null) return false;
        try {
            return (boolean) conditions.invokeExact(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public boolean skipSync(LogicalSide side, Object source, T value) {
        var conditions = side.isClient() ? this.syncToServerCondition : this.syncToClientCondition;
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

    /**
     * Reads the field's value from the given source object.
     *
     * <p>If a {@link #conversionGet} function is configured (via {@code @Conversion}),
     * the raw field value is passed through the function before being returned.
     * This means callers always receive the <em>managed</em> type, not the stored type.</p>
     *
     * @param source the object instance to read the field from
     * @return the field value, after optional conversion
     */
    @SuppressWarnings("unchecked")
    public T get(Object source) {
        var obj = (T) getter.get(source);
        if (conversionGet != null) return conversionGet.apply(obj);
        return obj;
    }

    /**
     * Writes a value to the field on the given source object.
     *
     * <p>If a {@link #conversionSet} function is configured (via {@code @Conversion}),
     * the value is first passed through the reverse conversion before being written to
     * the actual field. For example, a {@code Map<String, Tag>} value would be converted
     * back to {@code CompoundTag} before storage.</p>
     *
     * <p>This is a no-op if the field is {@code final} ({@code setter == null}).</p>
     *
     * @param source the object instance to write the field to
     * @param value  the value to set (in the managed type, before reverse conversion)
     */
    public void set(Object source, T value) {
        if (setter == null) return;
        if (conversionSet != null) {
            setter.set(source, conversionSet.apply(value));
        } else {
            setter.set(source, value);
        }
    }

    public int getInt(Object source) {
        return (int) getter.get(source);
    }

    public void setInt(Object source, int value) {
        setter.set(source, value);
    }

    public long getLong(Object source) {
        return (long) getter.get(source);
    }

    public void setLong(Object source, long value) {
        setter.set(source, value);
    }

    public float getFloat(Object source) {
        return (float) getter.get(source);
    }

    public void setFloat(Object source, float value) {
        setter.set(source, value);
    }

    public double getDouble(Object source) {
        return (double) getter.get(source);
    }

    public void setDouble(Object source, double value) {
        setter.set(source, value);
    }

    public boolean getBoolean(Object source) {
        return (boolean) getter.get(source);
    }

    public void setBoolean(Object source, boolean value) {
        setter.set(source, value);
    }

    public short getShort(Object source) {
        return (short) getter.get(source);
    }

    public void setShort(Object source, short value) {
        setter.set(source, value);
    }

    public byte getByte(Object source) {
        return (byte) getter.get(source);
    }

    public void setByte(Object source, byte value) {
        setter.set(source, value);
    }

    public char getChar(Object source) {
        return (char) getter.get(source);
    }

    public void setChar(Object source, char value) {
        setter.set(source, value);
    }

    public MethodHandle getListener(LogicalSide side) {
        return side.isClient() ? clientListenerHandle : serverListenerHandle;
    }

    boolean notifyUpdate(LogicalSide side) {
        return side.isClient() ? notifyClientUpdate : notifyServerUpdate;
    }

    boolean autoUpdate(LogicalSide side) {
        return side.isServer() ? autoSyncToClient : autoSyncToServer;
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
