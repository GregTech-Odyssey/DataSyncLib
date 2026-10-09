package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.ValueOps;
import it.unimi.dsi.fastutil.ints.IntCollection;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Synchronizes a FastUtil {@link it.unimi.dsi.fastutil.ints.IntCollection} (primitive int collection).
 *
 * <h3>Change detection:</h3>
 * <p>Uses {@link Object#hashCode()} comparison — the collection's hash is computed once
 * per sync cycle and compared against the last known hash. This is fast but can produce
 * false positives on hash collisions (extremely rare for int collections).</p>
 *
 * <h3>Network format:</h3>
 * <p>VarInt-prefixed length followed by VarInt values. The collection is cleared and
 * re-populated on the receiving end (in-place mutation).</p>
 *
 * <h3>Persistence format:</h3>
 * <p>Writes as the carrier''s {@code INT_ARRAY} value (a compact VarInt-encoded int array), and the
 * null value for an empty collection.</p>
 */
public final class IntCollectionAccess extends AbstractFieldAccess<IntCollection> {

    private int hashCode;

    public IntCollectionAccess(DataFieldDefinition<IntCollection> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull IntCollection instance, boolean autoDetectOnly) {
        var hashCode = instance.hashCode();
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, @NotNull IntCollection instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        data.writeVarInt(instance.size());
        instance.forEach(data::writeVarInt);
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, @NotNull IntCollection instance, @NotNull FriendlyByteBuf data) {
        var length = data.readVarInt();
        instance.clear();
        for (int i = 0; i < length; i++) {
            instance.add(data.readVarInt());
        }
    }

    @Override
    protected @NotNull Object doWriteValue(@NotNull Object source, @NotNull IntCollection instance, @NotNull ValueOps ops) {
        if (instance.isEmpty()) return ops.createNull();
        return ops.createIntArray(instance.toIntArray());
    }

    @Override
    protected void doReadValue(@NotNull IntCollection instance, @NotNull Object data, @NotNull ValueOps ops) {
        instance.clear();
        if (!ops.isIntArray(data)) return;
        var array = ops.getIntArray(data);
        for (var element : array) {
            instance.add(element);
        }
    }
}
