package com.gto.datasynclib.field;

import com.gto.datasynclib.DataField;
import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base class for primitive value field implementations. Provides common change tracking
 * (markAsChanged/clearChanged/isChanged) backed by a boolean flag. Subclasses implement
 * {@link #hasChange(LogicalSide, Object)} to provide type-specific value comparison.
 */
public abstract class AbstractField<T> implements DataField<T> {

    @Getter
    protected final DataFieldDefinition<T> definition;

    protected boolean changed;

    protected AbstractField(DataFieldDefinition<T> definition) {
        this.definition = definition;
    }

    @Override
    public final void markAsChanged(@NotNull Object source) {
        changed = true;
    }

    @Override
    public void clearChanged(@NotNull Object source) {
        changed = false;
    }

    @Override
    public boolean isChanged(@NotNull Object source) {
        return changed;
    }

    @Override
    public final boolean detectChange(@NotNull LogicalSide side, @NotNull Object source, boolean auto) {
        return changed = hasChange(side, source);
    }

    protected abstract boolean hasChange(@NotNull LogicalSide side, Object source);
}
