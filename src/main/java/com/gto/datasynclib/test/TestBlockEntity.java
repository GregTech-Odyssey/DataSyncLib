package com.gto.datasynclib.test;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.DataSyncLib;
import com.gto.datasynclib.FieldDataManager;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.annotations.*;
import com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity;
import com.gto.datasynclib.listener.ObjNotifiableHolder;
import com.gto.datasynclib.network.DataSyncNetwork;
import com.gto.datasynclib.util.FieldDataCodec;
import com.gto.datasynclib.util.NbtUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import lombok.Getter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.ItemStackHandler;

import java.util.*;
import java.util.function.Function;

/**
 * Development-only block entity used as the subject of {@link TestBlockEntityTests}: it carries one
 * field per access/factory family so the suite can drive every managed code path through a real
 * {@code BlockEntity} (NBT save/load, network buffers, listeners, conditions, child managers, ...).
 *
 * <h3>How to read this class as an example</h3>
 * <p>Every field below is annotated the way an application would annotate it, and the comment above
 * it names the machinery that picks it up:</p>
 * <ul>
 *   <li>primitives and objects with a codec → value-mode fields ({@code IntField}, {@code ObjCodecField})</li>
 *   <li>{@code final} primitive arrays and containers → access-mode fields
 *       ({@code IntArrayAccess}, {@code CollectionAccess}, {@code MapAccess}, FastUtil variants)</li>
 *   <li>{@code IFieldDataHolder} / {@code INBTSerializable} members → holder accessors
 *       ({@code FieldDataHolderAccess}, {@code TagSerializableAccess}), arrays of them included</li>
 *   <li>{@code @AdditionalHolder(childManager = true)} → a plain POJO with its own manager</li>
 *   <li>{@code @Codec} / {@code @Conversion} → custom serialization and type adaptation</li>
 *   <li>{@code @SaveToDisk(listener)} / {@code @SyncToClient(listener)} → change-notification hooks</li>
 * </ul>
 * <p>{@code TestBlockEntityTests} is the executable documentation for all of it — read the two side by
 * side. Fields and nested types are package-private on purpose so the suite can read and mutate them
 * directly. Only registered when the mod is not running in production (see {@code DataSyncLib}).</p>
 */
class TestBlockEntity extends FieldDataHolderBlockEntity {

    static final FieldDataCodec<A> A_CODEC = FieldDataManager.createCodec(A.class, A::new);

    /** Not annotated: must never show up in the managed definitions. */
    boolean isDirty;

    /** Primitive value field ({@code IntField}): value-mode, persisted only. */
    @SaveToDisk
    int i;

    /** Object array ({@code ArrayAccess} + the {@code String} codec); elements may be null. */
    @SaveToDisk
    final String[] uuids = new String[3];

    /** Array of a type without content equality — see the array strategy registration for ItemStack[]. */
    @SaveToDisk
    final ItemStack[] stacks = new ItemStack[9];

    /** Scalar {@code ItemStack}: exercises the registered {@link com.gto.datasynclib.util.ItemStackHashStrategy}. */
    @SaveToDisk
    @SyncToClient
    ItemStack stack = new ItemStack(Items.IRON_INGOT, 1);

    /** Scalar {@code FluidStack}: exercises the registered {@link com.gto.datasynclib.util.FluidStackHashStrategy}. */
    @SaveToDisk
    @SyncToClient
    FluidStack fluid = new FluidStack(Fluids.WATER, 1000);

    /**
     * Fluid array ({@code ArrayAccess} + the {@code FluidStack} codec), covered by the registered
     * {@link com.gto.datasynclib.util.FluidStackArrayHashStrategy} so an element refilled in place is
     * detected; a {@code null} slot means "no fluid" and is encoded as such.
     */
    @SaveToDisk
    final FluidStack[] tanks = new FluidStack[3];

    /** Nested array: the element is itself an array, so it goes through {@code ArrayAccess} with an enum-array codec. */
    @SaveToDisk
    final Direction[][] directions = new Direction[3][3];

    /** {@code java.util} containers → {@code CollectionAccess} / {@code MapAccess} (mutated in place). */
    @SaveToDisk
    final Set<String> uuidSet = new HashSet<>();

    @SaveToDisk
    final Map<Integer, Boolean> map = new HashMap<>();

    /** Minecraft value type with a codec ({@code AABB}, hand-written pair). */
    @SaveToDisk
    AABB aabb = new AABB(1, 4, 5, 6, 1, 5);

    /** {@code @Codec}: serialization delegated to the {@link FieldDataCodec} field named by the annotation. */
    @SaveToDisk
    @Codec(saveCodec = "A_CODEC", syncCodec = "A_CODEC")
    @SyncToClient
    A a = new A();

    /** Nested {@code IFieldDataHolder}: kept as one field and synced through its own manager. */
    @SaveToDisk
    @SyncToClient
    final B b = new B();

    /** Sync-only, container-typed field: the holder carries its own change detection and listener. */
    @SyncToServer
    final ObjNotifiableHolder<String> objectHolder = ObjNotifiableHolder.create(DataSyncCodec.STRING_CODEC);

