package com.gto.datasynclib.field.access.array;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.ListData;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.field.access.AbstractFieldAccess;
import it.unimi.dsi.fastutil.Hash;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Synchronizes a generic object array with element codec support.
 *
 * <h3>Change detection</h3>
 * <p>{@link Arrays#hashCode(Object[])} over the elements by default — one {@code hashCode()} call per
 * element on every check. No snapshot is kept here on purpose, so no element reference is retained;
 * the price is a full scan per cycle plus the usual (theoretical) hash-collision blind spot.</p>
 *
 * <p>That default hashes elements through their own {@code hashCode()}, which is <em>identity</em>
 * for types that do not implement content equality ({@link net.minecraft.world.item.ItemStack},
 * nested arrays, ...). An in-place change inside such an element is then invisible. Register a
 * {@link it.unimi.dsi.fastutil.Hash.Strategy} for the field's exact array type and it is used
 * instead:</p>
 *
 * <pre>{@code
 * FieldDefinitionStorage.registerStrategy(ItemStack[].class, ItemStackArrayHashStrategy.ALL);
 * }</pre>
 *
 * <p>The library already registers array strategies for {@code ItemStack[]} and {@code FluidStack[]};
 * a custom element type needs its own array-type registration, because the lookup uses the field's
 * exact type. The strategy itself can be derived from the element strategy with
 * {@link com.gto.datasynclib.util.HashUtil#arrayStrategy(Hash.Strategy)}:</p>
 *
 * <pre>{@code
 * FieldDefinitionStorage.registerStrategy(MyKey[].class, HashUtil.arrayStrategy(MyKeyHashStrategy.ALL));
 * }</pre>
 *
 * <p>For large arrays prefer {@code @SyncToClient(autoUpdate = false)} plus
 * {@code markFieldsForSync} instead of paying that scan every tick.</p>
 *
 * <p>Null elements are encoded with a boolean prefix.</p>
 */
public final class ArrayAccess<T> extends AbstractFieldAccess<T[]> {

    private final DataSyncCodec<T> elementCodec;
    /** Strategy registered for the array type, or {@code null} to hash elements directly. */
    @Nullable
    private final Hash.Strategy<T[]> strategy;
    private int hashCode;

    public ArrayAccess(DataFieldDefinition<T[]> definition, DataSyncCodec<T> elementCodec) {
        super(definition);
        this.elementCodec = elementCodec;
        // OBJECT_STRATEGY is the "nothing registered" default and would hash the array by identity,
        // which is strictly worse than Arrays.hashCode; only use a real registration.
        var strategy = definition.strategy;
        this.strategy = strategy == DataFieldDefinition.OBJECT_STRATEGY ? null : strategy;
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, T @NotNull [] instance, boolean autoOnly) {
        var hashCode = strategy != null ? strategy.hashCode(instance) : Arrays.hashCode(instance);
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, T @NotNull [] instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        for (var element : instance) {
            if (element == null) {
                data.writeBoolean(false);
            } else {
                data.writeBoolean(true);
                elementCodec.streamWriter.encode(data, element);
            }
        }
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, T @NotNull [] instance, @NotNull FriendlyByteBuf data) {
        var length = instance.length;
        for (int i = 0; i < length; i++) {
            if (data.readBoolean()) {
                instance[i] = elementCodec.streamReader.decode(data);
            } else {
                instance[i] = null;
            }
        }
    }

    @Override
    protected @NotNull Data doWriteData(@NotNull Object source, T @NotNull [] instance) {
        if (definition.hasDefaultValue() && Arrays.equals(instance, definition.getDefaultValue(source)))
            return NullData.NONE;
        var list = new ListData();
        for (T element : instance) {
            if (element != null) {
                list.add(elementCodec.dataWriter.encode(element));
            } else {
                list.addNull();
            }
        }
        if (definition.saveNull) return list;
        for (var data : list) {
            if (data != NullData.INSTANCE) return list;
        }
        return NullData.NONE;
    }

    @Override
    protected void doReadData(T @NotNull [] instance, @NotNull Data data, int dataVersion) {
        var list = data.getList();
        var length = Math.min(list.size(), instance.length);
        for (int i = 0; i < length; i++) {
            var element = list.get(i);
            if (element != NullData.INSTANCE) {
                instance[i] = elementCodec.dataReader.decode(element, dataVersion);
            } else {
                instance[i] = null;
            }
        }
    }
}
