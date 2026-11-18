package com.wallet.api.infrastructure;

import com.wallet.api.config.DatabaseProperties;
import com.wallet.api.domain.exception.ErrorCode;
import com.wallet.api.domain.exception.WalletException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Types;

/**
 * Thin JDBC gateway to {@code PKG_WALLET}.
 *
 * <p>Plain {@link CallableStatement}s are used instead of {@code SimpleJdbcCall} on purpose: no
 * dependence on procedure metadata (which is slow and fragile on Oracle) and full control over
 * parameter types. The procedures commit inside, so this class never opens a Spring transaction.
 *
 * <p>Calls are schema qualified ({@code WALLET_OWNER.PKG_WALLET}) because the runtime user only
 * holds {@code EXECUTE}, not ownership.
 */
@Component
public class WalletProcedures {

    private final String callCreateWallet;
    private final String callRegisterTransaction;
    private final JdbcTemplate jdbcTemplate;
    private final OracleErrorTranslator errorTranslator;

    public WalletProcedures(JdbcTemplate jdbcTemplate,
                            OracleErrorTranslator errorTranslator,
                            DatabaseProperties databaseProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.errorTranslator = errorTranslator;
        String packageName = databaseProperties.getSchema() + ".pkg_wallet";
        this.callCreateWallet = "{call " + packageName + ".create_wallet(?, ?, ?)}";
        this.callRegisterTransaction = "{call " + packageName + ".register_transaction(?, ?, ?, ?, ?, ?, ?, ?, ?)}";
    }

    public long createWallet(String holderName, String holderDocument) {
        try {
            Long walletId = jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
                try (CallableStatement statement = connection.prepareCall(callCreateWallet)) {
                    statement.setString(1, holderName);
                    if (holderDocument == null) {
                        statement.setNull(2, Types.VARCHAR);
                    } else {
                        statement.setString(2, holderDocument);
                    }
                    statement.registerOutParameter(3, Types.NUMERIC);
                    statement.execute();
                    return statement.getLong(3);
                }
            });
            if (walletId == null) {
                throw new WalletException(ErrorCode.INTERNAL_ERROR, "carteira não retornou identificador");
            }
            return walletId;
        } catch (DataAccessException ex) {
            throw errorTranslator.translate(ex);
        }
    }

    public StoredProcedureResult registerTransaction(long walletId, String type, BigDecimal amount,
                                                     String idempotencyKey, String requestHash,
                                                     String description) {
        try {
            StoredProcedureResult result = jdbcTemplate.execute((ConnectionCallback<StoredProcedureResult>) connection -> {
                try (CallableStatement statement = connection.prepareCall(callRegisterTransaction)) {
                    statement.setLong(1, walletId);
                    statement.setString(2, type);
                    statement.setBigDecimal(3, amount);
                    statement.setString(4, idempotencyKey);
                    statement.setString(5, requestHash);
                    if (description == null) {
                        statement.setNull(6, Types.VARCHAR);
                    } else {
                        statement.setString(6, description);
                    }
                    statement.registerOutParameter(7, Types.NUMERIC);
                    statement.registerOutParameter(8, Types.NUMERIC);
                    statement.registerOutParameter(9, Types.NUMERIC);
                    statement.execute();

                    BigDecimal balanceAfter = statement.getBigDecimal(8);
                    boolean replayed = statement.getInt(9) == 1;
                    return new StoredProcedureResult(statement.getLong(7), balanceAfter, replayed);
                }
            });
            if (result == null) {
                throw new WalletException(ErrorCode.INTERNAL_ERROR, "procedimento não retornou resultado");
            }
            return result;
        } catch (DataAccessException ex) {
            throw errorTranslator.translate(ex);
        }
    }
}
