package com.gto.datasynclib.forge;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.field.access.AbstractFieldAccess;
import com.gto.datasynclib.util.DataCodecs;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.common.util.INBTSerializable;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Objects;

public final class TagSerializableAccess extends AbstractFieldAccess<INBTSerializable> {

    private Tag uid;

    public TagSerializableAccess(DataFieldDefinition<INBTSerializable> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull INBTSerializable instance, boolean autoOnly) {
        var nbt = instance.serializeNBT();
        if (!Objects.equals(uid, nbt)) {
            this.uid = nbt;
            return true;
        }
        return false;
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, @NotNull INBTSerializable instance, @NotNull FriendlyByteBuf data, boolean force) {
        var nbt = instance.serializeNBT();
        if (nbt == null) {
            data.writeByte(0);
        } else {
            data.writeByte(nbt.getId());
            try {
                nbt.write(new ByteBufOutputStream(data));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, @NotNull INBTSerializable instance, @NotNull FriendlyByteBuf data) {
        var type = TagTypes.getType(data.readByte());
        if (type == EndTag.TYPE) return;
        try {
            var nbt = type.load(new ByteBufInputStream(data), 0, NbtAccounter.UNLIMITED);
            instance.deserializeNBT(nbt);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected @NotNull Data doWriteData(@NotNull Object source, @NotNull INBTSerializable instance) {
        var nbt = instance.serializeNBT();
        if (nbt == null) {
            return NullData.INSTANCE;
        } else {
            return DataCodecs.TAG_CODEC.encode(instance.serializeNBT());
        }
    }

    @Override
    protected void doReadData(@NotNull INBTSerializable instance, @NotNull Data data, int dataVersion) {
        if (data.isNull()) return;
        var nbt = DataCodecs.TAG_CODEC.decode(data, dataVersion);
        instance.deserializeNBT(nbt);
    }
}
