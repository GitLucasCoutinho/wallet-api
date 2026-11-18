package com.wallet.api.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Read model of a wallet. {@code balance} is always scaled to 2 decimals, exactly as stored in
 * {@code NUMBER(19,2)}.
 */
public record Wallet(
        long id,
        String holderName,
        String holderDocument,
        String currency,
        BigDecimal balance,
        OffsetDateTime createdAt
) {
}
