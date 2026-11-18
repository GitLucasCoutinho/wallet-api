package com.wallet.api.web.dto;

import com.wallet.api.domain.Wallet;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record WalletResponse(
        long id,
        String holderName,
        String holderDocument,
        String currency,
        BigDecimal balance,
        OffsetDateTime createdAt
) {

    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.id(),
                wallet.holderName(),
                wallet.holderDocument(),
                wallet.currency(),
                wallet.balance(),
                wallet.createdAt());
    }
}
