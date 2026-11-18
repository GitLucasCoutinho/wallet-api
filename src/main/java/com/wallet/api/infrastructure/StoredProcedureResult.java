package com.wallet.api.infrastructure;

import java.math.BigDecimal;

/**
 * Raw output of {@code PKG_WALLET.REGISTER_TRANSACTION}.
 */
public record StoredProcedureResult(long transactionId, BigDecimal balanceAfter, boolean replayed) {
}
