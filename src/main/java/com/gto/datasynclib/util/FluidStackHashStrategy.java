package com.gto.datasynclib.util;

import it.unimi.dsi.fastutil.Hash;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Configurable hash/equality strategy for FluidStack comparison.
 * Provides FLUID (identity only), FLUID_AND_TAG, and ALL comparison levels.
 */
public interface FluidStackHashStrategy extends Hash.Strategy<FluidStack> {

    FluidStackHashStrategy ALL = new FluidStackHashStrategy() {

        @Override
        public int hashCode(@Nullable FluidStack o) {
            if (o == null) return 0;
            var fluid = o.getFluid();
            if (fluid == Fluids.EMPTY) return 0;
            return Objects.hash(fluid, o.getAmount(), o.getTag());
        }

        @Override
        public boolean equals(@Nullable FluidStack a, @Nullable FluidStack b) {
            if (a == b) return true;
            if (a == null) return b.isEmpty();
            if (b == null) return a.isEmpty();
            if (a.getAmount() != b.getAmount()) return false;
            if (a.getFluid() != b.getFluid()) return false;
            return Objects.equals(a.getTag(), b.getTag());
        }
    };

    FluidStackHashStrategy FLUID_AND_TAG = new FluidStackHashStrategy() {

        @Override
        public int hashCode(@Nullable FluidStack o) {
            if (o == null) return 0;
            var item = o.getFluid();
            if (item == Fluids.EMPTY) return 0;
            return Objects.hash(item, o.getTag());
        }

        @Override
        public boolean equals(@Nullable FluidStack a, @Nullable FluidStack b) {
            if (a == b) return true;
            if (a == null) return b.isEmpty();
            if (b == null) return a.isEmpty();
            if (a.getFluid() != b.getFluid()) return false;
            return Objects.equals(a.getTag(), b.getTag());
        }
    };

    FluidStackHashStrategy FLUID = new FluidStackHashStrategy() {

        @Override
        public int hashCode(FluidStack o) {
            if (o == null) return 0;
            var fluid = o.getFluid();
            if (fluid == Fluids.EMPTY) return 0;
            return fluid.hashCode();
        }

        @Override
        public boolean equals(FluidStack a, FluidStack b) {
            if (a == b) return true;
            if (a == null) return b.isEmpty();
            if (b == null) return a.isEmpty();
            return a.getFluid() == b.getFluid();
        }
    };
}
