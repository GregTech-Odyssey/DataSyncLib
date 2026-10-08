package com.gto.datasynclib.entity;

import com.gto.datasynclib.FieldDataManager;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LazyFieldDataManager;
import com.gto.datasynclib.datastream.data.Data;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

/**
 * Ready-to-use {@link Entity} base class that implements {@link IFieldDataHolder} for automatic
 * field synchronization and persistence — the entity counterpart of
 * {@link com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity}.
 *
 * <h3>How to use:</h3>
 * <pre>{@code
 * public class MyEntity extends FieldDataHolderEntity {
 *     @SyncToClient @SaveToDisk
 *     private int charge;
 *
 *     public MyEntity(EntityType<?> type, Level level) {
 *         super(type, level);
 *     }
 *
 *     @Override
 *     public void tick() {
 *         super.tick();
 *         if (!level().isClientSide()) {
 *             DataSyncNetwork.syncEntityToClient(this, false, true); // push @SyncToClient fields
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h3>What you get automatically:</h3>
 * <ul>
 *   <li><strong>Disk persistence:</strong> {@link #addAdditionalSaveData} writes all
 *       {@code @SaveToDisk} fields as a {@code "field_save"} byte array plus the
 *       {@code "field_data_dataVersion"} marker, and {@link #readAdditionalSaveData} restores
 *       them</li>
 *   <li><strong>Field management:</strong> {@link #getFieldDataManager()} is created lazily, on the
 *       first field access</li>
 *   <li><strong>Remote calls:</strong> because the class is an {@link IFieldDataHolder},
 *       {@link com.gto.datasynclib.remote.RemoteNetwork} routes a received call into
 *       {@link IFieldDataHolder#handleRemoteCall(Object, byte[])} for it, so {@code @RemoteCall} methods
 *       on the entity (or a superclass) are reachable</li>
 * </ul>
 *
 * <h3>Manual steps needed:</h3>
 * <ul>
 *   <li>Register the entity type and define the synched data a vanilla entity needs
 *       ({@link #defineSynchedData()} is empty here, override it when the entity has its own)</li>
 *   <li>Call {@link com.gto.datasynclib.network.DataSyncNetwork#syncEntityToClient(Entity, boolean)
 *       DataSyncNetwork.syncEntityToClient()} from your tick to push updates</li>
 * </ul>
 *
 * <h3>Data version ({@link #VERSION}):</h3>
 * <p>Whatever {@link #VERSION} holds when the entity is saved is written to the NBT key
 * {@code "field_data_dataVersion"} and handed back to
 * {@link FieldDataManager#readFromData(com.gto.datasynclib.datastream.data.Data, int)} on load, where
 * it can drive migration. Like the block entity base, it is a single {@code static} shared by every
 * holder — assign it once during mod init, or override {@link #addAdditionalSaveData} /
 * {@link #readAdditionalSaveData} to version per entity type.</p>
 *
 * @see IFieldDataHolder
 * @see FieldDataManager
 * @see com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity
 */
public class FieldDataHolderEntity extends Entity implements IFieldDataHolder {

    /**
     * Data-format version written to {@code "field_data_dataVersion"}. Shared by all holders and
     * {@code 0} by default — see the class documentation before bumping it.
     */
    public static int VERSION;

    protected final LazyFieldDataManager fieldDataManager = new LazyFieldDataManager(this);

    public FieldDataHolderEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Override
    public FieldDataManager getFieldDataManager() {
        return fieldDataManager.get();
    }

    /**
     * No synched entity data of its own — override when the entity needs vanilla synched fields.
     */
    @Override
    protected void defineSynchedData() {
    }

    /**
     * Writes the current {@link #VERSION} and all {@code @SaveToDisk} fields, the same layout as
     * {@link com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity#saveAdditional(CompoundTag)}
     * uses for block entities.
     *
     * @param tag the NBT compound to save into
     */
    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        tag.putInt("field_data_dataVersion", VERSION);
        tag.putByteArray("field_save", getFieldDataManager().writeToData().writeToBytes());
    }

    /**
     * Restores the {@code @SaveToDisk} fields written by {@link #addAdditionalSaveData(CompoundTag)}.
     *
     * @param tag the NBT compound to load from
     */
    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        if (tag.contains("field_save")) {
            getFieldDataManager().readFromData(Data.readData(tag.getByteArray("field_save")), tag.getInt("field_data_dataVersion"));
        }
    }
}
