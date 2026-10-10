package com.gto.datasynclib.util;

import com.gto.datasynclib.datastream.codec.JavaOps;
import com.gto.datasynclib.datastream.codec.ValueCodec;
import com.gto.datasynclib.datastream.codec.ValueOps;
import com.mojang.serialization.Codec;
import lombok.experimental.UtilityClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fluids.FluidStack;

/**
 * The Minecraft-typed {@link ValueCodec} constants — the twin of the network half in
 * {@link ByteBufCodecExtends}, and the replacement for the retired Data codec table. The carrier's own types
 * (the primitives and their arrays, String, UUID, BigInteger, the FastUtil primitive collections)
 * live on {@link ValueCodec} itself, next to the codec they are: these are the types that need a
 * game class to be built, so they live here.
 *
 * <p>Every constant writes exactly the bytes its retired counterpart wrote — a
 * {@code BlockPos} is still a three-int array, a {@code Vec2} still the raw bits of two floats, an
 * NBT tag still a registered custom payload, a {@link Component} still its JSON string — so a field
 * stored before the migration decodes through these unchanged. {@code DataSyncCodec} pairs each of
 * them with its stream half; nothing is registered in a per-codec table.</p>
 *
 * <p>They are hand-written {@code new ValueCodec<>()} implementations rather than
 * {@link ValueCodec#of}: a leaf codec is on the hot path of every field it backs, and {@code of}
 * would put a second, interface-dispatched call behind each {@code encode}/{@code decode}. They box
 * nothing either, since they talk to the carrier's primitives directly — see
 * {@link ValueCodec#composite} for when the convenience is worth more than that.</p>
 *
 * <p>{@link #fromCodec(Codec)} bridges a Mojang DFU codec, which is what the retired {@code Data} bridge
 * used to do — the value it produces is a carrier value, so no conversion
 * happens on a read.</p>
 */
@UtilityClass
public class ValueCodecs {

