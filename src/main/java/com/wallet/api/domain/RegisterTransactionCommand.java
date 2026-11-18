package com.wallet.api.domain;

import java.math.BigDecimal;

/**
 * A credit/debit to apply.
 *
 * @param requestHash SHA-256 fingerprint of the normalized payload, computed by the application
 *                    and stored next to the idempotency key: the database uses it to tell a retry
 *                    (same key, same payload) from a key reuse (same key, different payload).
 */
public record RegisterTransactionCommand(
        TransactionType type,
        BigDecimal amount,
        String description,
        String idempotencyKey,
        String requestHash
) {
}
