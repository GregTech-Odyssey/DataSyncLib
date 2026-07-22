package com.gto.datasynclib.field;

import com.gto.datasynclib.DataField;
import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base class for <strong>value-type</strong> field implementations (primitives
 * and objects).
 *
 * <p>Uses the <strong>template method</strong> pattern: the public {@link #detectChange}
 * method handles the common logic (checking skip conditions, evaluating the result),
 * while subclasses implement {@link #hasChange(LogicalSide, Object)} for type-specific
 * value comparison.</p>
 *
 * <p>Provides common change tracking ({@code markAsChanged/clearChanged/isChanged})
 * backed by a simple boolean flag. Subclasses track a snapshot of the previous value
 * ({@code lastValue}) for comparison against the current value.</p>
 *
 * <h3>Subclass families:</h3>
 * <ul>
 *   <li>{@link com.gto.datasynclib.field.IntField IntField} through
 *       {@link com.gto.datasynclib.field.DoubleField DoubleField} — primitive types</li>
 *   <li>{@link com.gto.datasynclib.field.object.ObjField ObjField} — object types with
 *       two-tier change detection (hashCode quick-check, then strategy.equals)</li>
 * </ul>
 *
 * @param <T> the field's declared type
 * @see com.gto.datasynclib.field.access.AbstractFieldAccess
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
    public final boolean detectChange(@NotNull LogicalSide side, @NotNull Object source, boolean autoOnly) {
        return changed = hasChange(side, source);
    }

    protected abstract boolean hasChange(@NotNull LogicalSide side, Object source);
}
