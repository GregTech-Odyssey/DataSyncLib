package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies a custom change-detection strategy for this field by referencing a
 * static {@link it.unimi.dsi.fastutil.Hash.Strategy} field.
 *
 * <p>The referenced field must be a {@code static} field of type {@code Hash.Strategy<T>}
 * declared in the <em>same class</em> as the annotated field (the lookup is
 * {@link Class#getDeclaredField(String)}, which does not search superclasses; any access
 * modifier is accepted because the framework calls {@code setAccessible(true)}).
 * The strategy's {@code hashCode()} and {@code equals()} methods are used instead of the
 * default {@link com.gto.datasynclib.DataFieldDefinition#OBJECT_STRATEGY} for change
 * detection.</p>
 *
 * <h3>Example usage:</h3>
 * <pre>{@code
 * public static final Hash.Strategy<ItemStack> ITEM_ONLY = new Hash.Strategy<>() {
 *     public int hashCode(ItemStack s) { return Item.getId(s.getItem()); }
 *     public boolean equals(ItemStack a, ItemStack b) {
 *         return (a == b) || (a != null && b != null && a.getItem() == b.getItem());
 *     }
 * };
 *
 * @Strategy("ITEM_ONLY")
 * @SyncToClient
 * private ItemStack stack;
 * }</pre>
 *
 * <p>An <em>array</em> field needs a strategy of the array type, which is derived from the element
 * strategy with
 * {@link com.gto.datasynclib.util.HashUtil#arrayStrategy(it.unimi.dsi.fastutil.Hash.Strategy)}:</p>
 *
 * <pre>{@code
 * public static final Hash.Strategy<MyKey[]> KEYS = HashUtil.arrayStrategy(MyKeyHashStrategy.ALL);
 *
 * @Strategy("KEYS")
 * private MyKey[] keys = new MyKey[4];
 * }</pre>
 *
 * <p>If no {@code @Strategy} is specified, the default is
 * {@link com.gto.datasynclib.DataFieldDefinition#OBJECT_STRATEGY} (standard
 * {@code hashCode()/equals()}), unless a type-level strategy was registered via
 * {@link com.gto.datasynclib.FieldDefinitionStorage#registerStrategy(Class, it.unimi.dsi.fastutil.Hash.Strategy)}.</p>
 *
 * @see com.gto.datasynclib.util.ItemStackHashStrategy
 * @see com.gto.datasynclib.util.FluidStackHashStrategy
 * @see com.gto.datasynclib.util.ItemStackArrayHashStrategy
 * @see com.gto.datasynclib.util.FluidStackArrayHashStrategy
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Strategy {

    /**
     * The static field name of the strategy.
     * The specified field must be accessible from this class.
     *
     * @return the static field name referencing the strategy
     */
    String value();
}
