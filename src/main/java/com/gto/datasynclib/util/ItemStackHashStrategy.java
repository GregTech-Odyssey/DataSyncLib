package com.gto.datasynclib.util;

import it.unimi.dsi.fastutil.Hash;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Pre-built hash/equality strategies for {@link ItemStack} change detection, from coarsest to
 * finest. {@code DataSyncLib} registers {@link #ALL} for {@code ItemStack} fields by default;
 * pick another one with {@code @Strategy("ITEM")} on the field, or register your own via
 * {@link com.gto.datasynclib.FieldDefinitionStorage#registerStrategy(Class, Hash.Strategy)}.
 *
 * <p>All three treat {@code null} and an empty stack as equal, so an emptied slot does not
 * count as a change against a never-filled one.</p>
 */
public interface ItemStackHashStrategy extends Hash.Strategy<ItemStack> {

    /**
     * Item, count and NBT must all match. Registered by default for {@code ItemStack} fields.
     */
    ItemStackHashStrategy ALL = new ItemStackHashStrategy() {

        @Override
        public int hashCode(@Nullable ItemStack o) {
            if (o == null) return 0;
            var item = o.getItem();
            if (item == Items.AIR) return 0;
            return Objects.hash(item, o.getCount(), o.getTag());
        }

        @Override
        public boolean equals(@Nullable ItemStack a, @Nullable ItemStack b) {
            if (a == b) return true;
            if (a == null) return b.isEmpty();
            if (b == null) return a.isEmpty();
            if (a.getCount() != b.getCount()) return false;
            if (a.getItem() != b.getItem()) return false;
            return Objects.equals(a.getTag(), b.getTag());
        }
    };

    /**
     * Item and NBT must match; the count is ignored.
     */
    ItemStackHashStrategy ITEM_AND_TAG = new ItemStackHashStrategy() {

        @Override
        public int hashCode(@Nullable ItemStack o) {
            if (o == null) return 0;
            var item = o.getItem();
            if (item == Items.AIR) return 0;
            return Objects.hash(item, o.getTag());
        }

        @Override
        public boolean equals(@Nullable ItemStack a, @Nullable ItemStack b) {
            if (a == b) return true;
            if (a == null) return b.isEmpty();
            if (b == null) return a.isEmpty();
            if (a.getItem() != b.getItem()) return false;
            return Objects.equals(a.getTag(), b.getTag());
        }
    };

    /**
     * Only the item type must match; count and NBT are ignored.
     */
    ItemStackHashStrategy ITEM = new ItemStackHashStrategy() {

        @Override
        public int hashCode(ItemStack o) {
            if (o == null) return 0;
            var item = o.getItem();
            if (item == Items.AIR) return 0;
            return item.hashCode();
        }

        @Override
        public boolean equals(ItemStack a, ItemStack b) {
            if (a == b) return true;
            if (a == null) return b.isEmpty();
            if (b == null) return a.isEmpty();
            return a.getItem() == b.getItem();
        }
    };
}
