package com.wallet.api.web.dto;

import com.wallet.api.domain.TransactionReceipt;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Result of a register-operation call. {@code replayed = true} means the idempotency key had
 * already been applied: the response repeats the original outcome and the balance was not touched.
 */
public record TransactionReceiptResponse(
        long walletId,
        long transactionId,
        String type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        OffsetDateTime occurredAt,
        boolean replayed
) {

    public static TransactionReceiptResponse from(TransactionReceipt receipt) {
        return new TransactionReceiptResponse(
                receipt.walletId(),
                receipt.transactionId(),
                receipt.type().name(),
                receipt.amount(),
                receipt.balanceAfter(),
                receipt.occurredAt(),
                receipt.replayed());
    }
}
