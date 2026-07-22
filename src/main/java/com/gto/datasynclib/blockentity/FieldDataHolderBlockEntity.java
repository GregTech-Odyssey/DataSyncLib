package com.gto.datasynclib.blockentity;

import com.gto.datasynclib.FieldDataManager;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LazyFieldDataManager;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
/**
 * Ready-to-use {@link net.minecraft.world.level.block.entity.BlockEntity} base class
 * that implements {@link com.gto.datasynclib.IFieldDataHolder} for automatic field
 * synchronization and persistence.
 *
 * <h3>How to use:</h3>
 * <pre>{@code
 * public class MyBlockEntity extends FieldDataHolderBlockEntity {
 *     @SyncToClient @SaveToDisk
 *     private int energy;
 *
 *     public MyBlockEntity(BlockPos pos, BlockState state) {
 *         super(ModBlockEntities.MY_TYPE.get(), pos, state);
 *     }
 * }
 * }</pre>
 *
 * <h3>What you get automatically:</h3>
 * <ul>
 *   <li><strong>Chunk-load sync:</strong> {@link #getUpdateTag()} includes all
 *       {@code @SyncToClient} fields as {@code "field_sync"} NBT data</li>
 *   <li><strong>Disk persistence:</strong> {@link #saveAdditional} writes all
 *       {@code @SaveToDisk} fields as {@code "field_save"} NBT data with versioning</li>
 *   <li><strong>Disk loading:</strong> {@link #load} reads {@code "field_sync"} first
 *       (chunk sync takes precedence), falls back to {@code "field_save"} for full restore</li>
 * </ul>
 *
 * <h3>Manual steps needed:</h3>
 * <ul>
 *   <li>Call {@link com.gto.datasynclib.network.DataSyncNetwork#syncBlockEntityToClient
 *       DataSyncNetwork.syncBlockEntityToClient()} in your tick method to push updates</li>
 *   <li>Call {@link net.minecraft.world.level.block.entity.BlockEntity#setChanged()
 *       setChanged()} to mark the chunk for saving when fields change</li>
 * </ul>
 *
 * <h3>Static VERSION field:</h3>
 * <p>The {@link #VERSION} field controls the data version written to NBT. Increment
 * this when making breaking changes to your field layout, and use the {@code dataVersion}
 * parameter in {@link com.gto.datasynclib.IDataSerializable#readData} for migration.</p>
 *
 * @see com.gto.datasynclib.IFieldDataHolder
 * @see com.gto.datasynclib.FieldDataManager
 */

public class FieldDataHolderBlockEntity extends BlockEntity implements IFieldDataHolder {

    public static int VERSION;

    protected final LazyFieldDataManager fieldDataManager = new LazyFieldDataManager(this);

    public FieldDataHolderBlockEntity(BlockEntityType<?> type, BlockPos worldPosition, BlockState blockState) {
        super(type, worldPosition, blockState);
    }


    @Override
    public FieldDataManager getFieldDataManager() {
        return fieldDataManager.get();
    }

    /**
     * Overrides the vanilla chunk-sync tag to include all {@code @SyncToClient} fields.
     *
     * <p>When a chunk is sent to a player, Minecraft calls this method to get the initial
     * BlockEntity data. By including serialized sync fields under the {@code "field_sync"}
     * key, newly-arriving players receive the current state without needing a separate
     * sync packet.</p>
     *
     * <p>The data is encoded via {@link FieldDataManager#writeToNetworkBuffer(LogicalSide, boolean)}
     * with {@code writeAll = true} (full sync).</p>
     *
     * @return the compound tag containing vanilla data plus {@code "field_sync"} byte array
     */
    @Override
    public @NotNull CompoundTag getUpdateTag() {
        var tag = super.getUpdateTag();
        if (getFieldDataManager().hasSyncFields(LogicalSide.SERVER)) {
            tag.putByteArray("field_sync", getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, true));
        }
        return tag;
    }

    /**
     * Loads BlockEntity data from NBT.
     *
     * <p>Priority order:
     * <ol>
     *   <li>If {@code "field_sync"} byte array is present, the data is treated as a
     *       chunk-load sync (via {@link FieldDataManager#readFromNetworkBuffer})</li>
     *   <li>Otherwise, if {@code "field_save"} byte array is present, the data is
     *       treated as a full disk load (via {@link FieldDataManager#readFromData}
     *       with the version from {@code "field_data_dataVersion"})</li>
     * </ol>
     *
     * @param tag the NBT compound tag to load from
     */
    @Override
    public void load(@NotNull CompoundTag tag) {
        super.load(tag);
        if (tag.get("field_sync") instanceof ByteArrayTag byteArrayTag) {
            getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, byteArrayTag.getAsByteArray());
        } else {
            if (tag.get("field_save") instanceof ByteArrayTag byteArrayTag) {
                getFieldDataManager().readFromData(Data.readData(byteArrayTag.getAsByteArray()), tag.getInt("field_data_dataVersion"));
            }
        }
    }

    /**
     * Saves BlockEntity data to NBT for disk persistence.
     *
     * <p>Writes the current {@link #VERSION} under {@code "field_data_dataVersion"}
     * and all {@code @SaveToDisk} fields as a byte array under {@code "field_save"}
     * (via {@link FieldDataManager#writeToData()}).</p>
     *
     * <p>Only fields annotated with {@code @SaveToDisk} are included. Use
     * {@link FieldDataManager#writeAllToData()} if you need all managed fields.</p>
     *
     * @param tag the NBT compound tag to save into
     */
    @Override
    protected void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("field_data_dataVersion", VERSION);
        tag.putByteArray("field_save", getFieldDataManager().writeToData().writeToBytes());
    }


}