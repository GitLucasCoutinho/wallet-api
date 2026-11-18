package com.wallet.api.domain;

import java.time.OffsetDateTime;

/**
 * Statement filter. All fields are optional and combined with AND.
 */
public record TransactionFilter(
        OffsetDateTime from,
        OffsetDateTime to,
        TransactionType type
) {

    public static TransactionFilter none() {
        return new TransactionFilter(null, null, null);
    }

    public boolean isEmpty() {
        return from == null && to == null && type == null;
    }
}
