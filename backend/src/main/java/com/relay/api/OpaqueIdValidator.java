package com.relay.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class OpaqueIdValidator implements ConstraintValidator<OpaqueId, String> {
    @Override public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) return true; // Requiredness is expressed with @NotNull.
        int count=value.codePointCount(0,value.length());
        if (count < 1 || count > 128) return false;
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
}
