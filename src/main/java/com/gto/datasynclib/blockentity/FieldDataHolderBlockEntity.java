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


    @Override
    public @NotNull CompoundTag getUpdateTag() {
        var tag = super.getUpdateTag();
        if (getFieldDataManager().hasSyncFields(LogicalSide.SERVER)) {
            tag.putByteArray("field_sync", getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, true));
        }
        return tag;
    }

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

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("field_data_dataVersion", VERSION);
        tag.putByteArray("field_save", getFieldDataManager().writeToData().writeToBytes());
    }


}