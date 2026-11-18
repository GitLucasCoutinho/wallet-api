package com.wallet.api.web;

import java.util.List;

/**
 * Field level detail of a validation failure.
 */
public record FieldViolation(String field, String message) {

    public static List<FieldViolation> of(List<FieldViolation> violations) {
        return violations == null || violations.isEmpty() ? null : List.copyOf(violations);
    }
}
