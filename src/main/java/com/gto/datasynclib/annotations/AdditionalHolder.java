package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field whose <em>declared class</em> should be recursively scanned for
 * annotations ({@code @SaveToDisk}, {@code @SyncToClient}, {@code @SyncToServer},
 * {@code @AddToManager}), registering those nested fields with the parent class's
 * {@link com.gto.datasynclib.FieldDataManager}.
 *
 * <p>This enables <strong>nested holder</strong> support: a parent object can have
 * a field pointing to a sub-object, and the sub-object's annotated fields are
 * treated as if they were declared directly on the parent. The access path is
 * composed via chained {@link java.lang.invoke.MethodHandle} getters.</p>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * class MachineBlockEntity extends FieldDataHolderBlockEntity {
 *     @AdditionalHolder
 *     private InventoryComponent inventory = new InventoryComponent();
 * }
 *
 * class InventoryComponent {
 *     @SaveToDisk
 *     @SyncToClient
 *     private int itemCount;
 * }
 * }</pre>
 *
 * <p>The nested class's fields are discovered recursively, so an
 * {@code @AdditionalHolder} field inside another {@code @AdditionalHolder} class
 * will also be scanned (chained nesting).</p>
 *
 * <p>At runtime, {@link com.gto.datasynclib.IFieldDataHolder#getSource} resolves
 * the composed getter chain to reach the actual owning object.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AdditionalHolder {

}
