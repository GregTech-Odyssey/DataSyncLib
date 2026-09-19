package com.gto.datasynclib.forge;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.ListData;
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
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.BitSet;

/**
 * Synchronizes an array of {@link INBTSerializable} instances — the array counterpart of
 * {@link TagSerializableAccess}, which covers the scalar case. Registered by {@code DataSyncLib}
 * for components implementing {@code INBTSerializable}, so fields such as {@code ItemStackHandler[]}
 * or {@code FluidTank[]} work without a custom access.
 *
 * <h3>Wire format</h3>
 * <p>Each slot is written through the element's own {@code serializeNBT()}, using exactly the
 * per-element encoding of {@code TagSerializableAccess}: one NBT type byte followed by the payload,
 * where {@code 0}/{@link EndTag} means "absent". Reading a slot with an absent payload leaves the
 * existing element untouched, so the same buffer can be produced and consumed by the scalar codec
 * slot by slot.</p>
 *
 * <p>On an <strong>incremental</strong> sync only the slots that changed since the last write carry
 * a payload; every other slot is written as {@code 0}, which the receiver reads as "keep the element
 * I already have". A <strong>full</strong> sync ({@code writeAll}, e.g. the chunk-load
 * {@code field_sync} tag) always carries every slot, so a receiver that never saw an incremental
 * update still ends up with the complete array.</p>
 *
 * <h3>Persistence format</h3>
 * <p>A {@link ListData} with one entry per slot (null slots become {@code NullData.INSTANCE}); the
 * list is suppressed ({@link NullData#NONE}) when every slot is null and {@code saveNull} is off,
 * matching the other array accesses.</p>
 *
 * <h3>Change detection</h3>
 * <p>One cached hash per slot (an {@code int[]}), compared slot by slot — the comparison is between
 * two ints, never a full tree comparison. The whole array is walked so that <strong>every</strong>
 * changed slot is known in the same cycle and one packet can carry all of them; the slots that moved
 * are then the only ones whose payload is written (see the wire format above), which is what keeps
 * the packets small. {@link Tag#hashCode()} of a tag is deep, so the cached values are content
 * hashes. Hashing costs one {@code serializeNBT()} per element per check, the price of the
 * {@code INBTSerializable} API (it has no per-element dirty flag); no tag copy is retained, and —
 * unlike comparing tag references — a mutation of an implementation that returns its own live tag is
 * still detected. A length change re-primes every slot and marks them all dirty. As with any
 * hash-based accessor a collision can hide a change; on hot arrays prefer manual marking
 * ({@code @SyncToClient(autoUpdate = false)} plus {@code markFieldsForSync}), which still results in
 * a full payload because the framework cannot know which slot moved.</p>
 */
public final class TagSerializableArrayAccess extends AbstractFieldAccess<INBTSerializable[]> {

    /**
     * Hash of the serialized NBT last seen per slot ({@code 0} for a null slot).
     */
    private int[] slotHashes = ArrayUtils.EMPTY_INT_ARRAY;
    /**
     * One bit per slot, set while that slot is waiting to be written. A {@link BitSet} needs 1 bit
     * per slot where a {@code boolean[]} needs 1 byte, so it is 8× smaller on wide arrays and a wash
     * on the usual handful of slots; it also grows on demand, so a resized array needs no explicit
     * reallocation here.
     */
    private final BitSet dirtySlots = new BitSet();

    public TagSerializableArrayAccess(DataFieldDefinition<INBTSerializable[]> definition) {
        super(definition);
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, INBTSerializable @NotNull [] instance, boolean autoOnly) {
        if (this.slotHashes.length != instance.length) {
            // Different shape: everything has to be sent. Every slot is primed in one pass so the
            // next check does not report the same slots a second time.
            var slotHashes = this.slotHashes = new int[instance.length];
            for (int i = 0; i < instance.length; i++) {
                slotHashes[i] = hashOf(instance[i]);
            }
            dirtySlots.set(0, instance.length);
            return true;
        }
        var slotHashes = this.slotHashes;
        boolean hasChange = false;
        // The whole array is walked on purpose: collecting every changed slot in one pass lets a
        // single packet carry all of them, instead of deferring the tail to later cycles.
        for (int i = 0; i < instance.length; i++) {
            var hashCode = hashOf(instance[i]);
            if (hashCode != slotHashes[i]) {
                slotHashes[i] = hashCode;
                dirtySlots.set(i);
                hasChange = true;
            }
        }
        return hasChange;
    }

    /**
     * Hash of one slot: the hash of its serialized NBT, or {@code 0} for a null (or absent) slot.
     * {@link Tag#hashCode()} is deep, so this is a content hash of the whole slot.
     */
    private static int hashOf(@Nullable INBTSerializable element) {
        if (element == null) return 0;
        var nbt = element.serializeNBT();
        return nbt == null ? 0 : nbt.hashCode();
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, INBTSerializable @NotNull [] instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        // Without per-slot information (a manual markFieldsForSync, or a write that has no marks)
        // fall back to sending everything, so an explicit mark is never silently dropped.
        boolean all = writeAll || slotHashes.length != instance.length || dirtySlots.isEmpty();
        for (int i = 0; i < instance.length; i++) {
            if (!all && !dirtySlots.get(i)) {
                data.writeByte(0); // unchanged: the peer keeps the element it already has
                continue;
            }
            var nbt = instance[i] == null ? null : instance[i].serializeNBT();
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
        dirtySlots.clear();
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, INBTSerializable @NotNull [] instance, @NotNull FriendlyByteBuf data) {
        for (var element : instance) {
            var type = TagTypes.getType(data.readByte());
            if (type == EndTag.TYPE) continue;
            if (element == null) continue;
            try {
                element.deserializeNBT(type.load(new ByteBufInputStream(data), 0, NbtAccounter.UNLIMITED));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    protected @NotNull Data doWriteData(@NotNull Object source, INBTSerializable @NotNull [] instance) {
        var list = new ListData();
        for (var element : instance) {
            var nbt = element == null ? null : element.serializeNBT();
            if (nbt == null) {
                list.addNull();
            } else {
                list.add(DataCodecs.TAG_CODEC.encode(nbt));
            }
        }
        if (definition.saveNull) return list;
        for (var data : list) {
            if (data != NullData.INSTANCE) return list;
        }
        return NullData.NONE;
    }

    @Override
    protected void doReadData(INBTSerializable @NotNull [] instance, @NotNull Data data, int dataVersion) {
        var list = data.getList();
        var length = Math.min(list.size(), instance.length);
        for (int i = 0; i < length; i++) {
            var d = list.get(i);
            if (d == NullData.INSTANCE) continue;
            var element = instance[i];
            if (element == null) continue;
            element.deserializeNBT(DataCodecs.TAG_CODEC.decode(d, dataVersion));
        }
    }
}
