package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies a custom change-detection strategy for this field by referencing a
 * static {@link it.unimi.dsi.fastutil.Hash.Strategy} field.
 *
 * <p>The referenced field must be a {@code public static} field of type
 * {@code Hash.Strategy<T>} accessible from the declaring class (or its parent class).
 * The strategy's {@code hashCode()} and {@code equals()} methods are used instead of
 * the default {@link java.util.Objects#equals(Object, Object)} and
 * {@link java.util.Objects#hashCode(Object)} for change detection.</p>
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
 * <p>If no {@code @Strategy} is specified, the default is
 * {@link com.gto.datasynclib.DataFieldDefinition#OBJECT_STRATEGY} (standard
 * {@code hashCode()/equals()}), unless a type-level strategy was registered via
 * {@link com.gto.datasynclib.FieldDefinitionStorage#registerStrategy(Class, it.unimi.dsi.fastutil.Hash.Strategy)}.</p>
 *
 * @see com.gto.datasynclib.util.ItemStackHashStrategy
 * @see com.gto.datasynclib.util.FluidStackHashStrategy
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
