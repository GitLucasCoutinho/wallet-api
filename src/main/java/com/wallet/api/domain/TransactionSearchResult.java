package com.wallet.api.domain;

import java.util.List;

public record TransactionSearchResult(
        List<WalletTransaction> items,
        long totalElements,
        int page,
        int size
) {
}
