package com.wallet.api.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Outcome of a register-operation call.
 *
 * @param replayed {@code true} when the idempotency key had already been applied and the original
 *                 result is being returned; in that case the balance was not touched.
 */
public record TransactionReceipt(
        long walletId,
        long transactionId,
        TransactionType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        OffsetDateTime occurredAt,
        boolean replayed
) {
}
