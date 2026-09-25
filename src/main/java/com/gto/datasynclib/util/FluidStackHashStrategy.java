package com.gto.datasynclib.util;

import it.unimi.dsi.fastutil.Hash;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Pre-built hash/equality strategies for {@link FluidStack} change detection, from coarsest to
 * finest. {@code DataSyncLib} registers {@link #ALL} for {@code FluidStack} fields by default;
 * pick another one with {@code @Strategy("FLUID")} on the field, or register your own via
 * {@link com.gto.datasynclib.FieldDefinitionStorage#registerStrategy(Class, Hash.Strategy)}.
 *
 * <p>All three treat {@code null} and an empty stack as equal, so an emptied tank does not
 * count as a change against a never-filled one.</p>
 *
 * @see FluidStackArrayHashStrategy
 */
public interface FluidStackHashStrategy extends Hash.Strategy<FluidStack> {

    /**
     * Fluid, amount and components must all match. Registered by default for {@code FluidStack}
     * fields.
     */
    FluidStackHashStrategy ALL = new FluidStackHashStrategy() {

        @Override
        public int hashCode(@Nullable FluidStack o) {
            if (o == null) return 0;
            var fluid = o.getFluid();
            if (fluid == Fluids.EMPTY) return 0;
            // 1.21 replaced the stack's raw NBT tag with its data components.
            return 31 * (31 * fluid.hashCode() + o.getAmount()) + o.getComponents().hashCode();
        }

        @Override
        public boolean equals(@Nullable FluidStack a, @Nullable FluidStack b) {
            if (a == b) return true;
            if (a == null) return b.isEmpty();
            if (b == null) return a.isEmpty();
            if (a.getAmount() != b.getAmount()) return false;
            if (a.getFluid() != b.getFluid()) return false;
            return Objects.equals(a.getComponents(), b.getComponents());
        }
    };

    /**
     * Fluid and components must match; the amount is ignored.
     */
    FluidStackHashStrategy FLUID_AND_TAG = new FluidStackHashStrategy() {

        @Override
        public int hashCode(@Nullable FluidStack o) {
            if (o == null) return 0;
            var item = o.getFluid();
            if (item == Fluids.EMPTY) return 0;
            return 31 * item.hashCode() + o.getComponents().hashCode();
        }

        @Override
        public boolean equals(@Nullable FluidStack a, @Nullable FluidStack b) {
            if (a == b) return true;
            if (a == null) return b.isEmpty();
            if (b == null) return a.isEmpty();
            if (a.getFluid() != b.getFluid()) return false;
            return Objects.equals(a.getComponents(), b.getComponents());
        }
    };

    /**
     * Only the fluid type must match; amount and components are ignored.
     */
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
