package com.gto.datasynclib;

/**
 * A thread-safe, lazy-initializing wrapper for {@link FieldDataManager} using the
 * double-checked locking pattern.
 *
 * <p>The underlying {@link FieldDataManager} is only instantiated when first requested
 * via {@link #get()}, avoiding the cost of reflection-based field scanning until the
 * holder is actually used for sync or persistence.</p>
 *
 * <p>An explicit {@code holderClass} can be provided to control which class hierarchy
 * is scanned, independent of the holder's actual runtime class.</p>
 *
 * <h3>Thread-safety:</h3>
 * <p>The {@code get()} method uses double-checked locking with a {@code synchronized}
 * block. For full Java Memory Model correctness, the {@code fieldDataManager} field
 * should be declared {@code volatile} to prevent instruction reordering that could
 * expose a partially-constructed {@code FieldDataManager} to another thread.</p>
 *
 * @see FieldDataManager
 */
public final class LazyFieldDataManager {

    private final IFieldDataHolder holder;
    private final Class<?> holderClass;

    private volatile FieldDataManager fieldDataManager;

    public LazyFieldDataManager(IFieldDataHolder holder) {
        this.holder = holder;
        this.holderClass = holder.getClass();
    }

    public LazyFieldDataManager(IFieldDataHolder holder, Class<?> holderClass) {
        this.holder = holder;
        this.holderClass = holderClass;
    }

    /**
     * Gets the {@link FieldDataManager} instance, creating it if necessary.
     * <p>
     * This method is thread-safe and uses double-checked locking to ensure
     * that only one instance is created, even under concurrent access.
     *
     * @return the field data manager instance for the associated holder
     */
    public FieldDataManager get() {
        var manager = fieldDataManager;
        if (manager == null) {
            synchronized (this) {
                if (fieldDataManager == null) {
                    fieldDataManager = new FieldDataManager(holder, holderClass);
                }
                manager = fieldDataManager;
            }
        }
        return manager;
    }
}
