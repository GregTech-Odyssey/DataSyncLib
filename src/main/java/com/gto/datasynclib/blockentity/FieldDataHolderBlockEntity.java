package com.gto.datasynclib.blockentity;

import com.gto.datasynclib.*;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.ValueOps;
import com.gto.datasynclib.forge.DataSyncEvent;
import com.gto.datasynclib.network.DataSyncNetwork;
import com.gto.datasynclib.util.TaskSubscription;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

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
 *   <li><strong>Item transfer (opt-in):</strong> fields marked
 *       {@link SaveToItem @SaveToItem} travel with the dropped
 *       item and come back when it is placed — see {@link #saveToItem(CompoundTag, boolean)},
 *       {@link #loadFromItem(CompoundTag)} and {@link IDropSave}</li>
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
 * <h3>Data version ({@link #VERSION}):</h3>
 * <p>Whatever {@link #VERSION} holds when the chunk is saved is written to the NBT key
 * {@code "field_data_dataVersion"} and handed back to
 * {@link FieldDataManager#readFromValue(Object, ValueOps)} on
 * load, where it can drive migration. Raise it when the meaning or layout of persisted
 * fields changes.</p>
 *
 * <p><strong>Note:</strong> it is a single {@code static} on this base class — shared by
 * every holder and {@code 0} by default. Declaring another {@code VERSION} field in a
 * subclass does <em>not</em> affect what is written, because {@link #saveAdditional} binds
 * the reference at compile time to this class's field. Assign
 * {@code FieldDataHolderBlockEntity.VERSION = N} once during mod init, or override
 * {@link #saveAdditional} / {@link #load} to version per holder type.</p>
 *
 * @see com.gto.datasynclib.IFieldDataHolder
 * @see com.gto.datasynclib.FieldDataManager
 */

public class FieldDataHolderBlockEntity extends BlockEntity implements IFieldDataHolder, IDropSave {

    /**
     * Data-format version written to {@code "field_data_dataVersion"}. Shared by all holders
     * and {@code 0} by default — see the class documentation before bumping it.
     */
    public static int VERSION;

    public volatile boolean remove = false;

    /**
     * The holder's background sync registration, or {@code null} when it is not loaded. Kept so a
     * re-registration reuses the live entry instead of stacking a second one.
     */
    private TaskSubscription syncTask;

    protected final LazyFieldDataManager fieldDataManager = new LazyFieldDataManager(this);

    public FieldDataHolderBlockEntity(BlockEntityType<?> type, BlockPos worldPosition, BlockState blockState) {
        super(type, worldPosition, blockState);
    }


    @Override
    public FieldDataManager getFieldDataManager() {
        return fieldDataManager.get();
    }


    @Override
    public void setRemoved() {
        this.remove = true;
        super.setRemoved();
        unsubscribeSyncTask();
    }

    /**
     * Vanilla/Forge unload a chunk without {@link #setRemoved()} — it calls this instead, so the
     * background task is dropped here too and cannot keep a dead holder alive until the session ends.
     */
    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        unsubscribeSyncTask();
    }

    /**
     * Registers this holder with the executor of its side, so {@link #autoSync()} runs in the
     * background while the holder is loaded.
     *
     * <p>Vanilla calls this when the holder enters a chunk's block-entity map. The registration is
     * a single linked entry and the handle is kept: passing a still-live handle back means the
     * executor returns it unchanged, so a holder that is loaded twice never gets two sync loops.</p>
     */
    @Override
    public void clearRemoved() {
        this.remove = false;
        super.clearRemoved();
        if (level == null) return;
        periodID = level.random.nextInt(40);
        var executor = level instanceof ServerLevel
                ? DataSyncEvent.SERVER_ASYNC_TICK_EXECUTOR
                : level.isClientSide() ? DataSyncEvent.CLIENT_ASYNC_TICK_EXECUTOR : null;
        if (executor != null) {
            syncTask = executor.enqueueTick(syncTask, () -> remove, this::autoSync, getMinSyncCycle(), 0);
        }
    }

    private void unsubscribeSyncTask() {
        var task = syncTask;
        syncTask = null;
        if (task != null) task.unsubscribe();
    }

    protected int periodID;

    protected void autoSync() {
        if (remove || level == null) return;
        periodID++;
        var maxCycle = getMaxSyncCycle();
        if (needSync() || (maxCycle > 0 && periodID % maxCycle == 0)) {
            if (level.isClientSide()) {
                DataSyncNetwork.syncBlockEntityToServer(this);
            } else {
                DataSyncNetwork.syncBlockEntityToClient(this);
            }
        }
    }

    protected int getMinSyncCycle() {
        return 1;
    }

    protected int getMaxSyncCycle() {
        return 40;
    }

    public boolean needSync() {
        return true;
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
     * <p>Priority order:</p>
     * <ol>
     *   <li>If {@code "field_sync"} byte array is present, the data is treated as a
     *       chunk-load sync (via {@link FieldDataManager#readFromNetworkBuffer} with
     *       {@link LogicalSide#CLIENT}) and {@code "field_save"} is ignored entirely —
     *       including {@code "field_data_dataVersion"}</li>
     *   <li>Otherwise, if {@code "field_save"} byte array is present, the data is
     *       treated as a full disk load (via {@link FieldDataManager#readAllFromValue(Object, ValueOps)}
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
                getFieldDataManager().readFromValue(JavaValueOps.INSTANCE.fromBytes(byteArrayTag.getAsByteArray()),
                        JavaValueOps.create(tag.getInt("field_data_dataVersion")));
            }
        }
    }

    /**
     * Saves BlockEntity data to NBT for disk persistence.
     *
     * <p>Writes the current {@link #VERSION} under {@code "field_data_dataVersion"}
     * and all {@code @SaveToDisk} fields as a byte array under {@code "field_save"}
     * (via {@link FieldDataManager#writeToValue(ValueOps)}).</p>
     *
     * <p>Only fields annotated with {@code @SaveToDisk} are included. Use
     * {@link FieldDataManager#writeAllToValue(ValueOps)} if you need all managed fields.</p>
     *
     * @param tag the NBT compound tag to save into
     */
    @Override
    protected void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("field_data_dataVersion", VERSION);
        // the writer carries the version it is writing, the same one the tag records and the reader hands
        // back, so a codec that shapes its output by version writes what it stamps
        var ops = JavaValueOps.create(VERSION);
        tag.putByteArray("field_save", ops.toBytes(getFieldDataManager().writeToValue(ops)));
    }

    /**
     * @return {@code true} if this holder marks at least one field with
     * {@link SaveToItem @SaveToItem}
     */
    public boolean hasItemFields() {
        return FieldDefinitionStorage.get(getClass()).getAnnotatedFields(SaveToItem.class).length > 0;
    }

    /**
     * Writes this holder's {@code @SaveToItem} fields into {@code tag}, the root tag of its item.
     *
     * <p>The payload is what {@link FieldDataManager#writeFieldsToValue} produces for the marked
     * fields — the same carrier format a save file holds — stored under {@link #ITEM_DATA_KEY}
     * with the {@link #VERSION} it was written with under {@link #ITEM_DATA_VERSION_KEY}.</p>
     *
     * <p>Two per-field attributes narrow the payload here:</p>
     * <ul>
     *   <li>{@code @SaveToItem(saveOnClone = false)} — left out when {@code clone} is {@code true}
     *       (creative pick-block); breaking the block still carries the field.</li>
     *   <li>{@code @SaveToItem(skipWhen = "...")} — evaluated with the field's current value on
     *       every write, so a value that is not worth carrying stays out.</li>
     * </ul>
     * <p>Both are read from the parsed {@link SaveToItemMetadata} the annotation's metadata factory
     * stored in {@link FieldDefinitionStorage.AnnotatedField#metadata()} during the scan, not from
     * the annotation instance itself.</p>
     *
     * <p>Nothing is written when no field survives those filters or when every surviving field had
     * nothing to store (skipped by its {@code skipWhen} predicate or equal to its default), so the
     * caller can clean an empty tag off the stack afterwards.</p>
     *
     * <p>Called by {@link com.gto.datasynclib.block.FieldDataHolderBlock} when the block drops
     * ({@code clone = false}) and when creative pick-block clones it ({@code clone = true}). Called
     * directly when a block hands out an item itself.</p>
     *
     * @param tag   the item's root tag, already writable
     * @param clone {@code true} for a pick-block clone, {@code false} for a real drop
     */
    @Override
    public void saveToItem(CompoundTag tag, boolean clone) {
        IDropSave.saveToItem(this,tag,clone,VERSION);
    }

    /**
     * Reads the {@link #saveToItem(CompoundTag, boolean) saved} fields back from {@code tag}, the
     * root tag of the stack being placed.
     *
     * <p>Every marked field is read; keys the payload does not contain are left alone, so a field
     * that was filtered out on the way out keeps the value the fresh block entity starts with.</p>
     *
     * <p>Called by {@link com.gto.datasynclib.block.FieldDataHolderBlock#setPlacedBy}; a block that
     * does not extend it calls this method itself. An absent payload, or a holder that marks no
     * field, leaves this block entity untouched.</p>
     *
     * @param tag the item's root tag
     */
    @Override
    public void loadFromItem(CompoundTag tag) {
        IDropSave.loadFromItem(this, tag);
    }


}