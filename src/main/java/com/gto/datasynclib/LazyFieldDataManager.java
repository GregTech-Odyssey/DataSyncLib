package com.gto.datasynclib;

/**
 * A thread-safe, lazy-initializing wrapper for {@link FieldDataManager}.
 * <p>
 * This class implements the double-checked locking pattern to ensure that the
 * {@link FieldDataManager} is created only once, even in concurrent environments.
 * The underlying {@link FieldDataManager} is only instantiated when first requested,
 * reducing overhead for cases where it may not be needed.
 */
public final class LazyFieldDataManager {

    private final IFieldDataHolder holder;

    private volatile FieldDataManager fieldDataManager;

    public LazyFieldDataManager(IFieldDataHolder holder) {
        this.holder = holder;
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
                    fieldDataManager = new FieldDataManager(holder);
                }
                manager = fieldDataManager;
            }
        }
        return manager;
    }
}
