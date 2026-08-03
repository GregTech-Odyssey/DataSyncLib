package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.IntArrayData;
import com.gto.datasynclib.datastream.data.NullData;
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
 * <p>Writes as {@link com.gto.datasynclib.datastream.data.IntArrayData} (compact
 * VarInt-encoded int array). Returns {@link com.gto.datasynclib.datastream.data.NullData#INSTANCE}
 * for empty collections.</p>
 */
public final class IntCollectionAccess extends AbstractFieldAccess<IntCollection> {

    private int hashCode;

    public IntCollectionAccess(DataFieldDefinition<IntCollection> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull IntCollection instance, boolean autoOnly) {
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
    protected @NotNull Data doWriteData(@NotNull Object source, @NotNull IntCollection instance) {
        if (instance.isEmpty()) return NullData.INSTANCE;
        return new IntArrayData(instance.toIntArray());
    }

    @Override
    protected void doReadData(@NotNull IntCollection instance, @NotNull Data data, int dataVersion) {
        instance.clear();
        var array = data.getIntArray();
        for (var element : array) {
            instance.add(element);
        }
    }
}
