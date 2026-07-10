package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies the change detection strategy for this field.
 * Determines how the system detects whether the field's value has changed
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
