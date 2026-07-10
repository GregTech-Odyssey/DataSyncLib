package com.gto.datasynclib.field.access;

import com.gto.datasynclib.DataField;
import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datasream.data.Data;
import com.gto.datasynclib.datasream.data.ListData;
import com.gto.datasynclib.datasream.data.NullData;
import com.gto.datasynclib.datasream.data.StringMapData;
import lombok.Getter;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class AbstractFieldAccess<T> implements DataField<T> {

    @Getter
    protected final DataFieldDefinition<T> definition;

    protected T instance;

    protected boolean syncChange;

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
        syncChange = true;
    }

    @Override
    public void clearChanged(@NotNull Object source) {
        syncChange = false;
    }

    @Override
    public boolean isChanged(@NotNull Object source) {
        return syncChange;
    }

    @Override
    public boolean detectChange(@NotNull LogicalSide side, @NotNull Object source, boolean auto) {
        var instance = getInstance(source);
        if (definition.skipSync(side, source, instance)) return false;
        if (this.instance != instance) {
            this.instance = instance;
            markAsChanged(source);
            if (instance != null && mustDetected()) hasChange(side, instance, auto);
            return true;
        }
        if (instance == null) return syncChange;
        if (hasChange(side, instance, auto)) {
            markAsChanged(source);
            return true;
        }
        return syncChange;
    }

    @Override
    public final void writeToBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data, boolean force) {
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
        writeBuffer(side, value, data, force);
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
        if (value != null) readBuffer(side, value, data);
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
                var list = new ListData(2);
                list.add(definition.encode(source, value));
                list.add(writeData(source, value));
                return list;
            }
        } else {
            if (value == null) return NullData.NONE;
            if (definition.skipSave(source, value)) return NullData.NONE;
            return writeData(source, value);
        }
    }

    @Override
    public final void readFromData(@NotNull Object source, @NotNull Data data, int dataVersion) {
        if (definition.createInstance) {
            if (dataVersion == -1) {
                if (data instanceof StringMapData mapData && !mapData.isEmpty()) {
                    var uid = mapData.get("uid");
                    var value = definition.decode(source, uid, dataVersion);
                    readData(value, mapData.get("payload").getStringMap().get("d"), dataVersion);
                    definition.set(source, value);
                }
            } else {
                T value;
                if (data == NullData.INSTANCE) {
                    value = null;
                } else {
                    var list = data.getList();
                    value = definition.decode(source, list.getFirst(), dataVersion);
                    readData(value, list.get(1), dataVersion);
                }
                definition.set(source, value);
            }
        } else {
            var value = getInstance(source);
            if (value == null) return;
            readData(value, data, dataVersion);
        }
    }

    protected abstract boolean hasChange(@NotNull LogicalSide side, @NotNull T instance, boolean auto);

    protected abstract void writeBuffer(@NotNull LogicalSide side, @NotNull T instance, @NotNull FriendlyByteBuf data, boolean force);

    protected abstract void readBuffer(@NotNull LogicalSide side, @NotNull T instance, @NotNull FriendlyByteBuf data);

    protected abstract @NotNull Data writeData(@NotNull Object source, @NotNull T instance);

    protected abstract void readData(@NotNull T instance, @NotNull Data data, int dataVersion);
}
