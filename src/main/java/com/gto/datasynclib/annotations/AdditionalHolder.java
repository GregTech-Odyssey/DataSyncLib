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
 * <h3>Flat mode (default):</h3>
 * <p>The annotated field's fields are scanned and flattened into the parent manager,
 * resolved through a composed getter chain. The sub-object itself is <em>not</em>
 * serialized as a unit; only its individually-annotated fields are.</p>
 *
 * <h3>Child-manager mode ({@code childManager = true}):</h3>
 * <p>The annotated field is <em>not</em> flattened. Instead a dedicated child
 * {@link com.gto.datasynclib.FieldDataManager} is generated for the field's object,
 * and the whole object is handled as one field through
 * {@link com.gto.datasynclib.IFieldDataHolder IFieldDataHolder}-style access
 * (equivalent to {@link com.gto.datasynclib.field.access.FieldDataHolderAccess}).
 * Any {@code @SaveToDisk}/{@code @SyncTo*} annotations held <em>inside</em> the
 * sub-object's class are managed by that child manager, not the parent.</p>
 *
 * <pre>{@code
 * class Machine extends FieldDataHolderBlockEntity {
 *     @AdditionalHolder // flat: fields inside InventoryComponent go to parent
 *     private InventoryComponent flat = new InventoryComponent();
 *
 *     @AdditionalHolder(childManager = true) // child manager: whole object handled as one field
 *     private ModuleComponent module = new ModuleComponent();
 * }
 * }</pre>
 *
 * @see com.gto.datasynclib.IFieldDataHolder
 * @see com.gto.datasynclib.field.access.FieldDataHolderAccess
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AdditionalHolder {

    /**
     * Whether to treat this field as a standalone child manager rather than flattening
     * its annotated fields into the parent manager.
     *
     * <p>When {@code true}, the field's object is wrapped in a dedicated
     * {@link com.gto.datasynclib.IFieldDataHolder} adapter owning its own
     * {@link com.gto.datasynclib.FieldDataManager}, and the whole object is serialized
     * as a single field (no field-level flattening into the parent).</p>
     *
     * @return {@code true} to generate a child manager for this field; {@code false} (default)
     * to flatten its fields into the parent manager
     */
    boolean childManager() default false;
}
