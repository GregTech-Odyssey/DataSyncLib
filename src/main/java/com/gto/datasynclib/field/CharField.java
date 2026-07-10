package com.gto.datasynclib.field;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datasream.data.CharData;
import com.gto.datasynclib.datasream.data.Data;
import com.gto.datasynclib.datasream.data.NullData;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public final class CharField extends AbstractField<Character> {

    private char lastValue;

    public CharField(DataFieldDefinition<Character> definition) {
        super(definition);
    }

    @Override
    public boolean hasChanges(@NotNull LogicalSide side, Object source) {
        var definition = this.definition;
        var value = definition.getChar(source);
        if (definition.skipSync(side, source, value)) return false;
        return lastValue != value;
    }

    @Override
    public void writeToBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data, boolean force) {
        var value = definition.getChar(source);
        lastValue = value;
        data.writeChar(value);
    }

    @Override
    public void readFromBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data) {
        var value = data.readChar();
        definition.setChar(source, value);
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
    public @NotNull Data writeToData(@NotNull Object source) {
        var definition = this.definition;
        var value = definition.getChar(source);
        if (definition.skipSave(source, value)) return NullData.NONE;
        if (definition.hasDefaultValue() && definition.getDefaultCharValue(source) == value) return NullData.NONE;
        return CharData.valueOf(value);
    }

    @Override
    public void readFromData(@NotNull Object source, @NotNull Data data, int dataVersion) {
        char value = data.getChar();
        definition.setChar(source, value);
    }
}
