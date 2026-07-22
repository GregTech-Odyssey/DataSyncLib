package com.gto.datasynclib.field.access.array;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.ListData;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.datastream.data.StringMapData;
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
public final class SerializableArrayAccess extends AbstractFieldAccess<IDataSerializable[]> {

    private int hashCode;

    public SerializableArrayAccess(DataFieldDefinition<IDataSerializable[]> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, IDataSerializable @NotNull [] instance, boolean autoOnly) {
        var hashCode = HashUtil.arrayIdentityHashCode(instance);
        if (hashCode != this.hashCode) {
            this.hashCode = hashCode;
            for (var element : instance) {
                element.markAsChanged();
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
    protected @NotNull Data doWriteData(@NotNull Object source, IDataSerializable @NotNull [] instance) {
        var list = new ListData();
        for (var element : instance) {
            if (element != null) {
                list.add(element.writeData());
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
    protected void doReadData(IDataSerializable @NotNull [] instance, @NotNull Data data, int dataVersion) {
        var list = data.getList();
        var length = Math.min(list.size(), instance.length);
        if (dataVersion == -1) {
            for (int i = 0; i < length; i++) {
                if (list.get(i) instanceof StringMapData(Map<String, Data> map) && !map.isEmpty()) {
                    var element = instance[i];
                    if (element != null) element.readData(map.get("p"), dataVersion);
                }
            }
        } else {
            for (int i = 0; i < length; i++) {
                var d = list.get(i);
                if (d != NullData.INSTANCE) {
                    var element = instance[i];
                    if (element != null) element.readData(d, dataVersion);
                }
            }
        }
    }
}
