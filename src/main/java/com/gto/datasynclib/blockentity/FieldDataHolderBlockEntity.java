package com.gto.datasynclib.blockentity;

import com.gto.datasynclib.FieldDataManager;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LazyFieldDataManager;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.RegistryContext;
import com.gto.datasynclib.SyncContext;
import com.gto.datasynclib.datastream.data.Data;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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
 *   <li><strong>Chunk-load sync:</strong> {@link #getUpdateTag(HolderLookup.Provider)} includes
 *       all {@code @SyncToClient} fields as {@code "field_sync"} NBT data, and
 *       {@link #handleUpdateTag(CompoundTag, HolderLookup.Provider)} applies them on the
 *       client</li>
 *   <li><strong>Disk persistence:</strong>
 *       {@link #saveAdditional(CompoundTag, HolderLookup.Provider)} writes all
 *       {@code @SaveToDisk} fields as {@code "field_save"} NBT data with versioning</li>
 *   <li><strong>Disk loading:</strong>
 *       {@link #loadAdditional(CompoundTag, HolderLookup.Provider)} reads {@code "field_save"}
 *       back for a full restore — the chunk-load path stays separate, so a client never
 *       mistakes synced state for saved state</li>
 * </ul>
 *
 * <h3>1.21 signatures:</h3>
 * <p>In 1.21 the NBT entry points are passed a {@link HolderLookup.Provider}, which the nested
 * field codecs need but cannot receive as a parameter. This class therefore publishes it with
 * {@link RegistryContext#use} for the duration of each disk operation, and derives a
 * {@link SyncContext} from it for the initial sync. NeoForge delivers chunk-sync tags through
 * {@link #handleUpdateTag(CompoundTag, HolderLookup.Provider)} and block entity data packets
 * through {@link #onDataPacket(Connection, ClientboundBlockEntityDataPacket, HolderLookup.Provider)},
 * which is why {@link #loadAdditional(CompoundTag, HolderLookup.Provider)} only handles the
 * saved form.</p>
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
 * {@link FieldDataManager#readFromData(com.gto.datasynclib.datastream.data.Data, int)} on
 * load, where it can drive migration. Raise it when the meaning or layout of persisted
 * fields changes.</p>
 *
 * <p><strong>Note:</strong> it is a single {@code static} on this base class — shared by
 * every holder and {@code 0} by default. Declaring another {@code VERSION} field in a
 * subclass does <em>not</em> affect what is written, because
 * {@link #saveAdditional(CompoundTag, HolderLookup.Provider)} reads the version through
 * {@link #dataVersion()}, which is bound at compile time to this class's field. Assign
 * {@code FieldDataHolderBlockEntity.VERSION = N} once during mod init, or override
 * {@link #dataVersion()} to version per holder type.</p>
 *
 * @see com.gto.datasynclib.IFieldDataHolder
 * @see com.gto.datasynclib.FieldDataManager
 */

public class FieldDataHolderBlockEntity extends BlockEntity implements IFieldDataHolder {

    /**
     * Data-format version written to {@code "field_data_dataVersion"}. Shared by all holders
     * and {@code 0} by default — see the class documentation before bumping it.
     */
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
     * Data-format version written for <em>this</em> holder. Defaults to the shared
     * {@link #VERSION}; override to version one holder type independently of the static.
     *
     * @return the version stored next to this holder's saved fields
     */
    protected int dataVersion() {
        return VERSION;
    }

    /**
     * Saves BlockEntity data to NBT for disk persistence.
     *
     * <p>Writes {@link #dataVersion()} under {@code "field_data_dataVersion"} and all
     * {@code @SaveToDisk} fields as a byte array under {@code "field_save"} (via
     * {@link FieldDataManager#writeToData()}).</p>
     *
     * <p>Only fields annotated with {@code @SaveToDisk} are included; nothing is written when
     * the holder has none. Use {@link FieldDataManager#writeAllToData()} if you need all
     * managed fields.</p>
     *
     * <p>1.21: the registry lookup is published through {@link RegistryContext} while the
     * fields are serialized, so registry-dependent values can still be encoded.</p>
     *
     * @param tag    the NBT compound tag to save into
     * @param lookup the registry lookup of the saving side
     */
    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider lookup) {
        super.saveAdditional(tag, lookup);
        if (!getFieldDataManager().hasSaveFields()) return;
        try (var ignored = RegistryContext.use(lookup)) {
            tag.putInt("field_data_dataVersion", dataVersion());
            tag.putByteArray("field_save", getFieldDataManager().writeToData().writeToBytes());
        }
    }

    /**
     * Loads BlockEntity data from NBT.
     *
     * <p>If the {@code "field_save"} byte array is present, the data is treated as a full disk
     * load (via {@link FieldDataManager#readFromData} with the version from
     * {@code "field_data_dataVersion"}).</p>
     *
     * <p>1.21: the chunk-load sync path no longer goes through this method — NeoForge hands the
     * {@code getUpdateTag} result to
     * {@link #handleUpdateTag(CompoundTag, HolderLookup.Provider)} instead, so synced state is
     * never written by accident when the client joins.</p>
     *
     * @param tag    the NBT compound tag to load from
     * @param lookup the registry lookup of the loading side
     */
    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider lookup) {
        super.loadAdditional(tag, lookup);
        if (!tag.contains("field_save", Tag.TAG_BYTE_ARRAY)) return;
        try (var ignored = RegistryContext.use(lookup)) {
            getFieldDataManager().readFromData(
                    Data.readData(tag.getByteArray("field_save")),
                    tag.getInt("field_data_dataVersion"));
        }
    }

    /**
     * Builds the network context for the initial (chunk-load) sync.
     *
     * <p>1.21 passes the registry lookup explicitly, but not always as a
     * {@link RegistryAccess}; the level is used as the fallback.</p>
     *
     * @param lookup the registry lookup handed to the chunk-sync entry point
     * @return the context used to serialize the initial sync payload
     */
    private SyncContext syncContext(@NotNull HolderLookup.Provider lookup) {
        if (lookup instanceof RegistryAccess access) {
            return SyncContext.neoforge(access);
        }
        if (level != null) {
            return SyncContext.neoforge(level.registryAccess());
        }
        throw new IllegalStateException("Initial sync requires registry access");
    }

    /**
     * Overrides the vanilla chunk-sync tag to include all {@code @SyncToClient} fields.
     *
     * <p>When a chunk is sent to a player, Minecraft calls this method to get the initial
     * BlockEntity data. By including serialized sync fields under the {@code "field_sync"}
     * key, newly-arriving players receive the current state without needing a separate
     * sync packet.</p>
     *
     * <p>The data is encoded via {@link FieldDataManager#writeSnapshot(LogicalSide, SyncContext)}
     * (a full, <strong>non-consuming</strong> write): the pending deltas of players that are
     * already tracking the chunk stay queued, so a newcomer does not steal them.</p>
     *
     * @param lookup the registry lookup of the sending side
     * @return the compound tag containing vanilla data plus {@code "field_sync"} byte array
     */
    @Override
    public @NotNull CompoundTag getUpdateTag(@NotNull HolderLookup.Provider lookup) {
        var tag = super.getUpdateTag(lookup);
        if (getFieldDataManager().hasSyncFields(LogicalSide.SERVER)) {
            tag.putByteArray("field_sync", getFieldDataManager().writeSnapshot(LogicalSide.SERVER, syncContext(lookup)));
        }
        return tag;
    }

    /**
     * Applies the {@code getUpdateTag} data received on the client.
     *
     * <p>1.21 replaced the old {@code load(CompoundTag)} entry point for chunk sync: NeoForge
     * routes the chunk's BlockEntity tag here, so {@code "field_sync"} is decoded with
     * {@link LogicalSide#CLIENT} (the client's view of the {@code @SyncToClient} fields) and
     * never touches the saved {@code "field_save"} form.</p>
     *
     * @param tag    the chunk-sync tag sent by the server
     * @param lookup the registry lookup of the receiving client
     */
    @Override
    public void handleUpdateTag(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider lookup) {
        if (!tag.contains("field_sync", Tag.TAG_BYTE_ARRAY)) return;
        getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, syncContext(lookup), tag.getByteArray("field_sync"));
    }

    /**
     * Sends this holder's {@code getUpdateTag} data as a block entity data packet, so a
     * server-driven update can reach tracking clients before the next chunk send.
     *
     * @return the packet carrying {@link #getUpdateTag(HolderLookup.Provider)}
     */
    @Override
    public @NotNull ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * Applies a block entity data packet on the client.
     *
     * <p>The packet carries exactly the {@code getUpdateTag} payload, so it is handled by the
     * same code path.</p>
     *
     * @param connection the connection the packet came from
     * @param packet     the received packet
     * @param lookup     the registry lookup of the receiving client
     */
    @Override
    public void onDataPacket(@NotNull Connection connection, @NotNull ClientboundBlockEntityDataPacket packet, @NotNull HolderLookup.Provider lookup) {
        handleUpdateTag(packet.getTag(), lookup);
    }
}
