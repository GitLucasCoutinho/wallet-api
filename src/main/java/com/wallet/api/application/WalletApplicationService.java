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
import com.wallet.api.domain.exception.WalletException;
import com.wallet.api.domain.exception.WalletNotFoundException;
import com.wallet.api.observability.WalletMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
 *
 * <p>Observability: each use case is measured (outcome and duration) and the wallet id travels in
 * the MDC, so every log line of one request can be correlated by {@code walletId} plus the
 * {@code requestId} set by the filter.
 */
@Service
public class WalletApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WalletApplicationService.class);

    private static final int MAX_HOLDER_NAME_LENGTH = 120;
    private static final int MAX_HOLDER_DOCUMENT_LENGTH = 32;
    private static final int MAX_DESCRIPTION_LENGTH = 255;

    private static final String CREATE_WALLET = "create_wallet";
    private static final String REGISTER_TRANSACTION = "register_transaction";

    private final WalletRepository repository;
    private final PaginationProperties pagination;
    private final WalletMetrics metrics;

    public WalletApplicationService(WalletRepository repository, PaginationProperties pagination,
                                    WalletMetrics metrics) {
        this.repository = repository;
        this.pagination = pagination;
        this.metrics = metrics;
    }

    public Wallet createWallet(String holderName, String holderDocument) {
        Timer.Sample sample = metrics.start();
        try {
            String name = normalizeRequiredText(holderName, MAX_HOLDER_NAME_LENGTH,
                    ErrorCode.INVALID_HOLDER, "nome do titular");
            String document = normalizeOptionalText(holderDocument, MAX_HOLDER_DOCUMENT_LENGTH,
                    ErrorCode.INVALID_HOLDER, "documento do titular");
            Wallet wallet = repository.create(name, document);
            metrics.applied(CREATE_WALLET, null, sample);
            log.info("wallet created walletId={} currency={}", wallet.id(), wallet.currency());
            return wallet;
        } catch (WalletException exception) {
            metrics.rejected(CREATE_WALLET, null, exception.code(), sample);
            throw exception;
        } catch (RuntimeException exception) {
            metrics.failed(CREATE_WALLET, null, sample);
            throw exception;
        }
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
        Timer.Sample sample = metrics.start();
        try (MDC.MDCCloseable wallet = MDC.putCloseable("walletId", Long.toString(walletId));
             MDC.MDCCloseable key = MDC.putCloseable("idempotencyKey",
                     idempotencyKey == null || idempotencyKey.isBlank() ? "-" : idempotencyKey)) {

            if (type == null) {
                throw new InvalidRequestException(ErrorCode.INVALID_TRANSACTION_TYPE, "tipo é obrigatório");
            }
            BigDecimal normalizedAmount = Amounts.normalize(amount);
            String validKey = IdempotencyKeys.requireValid(idempotencyKey);
            String normalizedDescription = normalizeOptionalText(description, MAX_DESCRIPTION_LENGTH,
                    ErrorCode.INVALID_DESCRIPTION, "descrição");

            String fingerprint = RequestFingerprint.of(type, normalizedAmount, normalizedDescription);
            RegisterTransactionCommand command = new RegisterTransactionCommand(
                    type, normalizedAmount, normalizedDescription, validKey, fingerprint);

            TransactionReceipt receipt = repository.register(walletId, command);

            if (receipt.replayed()) {
                metrics.replayed(REGISTER_TRANSACTION, type, sample);
                log.info("operation replayed transactionId={} type={} amount={}",
                        receipt.transactionId(), type, normalizedAmount);
            } else {
                metrics.applied(REGISTER_TRANSACTION, type, sample);
                log.info("operation applied transactionId={} type={} amount={} balanceAfter={}",
                        receipt.transactionId(), type, normalizedAmount, receipt.balanceAfter());
            }
            return receipt;
        } catch (WalletException exception) {
            metrics.rejected(REGISTER_TRANSACTION, type, exception.code(), sample);
            log.warn("operation rejected code={} type={} amount={} detail={}",
                    exception.code(), type, amount, exception.detail());
            throw exception;
        } catch (RuntimeException exception) {
            metrics.failed(REGISTER_TRANSACTION, type, sample);
            log.error("operation failed type={} amount={}", type, amount, exception);
            throw exception;
        }
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
