package com.relay.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy=OpaqueIdValidator.class)
public @interface OpaqueId {
    String message() default "must contain 1 to 128 Unicode code points";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
