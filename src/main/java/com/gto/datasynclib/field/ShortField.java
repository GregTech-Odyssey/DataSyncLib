package com.gto.datasynclib.field;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.ValueOps;
import com.gto.datasynclib.LogicalSide;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * DataField implementation for short values. Tracks the previous value for change detection
 * comparison, handles sync skip predicates via {@code skipSync}, default value filtering for
 * persistence, and invokes the configured {@code @SyncToClient/@SyncToServer} listener on every
 * value applied by {@code readFromBuffer} — not only when the value actually changed.
 */
public final class ShortField extends AbstractField<Short> {

    private short lastValue;

    public ShortField(DataFieldDefinition<Short> definition) {
        super(definition);
    }

    @Override
    public boolean hasChange(@NotNull LogicalSide side, Object source) {
        var definition = this.definition;
        var value = definition.getShort(source);
        if (definition.skipSync(side, source, value)) return false;
        return lastValue != value;
    }

    @Override
    public void writeToBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data, boolean writeAll) {
        var value = definition.getShort(source);
        lastValue = value;
        data.writeShort(value);
    }

    @Override
    public void readFromBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data) {
        var value = data.readShort();
        definition.setShort(source, value);
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
        var value = definition.getShort(source);
        if (definition.skipSave(source, value)) return NOT_PERSISTED;
        if (definition.hasDefaultValue() && definition.getDefaultShortValue(source) == value) return NOT_PERSISTED;
        return value;
    }

    @Override
    public void readFromValue(@NotNull Object source, @NotNull Object data, @NotNull ValueOps ops) {
        var value = ops.getShort(data);
        definition.setShort(source, value);
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