package com.wallet.api.infrastructure;

import com.wallet.api.domain.exception.ErrorCode;
import com.wallet.api.domain.exception.WalletException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class OracleErrorTranslatorTest {

    private final OracleErrorTranslator translator = new OracleErrorTranslator();

    @Test
    void extractsTheDetailOfARaiseApplicationError() {
        String message = "ORA-20002: INSUFFICIENT_FUNDS: balance 5.00 is lower than the requested debit of 50.00\n"
                + "ORA-06512: at \"WALLET_OWNER.PKG_WALLET\", line 123\n"
                + "ORA-06512: at line 1\n";

        assertThat(OracleErrorTranslator.extractDetail(message))
                .isEqualTo("balance 5.00 is lower than the requested debit of 50.00");
    }

    @Test
    void returnsNullWhenThereIsNoMessage() {
        assertThat(OracleErrorTranslator.extractDetail(null)).isNull();
        assertThat(OracleErrorTranslator.extractDetail("  ")).isNull();
    }

    @Test
    void keepsTheWholeMessageWhenThereIsNoSeparator() {
        assertThat(OracleErrorTranslator.extractDetail("ORA-20010: LEDGER_IMMUTABLE"))
                .isEqualTo("LEDGER_IMMUTABLE");
    }

    @Test
    void mapsBusinessCodesToDomainErrors() {
        assertThat(codeOf(oracleError(20001, "ORA-20001: WALLET_NOT_FOUND: wallet 99 does not exist")))
                .isEqualTo(ErrorCode.WALLET_NOT_FOUND);
        assertThat(codeOf(oracleError(20002, "ORA-20002: INSUFFICIENT_FUNDS: balance 0.00")))
                .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(codeOf(oracleError(20003, "ORA-20003: IDEMPOTENCY_CONFLICT: key reused")))
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(codeOf(oracleError(20010, "ORA-20010: LEDGER_IMMUTABLE: append-only")))
                .isEqualTo(ErrorCode.LEDGER_IMMUTABLE);
    }

    @Test
    void usesTheDatabaseDetailAsMessage() {
        WalletException exception = translator.translate(
                oracleError(20002, "ORA-20002: INSUFFICIENT_FUNDS: balance 5.00 is lower than 50.00"));

        assertThat(exception.detail()).isEqualTo("balance 5.00 is lower than 50.00");
    }

    @Test
    void mapsConcurrencyAndAvailabilityCodes() {
        assertThat(codeOf(oracleError(60, "ORA-00060: deadlock detected while waiting for resource")))
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(codeOf(oracleError(54, "ORA-00054: resource busy and acquire with NOWAIT specified")))
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(codeOf(oracleError(17002, "ORA-17002: I/O error")))
                .isEqualTo(ErrorCode.DATABASE_UNAVAILABLE);
    }

    @Test
    void treatsUniqueViolationAsAnIdempotencyRace() {
        assertThat(codeOf(oracleError(1, "ORA-00001: unique constraint (WALLET_OWNER.UK_WALLET_TRANSACTION_IDEMPOTENCY) violated")))
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void hidesUnexpectedDatabaseErrorsBehindAGenericMessage() {
        WalletException exception = translator.translate(oracleError(942, "ORA-00942: table or view does not exist"));

        assertThat(exception.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(exception.detail()).isEqualTo("erro inesperado no banco de dados (ORA-942)");
    }

    @Test
    void findsTheSqlExceptionInsideSpringWrappers() {
        SQLException rootCause = oracleError(20002, "ORA-20002: INSUFFICIENT_FUNDS: balance 0.00");
        RuntimeException springException = new UncategorizedSQLException(
                "call", "{call pkg_wallet.register_transaction()}", rootCause);

        assertThat(codeOf(springException)).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
    }

    @Test
    void fallsBackToInternalErrorWhenThereIsNoSqlException() {
        assertThat(codeOf(new IllegalStateException("boom"))).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    private ErrorCode codeOf(Throwable throwable) {
        return translator.translate(throwable).code();
    }

    private static SQLException oracleError(int code, String message) {
        return new SQLException(message, null, code);
    }
}
