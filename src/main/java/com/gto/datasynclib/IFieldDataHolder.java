package com.gto.datasynclib;

import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.ValueOps;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

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
     * <p>Used by {@link #scheduleUpdate(LogicalSide)} to hand an update up to the owner. Remote calls do
     * <strong>not</strong> need it: a call is addressed by the target's index in the flattened holder
     * tree of the object the packet addresses
     * ({@link FieldDataManager#writeRemoteCall(Object, Object, String, Object...)}, which takes that
     * object explicitly), so a holder never has to know where it sits.</p>
     *
     * @return the owning holder, or {@code null} when this holder is a root
     */
    @Nullable
    default IFieldDataHolder getParentHolder() {
        return null;
    }

    /**
     * Encodes a remote call for {@code target} — the convenience form of
     * {@link FieldDataManager#writeRemoteCall(Object, Object, String, Object...)} for the holder the packet
     * will address: {@code this} is the root of the call, and {@code target} is the holder inside it the
     * call is for ({@code this} itself when the call is for the addressed object).
     *
     * @param target the holder the call is for
     * @param method the wire name of the {@code @RemoteCall} method
     * @param args   the arguments, in declaration order
     * @return the serialized call
     */
    default byte[] writeRemoteCall(Object target, String method, Object... args) {
        return FieldDataManager.writeRemoteCall(this, target, method, args);
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
     * <p>The payload names a node of {@code target}'s flattened holder tree
     * ({@link com.gto.datasynclib.remote.RemoteRouting}): index {@code 0} is {@code target} itself,
     * anything else a holder inside it, found by following one cached path — so a method declared on a
     * nested holder is invoked on that nested object, and the object a call was made from never has to
     * know where it sits.</p>
     *
     * <p>Failures (an index this tree has no node for, a node absent on this instance, a method index out
     * of range, an exception thrown by the method) are propagated; a payload whose prefix cannot be read
     * at all is ignored, and a packet handler usually wants to log what does come out instead — see
     * {@link com.gto.datasynclib.remote.RemoteNetwork#handleReceived(Object, byte[])}.</p>
     *
     * @param target  the block entity or entity the packet addressed; {@code null} is ignored
     * @param payload the call payload from
     *                {@link FieldDataManager#writeRemoteCall(Object, Object, String, Object...)}; empty
     *                input is ignored
     */
    static void handleRemoteCall(Object target, byte[] payload) {
        FieldDataManager.dispatchRemoteCall(target, payload);
    }

    /**
     * The side-aware form of {@link #handleRemoteCall(Object, byte[])}: the caller states the logical side
     * the call arrived on, so {@link com.gto.datasynclib.annotations.RemoteCall#side()} is enforced even
     * for a target that is not in a level (where the two-argument form has no side to check against).
     *
     * @param target  the block entity or entity the packet addressed; {@code null} is ignored
     * @param payload the call payload; empty input is ignored
     * @param side    the side the call arrived on, or {@code null} when it is not known
     */
    static void handleRemoteCall(Object target, byte[] payload, @Nullable LogicalSide side) {
        FieldDataManager.dispatchRemoteCall(target, payload, side);
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
     * Writes custom save data into the entry map the manager is building, before the regular fields
     * are persisted.
     *
     * <p>The map holds carrier values, so a value goes in as-is ({@code ops.createInt(3)},
     * {@code ops.createString("x")}, a nested map, …) and is written by the manager with the same
     * encoding as a field — {@code JavaValueOps#toBytes(Object)} on the whole entry then yields the
     * save file. The default implementation is empty; override it to persist state the annotated
     * fields do not cover.</p>
     *
     * @param data the entry map being written, keyed the way the fields are
     * @param ops  the carrier the values are built with
     */
    default void writeCustomSaveData(Map<String, Object> data, @NotNull ValueOps ops) {
    }

    /**
     * Reads custom save data back from the entry map the manager just read.
     *
     * @param data the entry map that was read, keyed the way the fields are
     * @param ops  the carrier the values came from; {@link com.gto.datasynclib.datastream.codec.ValueOps#dataVersion()}
     *             is the version of the data being read, useful for migration
     */
    default void readCustomSaveData(Map<String, Object> data, @NotNull ValueOps ops) {
    }
}
