package com.gto.datasynclib.test;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.DataSyncLib;
import com.gto.datasynclib.FieldDataManager;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.annotations.SaveToDisk;
import com.gto.datasynclib.annotations.SyncToClient;
import com.gto.datasynclib.annotations.SyncToServer;
import com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity;
import com.gto.datasynclib.datastream.data.StringMapData;
import com.gto.datasynclib.listener.ObjNotifiableHolder;
import com.gto.datasynclib.network.DataSyncNetwork;
import lombok.Getter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

 class TestBlockEntity extends FieldDataHolderBlockEntity {

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
    private final A a = new A();

    @SaveToDisk
    @SyncToClient
    private final B b = new B();

    @SyncToServer
    private final ObjNotifiableHolder<String> objectHolder = ObjNotifiableHolder.create(DataSyncCodec.STRING_CODEC);

    @SyncToClient
    private final StringMapData mapData = new StringMapData();

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

    protected void serverTick(ServerLevel level) {
        updateTick();
        if (level.getGameTime() % 20 == 0) {
            isDirty = true;
            ++b.a;
            b.c.value = b.a + "";
            mapData.putInt("aaa", mapData.getInt("aaa") + 1);
            DataSyncNetwork.syncBlockEntityToClient(this, false, true);
        }
    }

    protected void clientTick() {
        if (level.getGameTime() % 20 == 0) {
            getFieldDataManager().markFieldsForSync("objectHolder");
            DataSyncNetwork.syncBlockEntityToServer(this, false, true);
            DataSyncLib.LOGGER.info("mapData: {}", mapData);
        }
    }

    public final void updateTick() {
        if (isDirty) {
            setChanged();
            isDirty = false;
        }
    }

    private static class A implements IFieldDataHolder {

        @SaveToDisk
        private int a;

        @Getter
        private final FieldDataManager fieldDataManager = new FieldDataManager(this);
    }

    private static class B implements IFieldDataHolder {

        @SaveToDisk
        private int a;

        @SaveToDisk
        private final Item b = Items.IRON_INGOT;

        @SyncToClient
        private final ObjNotifiableHolder<String> c = ObjNotifiableHolder.create(DataSyncCodec.STRING_CODEC);

        public B() {
            c.setReceiverListener((s, n, o) -> {
                DataSyncLib.LOGGER.info("D changed: {} {} {}", s, n, o);
            });
        }

        @Getter
        private final FieldDataManager fieldDataManager = new FieldDataManager(this);
    }
}
