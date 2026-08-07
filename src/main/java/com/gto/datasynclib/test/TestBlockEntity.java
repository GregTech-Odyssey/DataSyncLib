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
import lombok.Getter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.function.Function;

class TestBlockEntity extends FieldDataHolderBlockEntity {

    private static final FieldDataCodec<A> A_CODEC = FieldDataManager.createCodec(A.class, A::new);

    protected boolean isDirty;
    @SaveToDisk
    protected int i;

    @SaveToDisk
    private final String[] uuids = new String[3];

    @SaveToDisk
    private final ItemStack[] stacks = new ItemStack[9];

    @SaveToDisk
    private final Direction[][] directions = new Direction[3][3];

    @SaveToDisk
    private final Set<String> uuidSet = new HashSet<>();

    @SaveToDisk
    private final Map<Integer, Boolean> map = new HashMap<>();

    @SaveToDisk
    @Codec(saveCodec = "A_CODEC", syncCodec = "A_CODEC")
    @SyncToClient
    private A a = new A();

    @SaveToDisk
    @SyncToClient
    private final B b = new B();

    @SyncToServer
    private final ObjNotifiableHolder<String> objectHolder = ObjNotifiableHolder.create(DataSyncCodec.STRING_CODEC);

    /**
     * {@code @Conversion} example: the field is a {@code CompoundTag}, but the sync system
     * manages it as a {@code Map<String, Tag>} via this static Function. No setFunction is
     * needed because the field is {@code final} (the map is mutated in-place through the
     * {@code CompoundTag} API — {@code tagData.putInt(...)} etc.).
     */
    private static final Function<CompoundTag, Map<String, Tag>> COMPOUND_TAG_MAP_FUNCTION = NbtUtil.COMPOUND_TAG_MAP;

    @SyncToClient
    @SaveToDisk
    @Conversion(getFunction = "COMPOUND_TAG_MAP_FUNCTION")
    private final CompoundTag tagData = new CompoundTag();

    public TestBlockEntity(BlockPos worldPosition, BlockState blockState) {
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
    }

    public static <T extends BlockEntity> void tick(Level level, BlockPos blockPos, BlockState blockState, T t) {
        if (level instanceof ServerLevel serverLevel && t instanceof TestBlockEntity testBlockEntity) {
            testBlockEntity.serverTick(serverLevel);
        } else if (level.isClientSide() && t instanceof TestBlockEntity testBlockEntity) {
            testBlockEntity.clientTick();
        }
    }

    /** Runs the in-dev self-test suite exactly once per JVM. */
    private static volatile boolean selfTestsRun = false;

    protected void serverTick(ServerLevel level) {
        updateTick();
        if (!selfTestsRun) {
            selfTestsRun = true;
            DataSyncSelfTests.runAll();
        }
        if (level.getGameTime() % 20 == 0) {
            isDirty = true;
            ++b.a;
            b.c.value = b.a + "";
            a = new A(b.a);
            tagData.putInt("aaa", tagData.getInt("aaa") + 1);
            DataSyncNetwork.syncBlockEntityToClient(this, false, true);
        }
    }

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

    private static class A {

        private A(int a) {
            this.a = a;
        }

        private A() {

        }

        @AddToManager
        private int a;
    }

    private static class B implements IFieldDataHolder {

        @SaveToDisk
        private int a;

        @SaveToDisk
        private Item b = Items.IRON_INGOT;

        @SyncToClient
        private final ObjNotifiableHolder<String> c = ObjNotifiableHolder.create(DataSyncCodec.STRING_CODEC);

        public B() {
            c.setReceiverListener((s, n, o) -> {
                DataSyncLib.LOGGER.info("D changed: {} {} {}", s, n, o);
                b = Items.COPPER_INGOT;
            });
        }

        @Getter
        private final FieldDataManager fieldDataManager = new FieldDataManager(this);
    }
}
