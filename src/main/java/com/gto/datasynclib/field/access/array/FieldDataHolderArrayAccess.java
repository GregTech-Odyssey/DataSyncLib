package com.gto.datasynclib.field.access.array;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.ValueOps;
import com.gto.datasynclib.field.access.AbstractFieldAccess;
import com.gto.datasynclib.util.HashUtil;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Synchronizes an array of IFieldDataHolder instances.
 * Uses identity-based hash for array change detection with per-element dirty flag propagation.
 * Overrides mustDetect() for mandatory detection.
 */

import java.util.ArrayList;

public final class FieldDataHolderArrayAccess extends AbstractFieldAccess<IFieldDataHolder[]> {

    private int hashCode;

    public FieldDataHolderArrayAccess(DataFieldDefinition<IFieldDataHolder[]> definition) {
        super(definition);
    }

    @Override
    public boolean mustDetect() {
        return true;
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, IFieldDataHolder @NotNull [] instance, boolean autoDetectOnly) {
        var hashCode = HashUtil.arrayIdentityHashCode(instance);
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            for (var element : instance) {
                if (element != null) {
                    element.getFieldDataManager().updateFieldDirtyFlags(side, autoDetectOnly);
                    element.getFieldDataManager().markAsChanged();
                }
            }
            return true;
        }
        boolean hasChange = false;
        for (var element : instance) {
            if (element != null && element.getFieldDataManager().updateFieldDirtyFlags(side, autoDetectOnly)) {
                element.getFieldDataManager().markAsChanged();
                hasChange = true;
            }
        }
        return hasChange;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, IFieldDataHolder @NotNull [] instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        for (var element : instance) {
            if (element == null || !element.getFieldDataManager().isChanged()) {
                data.writeBoolean(false);
            } else {
                data.writeBoolean(true);
                data.writeByteArray(element.getFieldDataManager().writeToNetworkBuffer(side, writeAll));
            }
        }
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, IFieldDataHolder @NotNull [] instance, @NotNull FriendlyByteBuf data) {
        for (var element : instance) {
            if (data.readBoolean()) {
                if (element != null) element.getFieldDataManager().readFromNetworkBuffer(side, data.readByteArray());
            }
        }
    }

    @Override
    protected @NotNull Object doWriteValue(@NotNull Object source, IFieldDataHolder @NotNull [] instance, @NotNull ValueOps ops) {
        var list = new ArrayList<Object>(instance.length);
        for (var element : instance) {
            list.add(element == null ? ops.createNull() : element.getFieldDataManager().writeToValue(ops));
        }
        if (definition.saveEmpty) return ops.createList(list);
        for (var element : list) {
            // the slots hold carrier values, so an absent one is the null value, never a Java null
            if (!ops.isNull(element)) return ops.createList(list);
        }
        return NOT_PERSISTED;
    }

    @Override
    protected void doReadValue(IFieldDataHolder @NotNull [] instance, @NotNull Object data, @NotNull ValueOps ops) {
        // an empty container was stored as the null value, which is a missing payload, not a failure
        if (!ops.isList(data)) return;
        var list = ops.getList(data);
        var length = Math.min(list.size(), instance.length);
        for (int i = 0; i < length; i++) {
            var element = list.get(i);
            if (ops.isNull(element)) continue;
            var holder = instance[i];
            if (holder != null) holder.getFieldDataManager().readFromValue(element, ops);
        }
    }
}
