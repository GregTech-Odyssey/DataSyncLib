package com.gto.datasynclib;

/**
 * Convenience base class for {@link IDataSerializable} implementations providing default change tracking.
 *
 * <p>Stores a simple {@code changed} boolean flag. The default {@link #detectChange()} implementation
 * simply returns this flag. Subclasses that need real change detection (e.g., comparing current values
 * against snapshots) should override {@code detectChange()} with domain-specific logic.
 *
 * <p>This class is suitable for simple data holders where change state is managed externally
 * (e.g., via {@link #markAsChanged()} and {@link #clearChanged()} calls).
 *
 * @see IDataSerializable
 */
public abstract class AbstractDataSerializable implements IDataSerializable {

    protected boolean changed;

    @Override
    public void markAsChanged() {
        changed = true;
    }

    @Override
    public void clearChanged() {
        changed = false;
    }

    @Override
    public boolean isChanged() {
        return changed;
    }

    @Override
    public boolean detectChange() {
        return changed;
    }
}
