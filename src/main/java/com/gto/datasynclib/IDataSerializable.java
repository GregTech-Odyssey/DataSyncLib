package com.gto.datasynclib;

import com.gto.datasynclib.datastream.data.Data;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Interface for objects that self-manage their serialization for both network
 * synchronization and disk persistence.
 *
 * <p>Unlike {@link com.gto.datasynclib.IFieldDataHolder} where individual fields are
 * managed by the framework, {@code IDataSerializable} gives full control to the
 * implementing class. The object is responsible for its own change detection,
 * network encoding/decoding, and persistent storage encoding/decoding.</p>
 *
 * <h3>Two independent I/O paths:</h3>
 * <ul>
 *   <li><strong>Network:</strong> {@link #writeBuffer}/{@link #readBuffer} — uses
 *       {@link FriendlyByteBuf} for live client-server sync</li>
 *   <li><strong>Persistence:</strong> {@link #writeData}/{@link #readData} — uses
 *       {@link Data} objects for disk save/load</li>
 * </ul>
 *
 * <h3>Change tracking:</h3>
 * <p>The {@link #detectChange()}/{@link #markAsChanged()}/{@link #clearChanged()}/
 * {@link #isChanged()} quartet supports both automatic detection (comparing snapshots)
 * and manual flag management. Implementations that always need to sync can simply
 * return {@code true} from {@code detectChange()}.</p>
 *
 * <h3>Data version migration:</h3>
 * <p>The {@code dataVersion} parameter in {@link #readData(Data, int)} enables
 * backward-compatible data migration — check the version and adapt the deserialization
 * logic for older formats.</p>
 *
 * @see com.gto.datasynclib.AbstractDataSerializable
 * @see com.gto.datasynclib.listener.ObjSerializableHolder
 */
public interface IDataSerializable {

    /**
     * Detects whether the data has changed since the last sync by comparing current
     * values with previous snapshots or stored state.
     *
     * @return {@code true} if changes are detected and the object should be included
     * in the next synchronization
     */
    boolean detectChange();

    /**
     * Marks this object as changed, forcing it to be included in the next
     * synchronization regardless of automatic change detection.
     */
    void markAsChanged();

    /**
     * Clears the changed flag after a successful synchronization.
     * Called by the framework after the object's data has been transmitted.
     */
    void clearChanged();

    /**
     * Checks whether this object is currently marked as changed.
     *
     * @return {@code true} if the object has been marked as changed
     */
    boolean isChanged();

    /**
     * Writes the object's data to a network buffer for live synchronization.
     * The implementation should write exactly the data needed by {@link #readBuffer}
     * to reconstruct the state.
     *
     * @param side the logical side (client or server) performing the write
     * @param buf  the network buffer to write to, must not be null
     */
    void writeBuffer(LogicalSide side, @NotNull FriendlyByteBuf buf);

    /**
     * Reads the object's data from a network buffer during live synchronization.
     * The implementation should read exactly the data written by {@link #writeBuffer}.
     *
     * @param side the logical side (client or server) performing the read
     * @param buf  the network buffer to read from, must not be null
     */
    void readBuffer(LogicalSide side, @NotNull FriendlyByteBuf buf);

    /**
     * Serializes the object's data to a {@link Data} tree for persistent storage.
     *
     * @return the Data object containing the serialized state (never null)
     */
    Data writeData();

    /**
     * Deserializes the object's data from a {@link Data} tree during loading.
     *
     * @param data        the Data object containing the previously serialized state
     * @param dataVersion the version of the data format, for handling migrations
     */
    void readData(@NotNull Data data, int dataVersion);
}
