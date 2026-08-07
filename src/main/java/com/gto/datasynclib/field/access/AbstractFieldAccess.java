package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataField;
import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.ListData;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.datastream.data.StringMapData;
import lombok.Getter;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Base class for <strong>container-type</strong> field implementations (collections, maps,
 * arrays, {@link com.gto.datasynclib.IFieldDataHolder IFieldDataHolder}, and
 * {@link com.gto.datasynclib.IDataSerializable IDataSerializable}).
 *
 * <p>Uses the <strong>template method</strong> pattern with a clear naming convention:</p>
 * <ul>
 *   <li><strong>Public interface methods</strong> (from {@link com.gto.datasynclib.DataField}):
 *     {@link #detectChange}, {@link #writeToBuffer}, {@link #readFromBuffer},
 *     {@link #writeToData}, {@link #readFromData} — handle the common logic:
 *     null checks, instance identity tracking, {@code createInstance} wrapping,
 *     skip conditions, and listener invocation.</li>
 *   <li><strong>Protected template methods</strong> (implemented by subclasses):
 *     {@link #hasChange}, {@link #doWriteBuffer}, {@link #doReadBuffer},
 *     {@link #doWriteData}, {@link #doReadData} — provide type-specific
 *     serialization logic for the container's <em>contents</em> only.</li>
 * </ul>
 *
 * <h3>Key behaviors:</h3>
 * <ul>
 *   <li><strong>Instance tracking:</strong> {@link #detectChange} compares the current
 *       object reference against the previously-seen one. If a different object is now
 *       assigned to the field, the field is automatically marked as changed. If it's
 *       the same object, content-level change detection ({@link #hasChange}) is invoked.</li>
 *   <li><strong>{@code createInstance} mode:</strong> When the field's definition has
 *       {@link com.gto.datasynclib.DataFieldDefinition#createInstance} = {@code true},
 *       the container itself (not just its contents) is serialized. This handles fields
 *       that may be {@code null} and need full instance replacement on deserialization.
 *       Also supports a legacy data version ({@code dataVersion == -1}) migration path.</li>
 *   <li><strong>Listener invocation:</strong> After reading from buffer, if a listener
 *       {@link java.lang.invoke.MethodHandle} is registered on the definition, it's
 *       invoked with {@code (source, newValue, oldInstance)}.</li>
 * </ul>
 *
 * @param <T> the type of the access-managed container (e.g., {@code Collection}, {@code Map}, array)
 * @see com.gto.datasynclib.field.AbstractField
 */
public abstract class AbstractFieldAccess<T> implements DataField<T> {

    @Getter
    protected final DataFieldDefinition<T> definition;

    protected T instance;

    protected boolean changed;

    protected AbstractFieldAccess(DataFieldDefinition<T> definition) {
        this.definition = definition;
    }

    @Nullable
    protected T getInstance(Object source) {
        var definition = this.definition;
        if (definition.isFinal) {
            if (instance != null) return instance;
            return instance = definition.get(source);
        }
        return definition.get(source);
    }

    @Override
    public void markAsChanged(@NotNull Object source) {
        changed = true;
    }

    @Override
    public void clearChanged(@NotNull Object source) {
        changed = false;
    }

    @Override
    public boolean isChanged(@NotNull Object source) {
        return changed;
    }

    @Override
    public boolean detectChange(@NotNull LogicalSide side, @NotNull Object source, boolean autoOnly) {
        var instance = getInstance(source);
        if (definition.skipSync(side, source, instance)) return false;
        if (this.instance != instance) {
            this.instance = instance;
            markAsChanged(source);
            if (instance != null && mustDetect()) hasChange(side, instance, autoOnly);
            return true;
        }
        if (instance == null) return changed;
        if (hasChange(side, instance, autoOnly)) {
            markAsChanged(source);
            return true;
        }
        return changed;
    }

    @Override
    public final void writeToBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data, boolean writeAll) {
        var value = getInstance(source);
        if (definition.createInstance) {
            if (value == null) {
                data.writeBoolean(false);
            } else {
                data.writeBoolean(true);
                definition.encode(source, value, data);
            }
        }
        if (value == null) return;
        doWriteBuffer(side, value, data, writeAll);
    }

    @Override
    public final void readFromBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data) {
        T value;
        if (definition.createInstance) {
            if (data.readBoolean()) {
                value = definition.decode(source, data);
            } else {
                value = null;
            }
            definition.set(source, value);
        } else {
            value = getInstance(source);
        }
        if (value != null) doReadBuffer(side, value, data);
        var listener = definition.getListener(side);
        if (listener != null) {
            try {
                listener.invokeExact(source, value, instance);
                instance = value;
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public final @NotNull Data writeToData(@NotNull Object source) {
        var definition = this.definition;
        var value = getInstance(source);
        if (definition.createInstance) {
            if (value == null) {
                return NullData.INSTANCE;
            } else {
                if (definition.skipSave(source, value)) return NullData.NONE;
                return ListData.of(definition.encode(source, value), doWriteData(source, value));
            }
        } else {
            if (value == null) return NullData.NONE;
            if (definition.skipSave(source, value)) return NullData.NONE;
            return doWriteData(source, value);
        }
    }

    @Override
    public final void readFromData(@NotNull Object source, @NotNull Data data, int dataVersion) {
        if (definition.createInstance) {
            if (dataVersion == -1) {
                if (data instanceof StringMapData mapData && !mapData.isEmpty()) {
                    var uid = mapData.get("uid");
                    var value = definition.decode(source, uid, dataVersion);
                    doReadData(value, mapData.get("payload").getStringMap().get("d"), dataVersion);
                    definition.set(source, value);
                }
            } else {
                T value;
                if (data == NullData.INSTANCE) {
                    value = null;
                } else {
                    var list = data.getList();
                    value = definition.decode(source, list.getFirst(), dataVersion);
                    doReadData(value, list.get(1), dataVersion);
                }
                definition.set(source, value);
            }
        } else {
            var value = getInstance(source);
            if (value == null) return;
            doReadData(value, data, dataVersion);
        }
    }

    /**
     * Template method: check if the container's <em>contents</em> have changed.
     * Called by {@link #detectChange} after confirming the instance reference hasn't changed.
     */
    protected abstract boolean hasChange(@NotNull LogicalSide side, @NotNull T instance, boolean autoOnly);

    /**
     * Template method: write the container's <em>contents</em> to the network buffer.
     * Called by {@link #writeToBuffer} after null/instance/createInstance handling.
     */
    protected abstract void doWriteBuffer(@NotNull LogicalSide side, @NotNull T instance, @NotNull FriendlyByteBuf data, boolean writeAll);

    /**
     * Template method: read the container's <em>contents</em> from the network buffer.
     * Called by {@link #readFromBuffer} after null/instance/createInstance handling.
     */
    protected abstract void doReadBuffer(@NotNull LogicalSide side, @NotNull T instance, @NotNull FriendlyByteBuf data);

    /**
     * Template method: serialize the container's <em>contents</em> to a Data object.
     * Called by {@link #writeToData} after null/instance/createInstance/skip handling.
     */
    protected abstract @NotNull Data doWriteData(@NotNull Object source, @NotNull T instance);

    /**
     * Template method: deserialize the container's <em>contents</em> from a Data object.
     * Called by {@link #readFromData} after null/instance/createInstance handling.
     */
    protected abstract void doReadData(@NotNull T instance, @NotNull Data data, int dataVersion);
}
