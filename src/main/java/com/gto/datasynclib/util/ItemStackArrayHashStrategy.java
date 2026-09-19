package com.gto.datasynclib.util;

import it.unimi.dsi.fastutil.Hash;
import net.minecraft.world.item.ItemStack;

/**
 * Array counterpart of {@link ItemStackHashStrategy}: the {@code ItemStack[]} change-detection levels
 * as named constants.
 *
 * <h3>Why it is needed</h3>
 * <p>{@link ItemStack} does not override {@code hashCode}/{@code equals} (vanilla compares stacks
 * through {@link ItemStack#matches}). An object array of them would therefore be hashed by element
 * <em>identity</em>, so writing into a stack in place — {@code stacks[0].setCount(2)} — would go
 * unnoticed, while replacing the slot would not. Registering an array strategy for the field's exact
 * array type makes {@link com.gto.datasynclib.field.access.array.ArrayAccess} use content hashing
 * instead of {@code Arrays.hashCode}.</p>
 *
 * <pre>{@code
 * // once, during mod construction (DataSyncLib does this for ItemStack[]):
 * FieldDefinitionStorage.registerStrategy(ItemStack[].class, ItemStackArrayHashStrategy.ALL);
 *
 * // then an in-place change inside an array element is detected like any other field change:
 * stacks[0].setCount(2);
 * manager.updateFieldDirtyFlags(LogicalSide.SERVER, true); // true
 * }</pre>
 *
 * <p>Use the array form of the same level as the scalar field expects; {@code @Strategy("...")} on
 * the field still wins over this registry entry. A {@code null} element counts as an empty stack,
 * matching the scalar strategies.</p>
 *
 * <p>The constants are the scalar levels passed through {@link HashUtil#arrayStrategy(Hash.Strategy)},
 * so this class holds no logic of its own — an array of any other element type is covered by calling
 * that method directly.</p>
 *
 * @see ItemStackHashStrategy
 * @see FluidStackArrayHashStrategy
 */
public final class ItemStackArrayHashStrategy {

    private ItemStackArrayHashStrategy() {
    }

    /** Item, count and NBT of every element must match. Registered for {@code ItemStack[]} by default. */
    public static final Hash.Strategy<ItemStack[]> ALL = HashUtil.arrayStrategy(ItemStackHashStrategy.ALL);

    /** Item and NBT of every element must match; counts are ignored. */
    public static final Hash.Strategy<ItemStack[]> ITEM_AND_TAG = HashUtil.arrayStrategy(ItemStackHashStrategy.ITEM_AND_TAG);

    /** Only the item type of every element must match; counts and NBT are ignored. */
    public static final Hash.Strategy<ItemStack[]> ITEM = HashUtil.arrayStrategy(ItemStackHashStrategy.ITEM);
}
