package com.gto.datasynclib;

import com.gto.datasynclib.datastream.data.StringMapData;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Interface for objects whose annotated fields are managed by a {@link FieldDataManager}.
 *
 * <p>Implementations must provide a {@link FieldDataManager} via {@link #getFieldDataManager()}.
 * The recommended approach is to use {@link com.gto.datasynclib.LazyFieldDataManager} for
 * lazy initialization (see {@link com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity}
 * for the canonical implementation).</p>
 *
 * <h3>Source resolution:</h3>
 * <p>{@link #getSource(DataFieldDefinition)} resolves the actual object that owns a given
 * field. For simple cases where all fields are declared directly on the holder class,
 * this returns {@code this}. For nested holders (via
 * {@link com.gto.datasynclib.annotations.AdditionalHolder @AdditionalHolder}), the
 * {@link DataFieldDefinition#source} function is a composed getter chain that reaches
 * into the nested object.</p>
 *
 * <h3>Customization hooks:</h3>
 * <ul>
 *   <li>{@link #writeCustomSyncData}/{@link #readCustomSyncData} — inject extra data before
 *       field-level network serialization</li>
 *   <li>{@link #writeCustomSaveData}/{@link #readCustomSaveData} — inject extra data before
 *       field-level disk serialization</li>
 *   <li>{@link #scheduleUpdate(LogicalSide)} — called after receiving sync data for fields
 *       with {@code notifyUpdate = true}, for triggering re-renders or validation</li>
 * </ul>
 *
 * @see FieldDataManager
 * @see com.gto.datasynclib.LazyFieldDataManager
 * @see com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity
 */
public interface IFieldDataHolder {

    /**
     * Gets the {@link FieldDataManager} that drives the sync and persistence lifecycle
     * for this holder's annotated fields.
     *
     * @return the field data manager instance (never null after initialization)
     */
    FieldDataManager getFieldDataManager();

    /**
     * Resolves the actual owning object for a given field definition.
     *
     * <p>For simple holders, returns {@code this}. For holders with
     * {@code @AdditionalHolder} nested objects, walks the composed getter chain
     * ({@link DataFieldDefinition#source}) to reach the nested owner.</p>
     *
     * @param definition the field definition to resolve the source for
     * @return the object that owns the field (may be a nested sub-object)
     */
    default Object getSource(DataFieldDefinition<?> definition) {
        var source = definition.source;
        if (source == null) return this;
        return source.apply(this);
    }

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
     * @param buf      the network data buffer
     * @param writeAll whether to force writing all data (ignoring dirty flags)
     */
    default void writeCustomSyncData(FriendlyByteBuf buf, boolean writeAll) {
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
