package com.gto.datasynclib.field;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.codec.ValueOps;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * {@link DataField} implementation for {@code int} values.
 *
 * <h3>Change detection:</h3>
 * <p>Compares the current field value against {@link #lastValue}, which is refreshed by
 * {@link #writeToBuffer} and — only when a listener is configured — by
 * {@link #readFromBuffer}; a receive-only holder therefore keeps its earlier snapshot.
 * The value is read via {@link DataFieldDefinition#getInt(Object)} (VarHandle), so this is
 * extremely fast. Sync skip predicates ({@code @SyncToClient(skipWhen = "...")}) are checked
 * before comparison.</p>
 *
 * <h3>Persistence:</h3>
 * <p>Skips writing if the value matches the configured default (avoids storing redundant
 * data) or if the save skip predicate returns {@code true}. Otherwise writes as the carrier's {@code INT} value.</p>
 *
 * <h3>Listener notification:</h3>
 * <p>On the receiving side, if a listener MethodHandle is configured, it is invoked with
 * {@code (source, newValue, lastValue)} after the value has been applied — once per
 * {@link #readFromBuffer} call, whether or not the value actually changed. {@code lastValue}
 * is the previous snapshot and is replaced by the new value only inside that same branch.</p>
 *
 * @see com.gto.datasynclib.field.AbstractField
 */
public final class IntField extends AbstractField<Integer> {

    private int lastValue;

    public IntField(DataFieldDefinition<Integer> definition) {
        super(definition);
    }

    @Override
    public boolean hasChange(@NotNull LogicalSide side, Object source) {
        var definition = this.definition;
        var value = definition.getInt(source);
        if (definition.skipSync(side, source, value)) return false;
        return lastValue != value;
    }

    @Override
    public void writeToBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data, boolean writeAll) {
        var value = definition.getInt(source);
        lastValue = value;
        data.writeVarInt(value);
    }

    @Override
    public void readFromBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data) {
        var value = data.readVarInt();
        definition.setInt(source, value);
        var listener = definition.getListener(side);
        if (listener != null) {
            try {
                listener.invokeExact(source, value, lastValue);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
            lastValue = value;
        }
    }

    @Override
    public @NotNull Object writeToValue(@NotNull Object source, @NotNull ValueOps ops) {
        var definition = this.definition;
        var value = definition.getInt(source);
        if (definition.skipSave(source, value)) return NOT_PERSISTED;
        if (definition.hasDefaultValue() && definition.getDefaultIntValue(source) == value) return NOT_PERSISTED;
        return value;
    }

    @Override
    public void readFromValue(@NotNull Object source, @NotNull Object data, @NotNull ValueOps ops) {
        var value = ops.getInt(data);
        definition.setInt(source, value);
        // @SaveToDisk(listener = "...") — disk-load hook, fired after the value is applied (never on the sync path).
        var listener = definition.getSaveListener();
        if (listener != null) {
            try {
                listener.invokeExact(source, value);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }
}