package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Forces a field to be resolved through the <strong>generic type</strong> factory chain
 * ({@link com.gto.datasynclib.FieldDefinitionStorage#GENERIC_FIELDS_CACHE}) rather than
 * the direct type factory chain.
 *
 * <p>Normally, a field's type is resolved through the FIELDS cache first, falling back to
 * GENERIC only when the direct type lookup fails. This annotation reverses that priority
 * for the annotated field, which is necessary when:</p>
 * <ul>
 *   <li>The field's declared type is a raw type (e.g., {@code List}) but you want
 *       generic-type-aware serialization (e.g., {@code List<String>} with string codec)</li>
 *   <li>The field's generic type parameters carry essential type information that the
 *       raw type factory cannot provide</li>
 * </ul>
 *
 * <p>The field MUST have resolvable generic type parameters. If the generic type cannot
 * be determined at runtime, a runtime exception will be thrown during field scanning.</p>
 *
 * @see com.gto.datasynclib.DataSyncCodec#get(Class, Class...)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Generic {

}
