package com.wallet.api.infrastructure;

import com.wallet.api.domain.exception.ErrorCode;
import com.wallet.api.domain.exception.WalletException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Translates Oracle errors into domain errors.
 *
 * <p>Business failures are raised by {@code PKG_WALLET} with {@code RAISE_APPLICATION_ERROR} in the
 * reserved range {@code -20001..-20099}. JDBC exposes the absolute value through
 * {@link SQLException#getErrorCode()}, so the mapping is a numeric lookup - no string matching of
 * driver messages, no leaked stack traces.
 */
@Component
public class OracleErrorTranslator {

    private static final Pattern ORA_PREFIX = Pattern.compile("^ORA-\\d{5}:\\s*");

    private static final Map<Integer, ErrorCode> BUSINESS_ERRORS = Map.ofEntries(
            Map.entry(20001, ErrorCode.WALLET_NOT_FOUND),
            Map.entry(20002, ErrorCode.INSUFFICIENT_FUNDS),
            Map.entry(20003, ErrorCode.IDEMPOTENCY_CONFLICT),
            Map.entry(20004, ErrorCode.INVALID_AMOUNT),
            Map.entry(20005, ErrorCode.INVALID_TRANSACTION_TYPE),
            Map.entry(20006, ErrorCode.INVALID_HOLDER),
            Map.entry(20008, ErrorCode.INVALID_IDEMPOTENCY_KEY),
            Map.entry(20009, ErrorCode.MISSING_IDEMPOTENCY_KEY),
            Map.entry(20010, ErrorCode.LEDGER_IMMUTABLE),
            Map.entry(20011, ErrorCode.INTERNAL_ERROR)
    );

    public WalletException translate(Throwable throwable) {
        SQLException sqlException = findSqlException(throwable);
        if (sqlException == null) {
            return new WalletException(ErrorCode.INTERNAL_ERROR, "erro inesperado", throwable);
        }

        int oracleCode = sqlException.getErrorCode();
        ErrorCode businessCode = BUSINESS_ERRORS.get(oracleCode);
        if (businessCode != null) {
            return new WalletException(businessCode, detailOrTitle(sqlException, businessCode), sqlException);
        }

        return switch (oracleCode) {
            // Unique violation: another session applied the same operation first. The package
            // normally catches this by re-reading under the row lock; this is the last resort.
            case 1 -> new WalletException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "a operação já foi aplicada para esta carteira", sqlException);
            // Resource busy (NOWAIT), deadlock, serialization failure: safe to retry.
            case 54, 60, 8177 -> new WalletException(ErrorCode.CONCURRENT_MODIFICATION,
                    "conflito de concorrência ao acessar a carteira, tente novamente", sqlException);
            // Connection level problems: the request is not the culprit.
            case 1017, 1033, 1034, 3113, 3114, 12170, 12514, 12541, 17002, 17008 ->
                    new WalletException(ErrorCode.DATABASE_UNAVAILABLE,
                            "banco de dados indisponível", sqlException);
            default -> new WalletException(ErrorCode.INTERNAL_ERROR,
                    "erro inesperado no banco de dados (ORA-" + oracleCode + ")", sqlException);
        };
    }

    /**
     * Extracts the human readable detail from a {@code RAISE_APPLICATION_ERROR} message, whose
     * first line looks like {@code ORA-20002: INSUFFICIENT_FUNDS: balance 5.00 is lower than ...}.
     */
    static String extractDetail(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String firstLine = message.lines().findFirst().orElse("").trim();
        String withoutPrefix = ORA_PREFIX.matcher(firstLine).replaceFirst("");
        int separator = withoutPrefix.indexOf(": ");
        String detail = separator >= 0 ? withoutPrefix.substring(separator + 2) : withoutPrefix;
        return detail.isBlank() ? null : detail.trim();
    }

    private static String detailOrTitle(SQLException exception, ErrorCode code) {
        String detail = extractDetail(exception.getMessage());
        return detail == null ? code.title() : detail;
    }

    private static SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }
}
