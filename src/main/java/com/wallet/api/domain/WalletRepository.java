package com.wallet.api.domain;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for wallet persistence.
 *
 * <p>Reads are plain SQL against the ledger tables (the runtime user has SELECT). Writes exist
 * only through {@code PKG_WALLET}, so this interface exposes operations, never table mutations.
 */
public interface WalletRepository {

    Wallet create(String holderName, String holderDocument);

    Optional<Wallet> findById(long walletId);

    TransactionReceipt register(long walletId, RegisterTransactionCommand command);

    List<WalletTransaction> findTransactions(long walletId, TransactionFilter filter, int offset, int limit);

    long countTransactions(long walletId, TransactionFilter filter);
}