    /**
     * A registry key as its string, which is what a save file has always held.
     */
    public final ValueCodec<ResourceLocation> RESOURCE_LOCATION = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, ResourceLocation value) {
            return ops.createString(value.toString());
        }

        @Override
        public ResourceLocation decode(ValueOps ops, Object data) {
            return ResourceLocation.parse(ops.getString(data));
        }
    };

    /**
     * A three-int array; {@link #VEC3I} has the same shape.
     */
    public final ValueCodec<BlockPos> BLOCK_POS = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, BlockPos value) {
            return ops.createIntArray(new int[]{value.getX(), value.getY(), value.getZ()});
        }

        @Override
        public BlockPos decode(ValueOps ops, Object data) {
            var array = ops.getIntArray(data);
            return new BlockPos(array[0], array[1], array[2]);
        }
    };

    /**
     * Two floats as their raw bits, so {@code NaN} and {@code -0.0f} survive.
     */
    public final ValueCodec<Vec2> VEC2 = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Vec2 value) {
            return ops.createFloatArray(new float[]{value.x, value.y});
        }

        @Override
        public Vec2 decode(ValueOps ops, Object data) {
            var array = ops.getFloatArray(data);
            return new Vec2(array[0], array[1]);
        }
    };

    /**
     * Three doubles as their raw bits.
     */
    public final ValueCodec<Vec3> VEC3 = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Vec3 value) {
            return ops.createDoubleArray(new double[]{value.x, value.y, value.z});
        }

        @Override
        public Vec3 decode(ValueOps ops, Object data) {
            var array = ops.getDoubleArray(data);
            return new Vec3(array[0], array[1], array[2]);
        }
    };

    /**
     * A chunk position as its packed long.
     */
    public final ValueCodec<ChunkPos> CHUNK_POS = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, ChunkPos value) {
            return ops.createLong(value.toLong());
        }

        @Override
        public ChunkPos decode(ValueOps ops, Object data) {
            return new ChunkPos(ops.getLong(data));
        }
    };

    /**
     * Integer triple in an {@code int[]}; {@link #BLOCK_POS} uses the same shape.
     */
    public final ValueCodec<Vec3i> VEC3I = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Vec3i value) {
            return ops.createIntArray(new int[]{value.getX(), value.getY(), value.getZ()});
        }

        @Override
        public Vec3i decode(ValueOps ops, Object data) {
            var array = ops.getIntArray(data);
            return new Vec3i(array[0], array[1], array[2]);
        }
    };

    /**
     * Section (16³) position as its packed long.
     */
    public final ValueCodec<SectionPos> SECTION_POS = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, SectionPos value) {
            return ops.createLong(value.asLong());
        }

        @Override
        public SectionPos decode(ValueOps ops, Object data) {
            return SectionPos.of(ops.getLong(data));
        }
    };

    /**
     * Six doubles — min then max — in a single primitive-backed array.
     */
    public final ValueCodec<AABB> AABB = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, AABB value) {
            return ops.createDoubleArray(new double[]{value.minX, value.minY, value.minZ, value.maxX, value.maxY, value.maxZ});
        }

        @Override
        public AABB decode(ValueOps ops, Object data) {
            var array = ops.getDoubleArray(data);
            return new AABB(array[0], array[1], array[2], array[3], array[4], array[5]);
        }
    };

    // ---- NBT: a registered custom payload holding the tag itself, no conversion at all ----

    public final ValueCodec<ListTag> LIST_TAG = ValueCodec.custom(NbtUtil.LIST_TAG_TYPE);

    public final ValueCodec<CompoundTag> COMPOUND_TAG = ValueCodec.custom(NbtUtil.COMPOUND_TAG_TYPE);

    public final ValueCodec<Tag> TAG = ValueCodec.custom(NbtUtil.TAG_TYPE);

    public final ValueCodec<ItemStack> ITEM_STACK = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, ItemStack value) {
            return COMPOUND_TAG.encode(ops, value.save(new CompoundTag()));
        }

        @Override
        public ItemStack decode(ValueOps ops, Object data) {
            return ItemStack.of(COMPOUND_TAG.decode(ops, data));
        }
    };

    public final ValueCodec<FluidStack> FLUID_STACK = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, FluidStack value) {
            return COMPOUND_TAG.encode(ops, value.writeToNBT(new CompoundTag()));
        }

        @Override
        public FluidStack decode(ValueOps ops, Object data) {
            return FluidStack.loadFluidStackFromNBT(COMPOUND_TAG.decode(ops, data));
        }
    };

    /**
     * A component as its JSON string — the shape the persistence path has always used, which keeps
     * a component readable in a save file.
     */
    public final ValueCodec<Component> COMPONENT = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Component value) {
            return ops.createString(Component.Serializer.toJson(value));
        }

        @Override
        public Component decode(ValueOps ops, Object data) {
            return Component.Serializer.fromJson(ops.getString(data));
        }
    };

    /**
     * Builds a codec for entries of a Minecraft {@link Registry}: values are written as their
     * registry key (self-describing, stable across game versions), and decoded by looking the key
     * back up.
     *
     * @param registry the registry the values belong to
     */
    public <T> ValueCodec<T> of(Registry<T> registry) {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T value) {
                return ops.createString(registry.getKey(value).toString());
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                return registry.get(ResourceLocation.parse(ops.getString(data)));
            }
        };
    }


    /**
     * A Mojang DFU {@link Codec} seen as a value codec: it runs on {@link JavaOps}, so the carrier of
     * a DFU codec is a carrier value. This replaces
     * the retired {@code DataCodec.of(Codec)}, and it reads the same values that bridge produced.
     */
    public static <T> ValueCodec<T> fromCodec(Codec<T> codec) {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T value) {
                return codec.encodeStart(JavaOps.INSTANCE, value).result().orElseThrow();
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                return codec.parse(JavaOps.INSTANCE, data).result().orElseThrow();
            }
        };
    }

}
