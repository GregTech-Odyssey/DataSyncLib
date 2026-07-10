package com.gto.datasynclib.util;

import it.unimi.dsi.fastutil.Hash;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A configurable generator of hashing strategies, allowing for consideration of select properties of ItemStacks when
 * considering equality.
 */
public interface ItemStackHashStrategy extends Hash.Strategy<ItemStack> {

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
