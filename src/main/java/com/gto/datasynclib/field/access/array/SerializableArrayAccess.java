package com.gto.datasynclib.field.access.array;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.ValueOps;
import com.gto.datasynclib.field.access.AbstractFieldAccess;
import com.gto.datasynclib.util.HashUtil;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Synchronizes an array of IDataSerializable instances.
 * Uses identity-based hash with per-element detectChange() propagation.
 * Supports legacy data version migration.
 */

import java.util.ArrayList;

public final class SerializableArrayAccess extends AbstractFieldAccess<IDataSerializable[]> {

    private int hashCode;

    public SerializableArrayAccess(DataFieldDefinition<IDataSerializable[]> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, IDataSerializable @NotNull [] instance, boolean autoDetectOnly) {
        var hashCode = HashUtil.arrayIdentityHashCode(instance);
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            for (var element : instance) {
                if (element != null) element.markAsChanged();
            }
            return true;
        }
        boolean hasChange = false;
        for (var element : instance) {
            if (element != null && element.detectChange()) {
                element.markAsChanged();
                hasChange = true;
            }
        }
        return hasChange;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, IDataSerializable @NotNull [] instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        for (var element : instance) {
            if (element == null || !element.isChanged()) {
                data.writeBoolean(false);
            } else {
                data.writeBoolean(true);
                element.writeBuffer(side, data);
            }
        }
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, IDataSerializable @NotNull [] instance, @NotNull FriendlyByteBuf data) {
        for (var element : instance) {
            if (data.readBoolean()) {
                if (element != null) element.readBuffer(side, data);
            }
        }
    }

    @Override
    protected @NotNull Object doWriteValue(@NotNull Object source, IDataSerializable @NotNull [] instance, @NotNull ValueOps ops) {
        var list = new ArrayList<Object>(instance.length);
        for (var element : instance) {
            list.add(element == null ? ops.createNull() : element.writeValue(ops));
        }
        if (definition.saveEmpty) return ops.createList(list);
        for (var element : list) {
            // the slots hold carrier values, so an absent one is the null value, never a Java null
            if (!ops.isNull(element)) return ops.createList(list);
        }
        return NOT_PERSISTED;
    }

    @Override
    protected void doReadValue(IDataSerializable @NotNull [] instance, @NotNull Object data, @NotNull ValueOps ops) {
        // an empty container was stored as the null value, which is a missing payload, not a failure
        if (!ops.isList(data)) return;
        var list = ops.getList(data);
        var length = Math.min(list.size(), instance.length);
        if (ops.dataVersion() == -1) {
            // legacy layout: each element was a map of "uid"/"p" rather than the value itself
            for (int i = 0; i < length; i++) {
                var element = list.get(i);
                if (ops.isStringMap(element)) {
                    var map = ops.getStringMap(element);
                    if (map.isEmpty()) continue;
                    var serializable = instance[i];
                    if (serializable != null) serializable.readValue(map.get("p"), ops);
                }
            }
        } else {
            for (int i = 0; i < length; i++) {
                var element = list.get(i);
                if (ops.isNull(element)) continue;
                var serializable = instance[i];
                if (serializable != null) serializable.readValue(element, ops);
            }
        }
    }
}
