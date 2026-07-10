package com.gto.datasynclib.field;

import com.gto.datasynclib.DataField;
import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

public abstract class AbstractField<T> implements DataField<T> {

    @Getter
    protected final DataFieldDefinition<T> definition;

    protected boolean syncChange;

    protected AbstractField(DataFieldDefinition<T> definition) {
        this.definition = definition;
    }

    @Override
    public final void markAsChanged(@NotNull Object source) {
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
    public final boolean detectChange(@NotNull LogicalSide side, @NotNull Object source, boolean auto) {
        return syncChange = hasChanges(side, source);
    }

    protected abstract boolean hasChanges(@NotNull LogicalSide side, Object source);
}
