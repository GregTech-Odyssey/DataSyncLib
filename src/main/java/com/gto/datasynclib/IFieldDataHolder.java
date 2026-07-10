package com.gto.datasynclib;

import com.gto.datasynclib.datasream.data.StringMapData;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Interface for objects that hold field data managed by {@link FieldDataManager}.
 * Provides methods for synchronization, persistence, and custom data handling.
 */
public interface IFieldDataHolder {

    /**
     * Gets the {@link FieldDataManager} associated with this data holder.
     *
     * @return the field data manager instance
     */
    FieldDataManager getFieldDataManager();

    /**
     * Marks the specified fields for synchronization.
     * This is a convenience method that delegates to the underlying {@link FieldDataManager}.
     *
     * @param name the names of the fields to mark for synchronization
     */
    default void markFieldsForSync(String... name) {
        getFieldDataManager().markFieldsForSync(name);
    }

    /**
     * Schedules an update operation.
     * <p>
     * Called when field data has changed and notification is needed.
     * The default implementation is empty; subclasses can override to implement
     * custom update logic.
     *
     * @param side the logical side (client or server), used to distinguish update direction
     */
    default void scheduleUpdate(LogicalSide side) {
    }

    /**
     * Writes custom synchronization data.
     * <p>
     * Writes additional custom data to the network buffer before regular field synchronization.
     * The default implementation is empty; subclasses can override to implement
     * custom data synchronization.
     *
     * @param buf   the network data buffer
     * @param force whether to force writing all data (ignoring dirty flags)
     */
    default void writeCustomSyncData(FriendlyByteBuf buf, boolean force) {
    }

    /**
     * Reads custom synchronization data.
     * <p>
     * Reads additional custom data from the network buffer before regular field synchronization.
     * The default implementation is empty; subclasses can override to implement
     * custom data synchronization.
     *
     * @param buf the network data buffer
     */
    default void readCustomSyncData(FriendlyByteBuf buf) {
    }

    /**
     * Writes custom save data.
     * <p>
     * Writes additional custom data to the MapData object before regular field persistence.
     * The default implementation is empty; subclasses can override to implement
     * custom data persistence.
     *
     * @param data the data map object used for storing persistent data
     */
    default void writeCustomSaveData(StringMapData data) {
    }

    /**
     * Reads custom save data.
     * <p>
     * Reads additional custom data from the MapData object before regular field loading.
     * The default implementation is empty; subclasses can override to implement
     * custom data persistence.
     *
     * @param data        the data map object containing persistent data
     * @param dataVersion the version of the data being read, useful for migration
     */
    default void readCustomSaveData(StringMapData data, int dataVersion) {
    }
}
