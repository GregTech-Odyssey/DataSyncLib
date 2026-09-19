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

/**
 * Synchronizes a scalar {@link INBTSerializable} — Forge's {@code ItemStackHandler}, {@code FluidTank},
 * or any custom {@code INBTSerializable} holder — through its own {@code serializeNBT()} /
 * {@code deserializeNBT(...)} pair. Registered by {@code DataSyncLib} for fields whose type
 * implements {@code INBTSerializable}; {@link TagSerializableArrayAccess} covers arrays of them.
 *
 * <h3>Wire format</h3>
 * <p>One NBT type byte followed by the payload, where {@code 0}/{@link EndTag} stands for "absent"
 * (a null serialized tag) — reading such a slot leaves the current value untouched. The payload is
 * loaded with {@link NbtAccounter#UNLIMITED}.</p>
 *
 * <h3>Persistence format</h3>
 * <p>The tag goes through {@link DataCodecs#TAG_CODEC}; a null tag is written as
 * {@link NullData#INSTANCE} and read back as a no-op.</p>
 *
 * <h3>Change detection</h3>
 * <p>A single cached hash of the serialized tag ({@link Tag#hashCode()} of a tag is deep, i.e. a
 * content hash). Hashing costs one {@code serializeNBT()} per check, but unlike comparing tag
 * <em>references</em> it also detects mutations of an implementation that returns its own live tag.
 * A hash collision can hide a change, and this detection is not free — on hot fields prefer manual
 * marking ({@code @SyncToClient(autoUpdate = false)} plus {@code markFieldsForSync}).</p>
 */
public final class TagSerializableAccess extends AbstractFieldAccess<INBTSerializable> {

    private int hashCode;
    private boolean hasHash;

    public TagSerializableAccess(DataFieldDefinition<INBTSerializable> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull INBTSerializable instance, boolean autoOnly) {
        var nbt = instance.serializeNBT();
        var hashCode = nbt == null ? 0 : nbt.hashCode();
        // hasHash: an empty tag hashes to 0, which must not look like "unchanged" on the first check.
        if (!hasHash || hashCode != this.hashCode) {
            this.hasHash = true;
            this.hashCode = hashCode;
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
            return DataCodecs.TAG_CODEC.encode(nbt);
        }
    }

    @Override
    protected void doReadData(@NotNull INBTSerializable instance, @NotNull Data data, int dataVersion) {
        if (data.isNull()) return;
        var nbt = DataCodecs.TAG_CODEC.decode(data, dataVersion);
        instance.deserializeNBT(nbt);
    }
}
