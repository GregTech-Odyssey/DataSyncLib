package com.gto.datasynclib.util;

import com.gto.datasynclib.datastream.codec.StreamCodec;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/**
 * The <strong>write-to-disk</strong> half of the codec toolkit: the same {@link StreamCodec} set as
 * {@link ByteBufCodecExtends}, except that everything whose network form is a registry <em>number</em> is
 * written by its <em>key</em> here.
 *
 * <p>A number is an index into the registry of the session that wrote it: it is compact and correct on a
 * channel whose two ends share a registry, and it means nothing in a file. A save is read by a client
 * whose mods, datapacks and therefore registry order may differ from the writer's, and a number would
 * then name another entry — or nothing — with no way to tell. The key
 * ({@code minecraft:stone}) is what survives that. So a codec that goes into a save file is taken from
 * here, a codec that goes over the wire from {@link ByteBufCodecExtends}, and the two write the same
 * value differently on purpose.</p>
 *
 * <p>The members whose form is already self-describing — {@link #RESOURCE_LOCATION_CODEC},
 * {@link #TAG_CODEC}/{@link #COMPOUND_TAG_CODEC}/{@link #LIST_TAG_CODEC}, the {@code JsonElement} trio,
 * the positions, {@link #COMPONENT_CODEC} — are inherited unchanged, since there is nothing session-bound
 * about them. Only the registry-keyed entries and the two stacks are defined again here.</p>
 */
public interface DiskByteBufCodecs extends ByteBufCodecExtends {

    /**
     * Builds a codec for entries of a Minecraft {@link Registry} that writes the entry's key, the durable
     * counterpart of {@link ByteBufCodecExtends#of(Registry)}'s registry id.
     *
     * <p>A key the registry does not hold decodes to {@code null} rather than failing: a save made with a
     * mod that has since been removed names an entry that is simply gone, and a missing value is what the
     * caller can act on. An entry that exists but is not registered under a key at all — a value built on
     * the fly — throws on the way out, since there is no durable name for it.</p>
     *
     * @param registry the registry the values belong to
     */
    static <T> StreamCodec<FriendlyByteBuf, T> of(Registry<T> registry) {
        return new StreamCodec<>() {

            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                var key = registry.getKey(obj);
                if (key == null) {
                    throw new IllegalArgumentException("A " + registry.key().location() + " entry without a key "
                            + "cannot be stored: it has no name a later session could look up (" + obj + ")");
                }
                RESOURCE_LOCATION_CODEC.encode(buf, key);
            }

            @Override
            public @Nullable T decode(FriendlyByteBuf buf) {
                return registry.get(RESOURCE_LOCATION_CODEC.decode(buf));
            }
        };
    }

    /**
     * An {@link Item} by its key, where {@link ByteBufCodecExtends#of(Registry)} over the item registry
     * would use its id.
     */
    StreamCodec<FriendlyByteBuf, Item> ITEM = of(BuiltInRegistries.ITEM);

    /**
     * A {@link Block} by its key.
     */
    StreamCodec<FriendlyByteBuf, Block> BLOCK = of(BuiltInRegistries.BLOCK);

    /**
     * A {@link Fluid} by its key.
     */
    StreamCodec<FriendlyByteBuf, Fluid> FLUID = of(BuiltInRegistries.FLUID);

    /**
     * An {@link ItemStack} as the tag it saves itself to — its item's key inside it, plus the count and
     * the stack's own tag — where {@link #ITEM_STACK_CODEC} writes the item's id.
     */
    StreamCodec<FriendlyByteBuf, ItemStack> ITEM_STACK = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, ItemStack obj) {
            NbtUtil.write(obj.save(new CompoundTag()), buf);
        }

        @Override
        public ItemStack decode(FriendlyByteBuf buf) {
            return ItemStack.of((CompoundTag) NbtUtil.read(Tag.TAG_COMPOUND, buf));
        }
    };

    /**
     * A {@link FluidStack} as the tag it saves itself to, where {@link #FLUID_STACK_CODEC} writes its
     * fluid's id.
     */
    StreamCodec<FriendlyByteBuf, FluidStack> FLUID_STACK = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, FluidStack obj) {
            NbtUtil.write(obj.writeToNBT(new CompoundTag()), buf);
        }

        @Override
        public FluidStack decode(FriendlyByteBuf buf) {
            return FluidStack.loadFluidStackFromNBT((CompoundTag) NbtUtil.read(Tag.TAG_COMPOUND, buf));
        }
    };
}
