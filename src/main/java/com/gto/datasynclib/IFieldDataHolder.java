package com.gto.datasynclib;

import com.gto.datasynclib.datastream.data.StringMapData;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

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
 *       with {@code scheduleUpdate = true}, for triggering re-renders or validation</li>
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
     * The holder this one is nested in — {@code null} for a holder that is its own root.
     *
     * <p>This is what makes a holder addressable inside its owner: a remote call carries the field path
     * from the root down to the calling holder, and that path is derived from this link
     * ({@link FieldDataManager#remotePath()}). A nested holder knows its owner, so it answers it here —
     * typically with the reference it was constructed with:</p>
     *
     * <pre>{@code
     * final class EnergyHandler implements IFieldDataHolder {
     *     private final MyBlockEntity owner;
     *
     *     EnergyHandler(MyBlockEntity owner) { this.owner = owner; }
     *
     *     @Override public IFieldDataHolder getParentHolder() { return owner; }
     * }
     *
     * // in the owner: this.energy = new EnergyHandler(this);
     * }</pre>
     *
     * <p>Nothing else is required of that field. It is an ordinary holder-typed field, so it takes part
     * in the owner's holder-field numbering like any other — harmless, because the sender and the
     * receiver number the same class the same way. Mark it {@code transient} only if you would rather
     * keep the owner link out of that numbering (see
     * {@link com.gto.datasynclib.remote.RemoteRouting}).</p>
     *
     * @return the owning holder, or {@code null} when this holder is a root
     */
    @Nullable
    default IFieldDataHolder getParentHolder() {
        return null;
    }

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
     * Applies a received remote call to the object a packet resolved — the dispatch rule the remote
     * packet layer uses, exposed so a packet of your own can share it.
     *
     * <p>The call is routed by the holder-field path the payload carries: the receiving side reads the
     * addressed object's holder fields index by index (see
     * {@link com.gto.datasynclib.remote.RemoteRouting}) until the path is exhausted, so a method declared
     * on an {@code @AdditionalHolder} sub-object (or any field whose type implements
     * {@link IFieldDataHolder}) is invoked on that nested object — which is what lets a call encoded by a
     * nested holder's manager be received here. A holder target runs the call through
     * {@link FieldDataManager#readRemoteCall(byte[])}, any other block entity or entity through
     * {@link FieldDataManager#dispatchRemoteCall(Object, byte[])}; both share one buffer and one
     * decode of the payload.</p>
     *
     * <p>Failures (a path this tree has no field for, an absent holder, a method index out of range, an
     * exception thrown by the method) are propagated; a payload whose prefix cannot be read at all is
     * ignored, and a packet handler usually wants to log what does come out instead — see
     * {@link com.gto.datasynclib.remote.RemoteNetwork#handleReceived(Object, byte[])}.</p>
     *
     * @param target  the block entity or entity the packet addressed; {@code null} is ignored
     * @param payload the call payload from
     *                {@link FieldDataManager#writeRemoteCall(String, Object...)}; empty input is
     *                ignored
     */
    static void handleRemoteCall(Object target, byte[] payload) {
        if (target instanceof IFieldDataHolder holder) {
            holder.getFieldDataManager().readRemoteCall(payload);
        } else {
            FieldDataManager.dispatchRemoteCall(target, payload);
        }
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
        var parent = getParentHolder();
        if (parent != null) parent.scheduleUpdate(side);
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
