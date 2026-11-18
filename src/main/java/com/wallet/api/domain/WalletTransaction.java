package com.wallet.api.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One entry of the wallet statement. Immutable by design: the database rejects UPDATE/DELETE on
 * the underlying table, so this object is always a faithful copy of a posted entry.
 */
public record WalletTransaction(
        long id,
        long walletId,
        TransactionType type,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        String idempotencyKey,
        String description,
        OffsetDateTime occurredAt
) {
}
