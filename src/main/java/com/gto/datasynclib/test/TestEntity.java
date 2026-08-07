package com.gto.datasynclib.test;

import com.gto.datasynclib.FieldDataManager;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.annotations.AdditionalHolder;
import com.gto.datasynclib.annotations.SaveToDisk;
import com.gto.datasynclib.annotations.SyncToClient;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.network.DataSyncNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;

/**
 * Development-only test entity that exercises {@link IFieldDataHolder} on a live
 * {@link Entity}.
 *
 * <p>It wires:
 * <ul>
 *   <li><strong>Disk persistence</strong> — {@link #readAdditionalSaveData}/{@link #addAdditionalSaveData}
 *       forward to {@link FieldDataManager#readFromData}/{@link FieldDataManager#writeToData()}.</li>
 *   <li><strong>Network sync</strong> — {@link #tick()} calls
 *       {@link DataSyncNetwork#syncEntityToClient(Entity, boolean)} on the server.</li>
 *   <li><strong>Nested sub-manager</strong> — a {@code @AdditionalHolder(childManager = true)} field
 *       demonstrates the child-manager mode on a non-holder sub-object.</li>
 * </ul>
 */
public class TestEntity extends Entity implements IFieldDataHolder {


    @SaveToDisk
    @SyncToClient
    private int syncTicks;

    @SaveToDisk
    @SyncToClient
    private float speed;

    @SaveToDisk
    @SyncToClient
    private final String title = "";

    // Child-manager mode: managed by a dedicated sub-manager, not flattened into this entity's manager.
    @SaveToDisk
    @SyncToClient
    @AdditionalHolder(childManager = true)
    private final TestEntitySub sub = new TestEntitySub();

    public TestEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {

    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(0.6F, 1.8F);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && level().getGameTime() % 20 == 0) {
            ++syncTicks;
            sub.points += 10;
            DataSyncNetwork.syncEntityToClient(this, false, true);
        }
    }

    // ==================== IFieldDataHolder — disk + manager ====================

    /**
     * Key under which the whole child-manager-backed payload is stored in entity NBT.
     */
    private static final String DATA_KEY = "dsl_data";
    private static final String DATA_VERSION_KEY = "dsl_data_version";
    private static final int DATA_VERSION = 0;

    private final FieldDataManager fieldDataManager = new FieldDataManager(this);

    @Override
    public FieldDataManager getFieldDataManager() {
        return fieldDataManager;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt(DATA_VERSION_KEY, DATA_VERSION);
        tag.putByteArray(DATA_KEY, getFieldDataManager().writeToData().writeToBytes());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains(DATA_KEY)) {
            getFieldDataManager().readFromData(
                    Data.readData(tag.getByteArray(DATA_KEY)),
                    tag.getInt(DATA_VERSION_KEY));
        }
    }
}
