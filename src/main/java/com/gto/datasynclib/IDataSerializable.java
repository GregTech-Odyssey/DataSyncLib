package com.gto.datasynclib;

import com.gto.datasynclib.datasream.data.Data;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Interface for objects that can be serialized and deserialized for data synchronization.
 * <p>
 * Provides methods for detecting and tracking changes, as well as writing to and reading from
 * various data formats including network buffers ({@link FriendlyByteBuf}) and persistent
 * storage ({@link Data}). Implementing classes can control their own serialization behavior
 * for both network synchronization and disk persistence.
 */
public interface IDataSerializable {

    /**
     * Detects whether the data has changed by comparing current values with previous snapshots.
     * This method should implement the comparison logic specific to the data structure.
     *
     * @return true if changes are detected, false otherwise
     */
    boolean detectChange();

    /**
     * Marks this data as changed, forcing it to be included in the next synchronization.
     */
    void markAsChanged();

    /**
     * Clears the changed flag, indicating that the data has been synchronized.
     */
    void clearChanged();

    /**
     * Checks whether this data is marked as changed.
     *
     * @return true if the data has been marked as changed, false otherwise
     */
    boolean isChanged();

    /**
     * Writes the data to a network buffer for synchronization.
     *
     * @param side the logical side (client or server) performing the write
     * @param buf  the network buffer to write to, must not be null
     */
    void writeBuf(LogicalSide side, @NotNull FriendlyByteBuf buf);

    /**
     * Reads the data from a network buffer during synchronization.
     *
     * @param side the logical side (client or server) performing the read
     * @param buf  the network buffer to read from, must not be null
     */
    void readBuf(LogicalSide side, @NotNull FriendlyByteBuf buf);

    /**
     * Writes the data to a {@link Data} object for persistent storage.
     *
     * @return the Data object containing the serialized data
     */
    Data writeData();

    /**
     * Reads the data from a {@link Data} object during loading.
     *
     * @param data        the Data object containing the serialized data, must not be null
     * @param dataVersion the version of the data format, useful for handling migrations
     */
    void readData(@NotNull Data data, int dataVersion);
}
