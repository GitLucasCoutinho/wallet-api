package com.wallet.api.web.dto;

import com.wallet.api.domain.WalletTransaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransactionResponse(
        long id,
        long walletId,
        String type,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        String description,
        OffsetDateTime occurredAt
) {

    public static TransactionResponse from(WalletTransaction transaction) {
        return new TransactionResponse(
                transaction.id(),
                transaction.walletId(),
                transaction.type().name(),
                transaction.amount(),
                transaction.balanceBefore(),
                transaction.balanceAfter(),
                transaction.description(),
                transaction.occurredAt());
    }
}
