package com.wallet.api.domain;

import com.wallet.api.domain.exception.ErrorCode;
import com.wallet.api.domain.exception.InvalidRequestException;

import java.util.regex.Pattern;

/**
 * Client supplied idempotency key. Constraints are duplicated in the database
 * ({@code VARCHAR2(80)}), so both sides reject the same input.
 */
public final class IdempotencyKeys {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 80;

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:-]+");

    private IdempotencyKeys() {
    }

    public static String requireValid(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException(ErrorCode.MISSING_IDEMPOTENCY_KEY,
                    "cabeçalho Idempotency-Key é obrigatório");
        }
        String key = raw.trim();
        if (key.length() > MAX_LENGTH) {
            throw new InvalidRequestException(ErrorCode.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key deve ter no máximo " + MAX_LENGTH + " caracteres");
        }
        if (key.length() < MIN_LENGTH) {
            throw new InvalidRequestException(ErrorCode.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key deve ter pelo menos " + MIN_LENGTH + " caracteres");
        }
        if (!ALLOWED.matcher(key).matches()) {
            throw new InvalidRequestException(ErrorCode.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key aceita apenas letras, números e os caracteres . _ : -");
        }
        return key;
    }
}
