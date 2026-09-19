package com.gto.datasynclib.util;

import it.unimi.dsi.fastutil.Hash;
import net.minecraftforge.fluids.FluidStack;

/**
 * Array counterpart of {@link FluidStackHashStrategy}: the {@code FluidStack[]} change-detection
 * levels as named constants.
 *
 * <h3>Why it is needed</h3>
 * <p>{@link FluidStack} does implement content-based {@code hashCode}/{@code equals}, so unlike
 * {@code ItemStack[]} an unregistered {@code FluidStack[]} field is already compared slot by slot
 * through {@link java.util.Arrays#hashCode(Object[])}: an element filled in place
 * ({@code tanks[0].setAmount(500)}) <em>is</em> noticed without any registration.</p>
 *
 * <p>What the default cannot express is the <em>level</em> of the comparison — it always uses
 * {@code FluidStack.equals}, i.e. fluid + amount + NBT. Registering an array strategy for the field's
 * exact array type makes {@link com.gto.datasynclib.field.access.array.ArrayAccess} hash through it
 * instead, which buys two things: the amount/NBT-insensitive levels below ({@link #FLUID_AND_TAG},
 * {@link #FLUID}, useful for a tank that only cares that the *kind* of fluid changed), and one
 * consistent choice per array type that still lets a single field opt out with {@code @Strategy}.</p>
 *
 * <pre>{@code
 * // once, during mod construction (DataSyncLib does this for FluidStack[]):
 * FieldDefinitionStorage.registerStrategy(FluidStack[].class, FluidStackArrayHashStrategy.ALL);
 *
 * // then the level of the comparison is what changes, e.g. a tank that ignores the amount:
 * FieldDefinitionStorage.registerStrategy(FluidStack[].class, FluidStackArrayHashStrategy.FLUID);
 * }</pre>
 *
 * <p>Use the array form of the same level as the scalar field expects; {@code @Strategy("...")} on the
 * field still wins over this registry entry. A {@code null} element counts as an empty stack, matching
 * the scalar strategies.</p>
 *
 * <p>The constants are the scalar levels passed through {@link HashUtil#arrayStrategy(Hash.Strategy)},
 * so this class holds no logic of its own — an array of any other element type is covered by calling
 * that method directly.</p>
 *
 * @see FluidStackHashStrategy
 * @see ItemStackArrayHashStrategy
 */
public final class FluidStackArrayHashStrategy {

    private FluidStackArrayHashStrategy() {
    }

    /** Fluid, amount and NBT of every element must match. Registered for {@code FluidStack[]} by default. */
    public static final Hash.Strategy<FluidStack[]> ALL = HashUtil.arrayStrategy(FluidStackHashStrategy.ALL);

    /** Fluid and NBT of every element must match; amounts are ignored. */
    public static final Hash.Strategy<FluidStack[]> FLUID_AND_TAG = HashUtil.arrayStrategy(FluidStackHashStrategy.FLUID_AND_TAG);

    /** Only the fluid type of every element must match; amounts and NBT are ignored. */
    public static final Hash.Strategy<FluidStack[]> FLUID = HashUtil.arrayStrategy(FluidStackHashStrategy.FLUID);
}
