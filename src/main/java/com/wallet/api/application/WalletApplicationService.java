package com.wallet.api.application;

import com.wallet.api.config.PaginationProperties;
import com.wallet.api.domain.Amounts;
import com.wallet.api.domain.IdempotencyKeys;
import com.wallet.api.domain.RegisterTransactionCommand;
import com.wallet.api.domain.RequestFingerprint;
import com.wallet.api.domain.TransactionFilter;
import com.wallet.api.domain.TransactionReceipt;
import com.wallet.api.domain.TransactionSearchResult;
import com.wallet.api.domain.TransactionType;
import com.wallet.api.domain.Wallet;
import com.wallet.api.domain.WalletRepository;
import com.wallet.api.domain.exception.ErrorCode;
import com.wallet.api.domain.exception.InvalidRequestException;
import com.wallet.api.domain.exception.WalletNotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Use cases of the wallet domain.
 *
 * <p>The service validates the request (fail fast, with a precise error code) and delegates every
 * consistency decision to the database: the balance rule, the row lock and the idempotency check
 * are all inside {@code PKG_WALLET}. Nothing here opens a transaction - the stored procedure owns
 * it - so a request can never leave a half applied operation behind.
 */
@Service
public class WalletApplicationService {

    private static final int MAX_HOLDER_NAME_LENGTH = 120;
    private static final int MAX_HOLDER_DOCUMENT_LENGTH = 32;
    private static final int MAX_DESCRIPTION_LENGTH = 255;

    private final WalletRepository repository;
    private final PaginationProperties pagination;

    public WalletApplicationService(WalletRepository repository, PaginationProperties pagination) {
        this.repository = repository;
        this.pagination = pagination;
    }

    public Wallet createWallet(String holderName, String holderDocument) {
        String name = normalizeRequiredText(holderName, MAX_HOLDER_NAME_LENGTH,
                ErrorCode.INVALID_HOLDER, "nome do titular");
        String document = normalizeOptionalText(holderDocument, MAX_HOLDER_DOCUMENT_LENGTH,
                ErrorCode.INVALID_HOLDER, "documento do titular");
        return repository.create(name, document);
    }

    public Wallet getWallet(long walletId) {
        return repository.findById(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    public TransactionSearchResult searchTransactions(long walletId, TransactionFilter filter,
                                                      int page, int size) {
        if (page < 0) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "page deve ser maior ou igual a 0");
        }
        int maxPageSize = pagination.getMaxPageSize();
        if (size < 1 || size > maxPageSize) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER,
                    "size deve estar entre 1 e " + maxPageSize);
        }
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER,
                    "from deve ser anterior ou igual a to");
        }

        // 404 for an unknown wallet, instead of an empty page.
        getWallet(walletId);

        long total = repository.countTransactions(walletId, filter);
        long offset = (long) page * size;
        if (offset >= total) {
            return new TransactionSearchResult(List.of(), total, page, size);
        }
        return new TransactionSearchResult(
                repository.findTransactions(walletId, filter, (int) offset, size), total, page, size);
    }

    public TransactionReceipt registerTransaction(long walletId, TransactionType type,
                                                  BigDecimal amount, String description,
                                                  String idempotencyKey) {
        if (type == null) {
            throw new InvalidRequestException(ErrorCode.INVALID_TRANSACTION_TYPE, "tipo é obrigatório");
        }
        BigDecimal normalizedAmount = Amounts.normalize(amount);
        String key = IdempotencyKeys.requireValid(idempotencyKey);
        String normalizedDescription = normalizeOptionalText(description, MAX_DESCRIPTION_LENGTH,
                ErrorCode.INVALID_DESCRIPTION, "descrição");

        String fingerprint = RequestFingerprint.of(type, normalizedAmount, normalizedDescription);
        RegisterTransactionCommand command = new RegisterTransactionCommand(
                type, normalizedAmount, normalizedDescription, key, fingerprint);
        return repository.register(walletId, command);
    }

    private static String normalizeRequiredText(String raw, int maxLength, ErrorCode code, String field) {
        String value = raw == null ? null : raw.trim();
        if (value == null || value.isEmpty()) {
            throw new InvalidRequestException(code, field + " é obrigatório");
        }
        if (value.length() > maxLength) {
            throw new InvalidRequestException(code, field + " deve ter no máximo " + maxLength + " caracteres");
        }
        return value;
    }

    private static String normalizeOptionalText(String raw, int maxLength, ErrorCode code, String field) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new InvalidRequestException(code, field + " deve ter no máximo " + maxLength + " caracteres");
        }
        return value;
    }
}