    /**
     * {@code final} primitive arrays take the access-mode pair (snapshot + {@code Arrays.equals}).
     * A non-final one would be handled by the codec-backed value field instead.
     */
    @SaveToDisk
    @SyncToClient
    final int[] ints = new int[3];

    @SaveToDisk
    @SyncToClient
    final float[] floats = new float[4];

    /** FastUtil containers → IntCollectionAccess / Object2IntMapAccess. */
    @SaveToDisk
    @SyncToClient
    final IntList intList = new IntArrayList();

    @SaveToDisk
    @SyncToClient
    final Object2IntMap<String> object2IntMap = new Object2IntOpenHashMap<>();

    /** Forge {@code INBTSerializable} → TagSerializableAccess / TagSerializableArrayAccess. */
    @SaveToDisk
    @SyncToClient
    final ItemStackHandler handler = new ItemStackHandler(3);

    @SaveToDisk
    @SyncToClient
    final ItemStackHandler[] handlerArray = {new ItemStackHandler(2), new ItemStackHandler(2)};

    /** {@code @AdditionalHolder(childManager = true)} → ChildManagerAccess over a plain POJO. */
    @SaveToDisk
    @SyncToClient
    @AdditionalHolder(childManager = true)
    ChildModule module = new ChildModule();

    /** Hand-written codecs added to the built-in set. */
    @SaveToDisk
    @SyncToClient
    GlobalPos globalPos = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 2, 3));

    @SaveToDisk
    SectionPos sectionPos = SectionPos.of(1, 2, 3);

    @SaveToDisk
    Vec3i vec3i = new Vec3i(1, 2, 3);

    /** Equals the configured default → skipped on disk; changing it makes it written. */
    @SaveToDisk(defaultValue = "7")
    int withDefault = 7;

    /** Skipped while {@link #skipMe(int)} says so. */
    @SaveToDisk(condition = "skipMe")
    int conditioned = 0;

    /** {@code autoUpdate = false}: only sent after an explicit markFieldsForSync. */
    @SyncToClient(autoUpdate = false)
    int manual = 0;

    /** Exercises the {@code @SaveToDisk(listener = ...)} hook added in this version. */
    @SaveToDisk(listener = "onLoaded")
    int loaded = 0;

    int loadedListenerCalls = 0;
    int lastLoaded = -1;

    /** Exercises the sync listener hook. */
    @SyncToClient(listener = "onSynced")
    int synced = 0;

    int syncedListenerCalls = 0;
    int lastSyncedOld = -1;

    /**
     * {@code @Conversion} example: the field is a {@code CompoundTag}, but the sync system
     * manages it as a {@code Map<String, Tag>} via this static Function. No setFunction is
     * needed because the field is {@code final} (the map is mutated in-place through the
     * {@code CompoundTag} API — {@code tagData.putInt(...)} etc.).
     */
    static final Function<CompoundTag, Map<String, Tag>> COMPOUND_TAG_MAP_FUNCTION = NbtUtil.COMPOUND_TAG_MAP;

    @SyncToClient
    @SaveToDisk
    @Conversion(getFunction = "COMPOUND_TAG_MAP_FUNCTION")
    final CompoundTag tagData = new CompoundTag();

    TestBlockEntity(BlockPos worldPosition, BlockState blockState) {
        super(ModBlockEntities.TEST_BLOCK_ENTITY.get(), worldPosition, blockState);
        objectHolder.value = "aaa";
        objectHolder.setReceiverListener((s, n, o) -> DataSyncLib.LOGGER.info("changed: {} {} {}", s, n, o));
        for (int i = 0; i < uuids.length; i++) {
            uuids[i] = UUID.randomUUID().toString().substring(24);
            uuidSet.add(UUID.randomUUID().toString().substring(24));
            map.put(i, Math.random() > 0.5);
            directions[i][i] = Direction.values()[i];
            stacks[i] = new ItemStack(Items.IRON_INGOT, i + 1);
            stacks[i].setDamageValue(i << 1);
        }
        tanks[0] = new FluidStack(Fluids.WATER, 1000);
        tanks[2] = new FluidStack(Fluids.LAVA, 500); // tanks[1] stays null on purpose: "no fluid here"
        intList.add(1);
        intList.add(2);
        object2IntMap.put("a", 1);
        handler.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 1));
        handlerArray[0].setStackInSlot(0, new ItemStack(Items.GOLD_INGOT, 2));
        module.name = "module";
        module.values.add(1);
        tagData.putInt("aaa", 1);
    }

    /**
     * Condition method referenced by {@code @SaveToDisk(condition = "skipMe")}.
     *
     * <p><b>Signature:</b> {@code (T fieldType) -> boolean}, non-static, declared on the same class as
     * the field (same rule for {@code @SyncToClient(condition = ...)}/ {@code @SyncToServer(...)}). The
     * field is skipped when it returns {@code true}.</p>
     */
    boolean skipMe(int value) {
        return value < 0;
    }

    /** Test hook: the BE-level save path ({@code saveAdditional} is protected in the base class). */
    CompoundTag saveToTag() {
        var tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }

    /** Test hook: the BE-level load path. */
    void loadFromTag(CompoundTag tag) {
        load(tag);
    }

    /**
     * Target of {@code @SaveToDisk(listener = "onLoaded")}.
     *
     * <p><b>Signature:</b> exactly one parameter of the field's declared type — {@code int} here, not
     * {@code Integer}. Called after a disk load only, and only when the field was present in the saved
     * data. It is the place to rebuild state derived from the restored value.</p>
     */
    void onLoaded(int value) {
        loadedListenerCalls++;
        lastLoaded = value;
    }

    /**
     * Target of {@code @SyncToClient(listener = "onSynced")}.
     *
     * <p><b>Signature:</b> {@code (T newValue, T oldValue)}. Called on the receiving side every time the
     * value is applied through the network path (not on disk loads), so it is the hook for reacting to a
     * peer's change.</p>
     */
    void onSynced(int newValue, int oldValue) {
        syncedListenerCalls++;
        lastSyncedOld = oldValue;
    }

    /**
     * The usual BlockEntity tick entry point: server and client halves are separated here so the example
     * shows both sync directions.
     */
    public static <T extends BlockEntity> void tick(Level level, BlockPos blockPos, BlockState blockState, T t) {
        if (level instanceof ServerLevel serverLevel && t instanceof TestBlockEntity testBlockEntity) {
            testBlockEntity.serverTick(serverLevel);
        } else if (level.isClientSide() && t instanceof TestBlockEntity testBlockEntity) {
            testBlockEntity.clientTick();
        }
    }

    /**
     * Runs the in-dev test suites exactly once per JVM.
     */
    static volatile boolean selfTestsRun = false;

    /**
     * Server-side tick: the pattern to copy is <em>change the fields, then ask the framework to sync</em>.
     *
     * <pre>{@code
     * DataSyncNetwork.syncBlockEntityToClient(this, all, autoOnly);
     * //  all      = false -> incremental (only changed fields), true -> full state
     * //  autoOnly = true  -> only fields with autoUpdate = true are watched, false -> all of them
     * // The call is async-safe: the snapshot is taken now, the packet is sent on the server thread.
     * }</pre>
     *
     * <p>Persistence needs {@code setChanged()} (here driven by the {@link #isDirty} flag through
     * {@link #updateTick()}); fields declared with {@code autoUpdate = false} additionally need
     * {@code getFieldDataManager().markFieldsForSync("name")} before the sync call.</p>
     */
    protected void serverTick(ServerLevel level) {
        updateTick();
        if (!selfTestsRun) {
            selfTestsRun = true;
            DataSyncSelfTests.runAll();
            TestBlockEntityTests.runAll();
        }
        if (level.getGameTime() % 20 == 0) {
            isDirty = true;
            ++b.a;
            b.c.value = b.a + "";
            a = new A(b.a);
            aabb = new AABB(1, 4, 5, 6, 1, b.a);
            tagData.putInt("aaa", tagData.getInt("aaa") + 1);
            DataSyncNetwork.syncBlockEntityToClient(this, false, true);
        }
    }

    /**
     * Client-side tick: the client→server direction, used here for the {@code @SyncToServer} field.
     * Because {@code objectHolder} is not marked with {@code autoUpdate = false}, the explicit
     * {@code markFieldsForSync} call below is only there to demonstrate the manual-marking API.
     */
    protected void clientTick() {
        if (level.getGameTime() % 20 == 0) {
            getFieldDataManager().markFieldsForSync("objectHolder");
            DataSyncNetwork.syncBlockEntityToServer(this, false, true);
            DataSyncLib.LOGGER.info("tagData: {}", tagData);
            DataSyncLib.LOGGER.info("a.a: {}", a.a);
        }
    }

    public final void updateTick() {
        if (isDirty) {
            setChanged();
            isDirty = false;
        }
    }

    /** POJO managed through {@code @Codec} + {@link FieldDataCodec}. */
    static class A {

        A(int a) {
            this.a = a;
        }

        A() {
        }

        @AddToManager
        int a;
    }

    /** Nested {@link IFieldDataHolder}: handled by {@code FieldDataHolderAccess}. */
    static class B implements IFieldDataHolder {

        @SaveToDisk
        int a;

        @SaveToDisk
        Item b = Items.IRON_INGOT;

        @SyncToClient
        final ObjNotifiableHolder<String> c = ObjNotifiableHolder.create(DataSyncCodec.STRING_CODEC);

        int receiverCalls = 0;
        String lastReceived = null;

        B() {
            c.setReceiverListener((s, n, o) -> {
                DataSyncLib.LOGGER.info("D changed: {} {} {}", s, n, o);
                b = Items.COPPER_INGOT;
                receiverCalls++;
                lastReceived = n;
            });
        }

        @Getter
        final FieldDataManager fieldDataManager = new FieldDataManager(this);
    }

    /** Plain POJO with its own child manager (does not implement {@link IFieldDataHolder}). */
    static class ChildModule {

        @SaveToDisk
        @SyncToClient
        int ticks;

        @SaveToDisk
        @SyncToClient
        String name = "";

        @SaveToDisk
        List<Integer> values = new ArrayList<>();
    }
}
