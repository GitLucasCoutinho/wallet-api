package com.wallet.api.infrastructure;

import com.wallet.api.config.DatabaseProperties;
import com.wallet.api.domain.Amounts;
import com.wallet.api.domain.RegisterTransactionCommand;
import com.wallet.api.domain.TransactionFilter;
import com.wallet.api.domain.TransactionReceipt;
import com.wallet.api.domain.TransactionType;
import com.wallet.api.domain.Wallet;
import com.wallet.api.domain.WalletRepository;
import com.wallet.api.domain.WalletTransaction;
import com.wallet.api.domain.exception.ErrorCode;
import com.wallet.api.domain.exception.WalletException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JDBC adapter for the wallet ledger.
 *
 * <p>Reads use plain SQL (the runtime user only has SELECT); writes are delegated to
 * {@link WalletProcedures}. No ORM: there is no entity state to flush, no lazy loading and no
 * hidden transaction - which is exactly what we want when the database owns consistency.
 *
 * <p>Table names are schema qualified because {@code WALLET_APP} only holds object grants.
 */
@Repository
public class JdbcWalletRepository implements WalletRepository {

    private static final String WALLET_COLUMNS =
            "id, holder_name, holder_document, currency, balance, created_at";

    private static final String TRANSACTION_COLUMNS =
            "id, wallet_id, type, amount, balance_before, balance_after, idempotency_key, description, occurred_at";

    private static final RowMapper<Wallet> WALLET_MAPPER = (rs, rowNum) -> new Wallet(
            rs.getLong("id"),
            rs.getString("holder_name"),
            rs.getString("holder_document"),
            rs.getString("currency"),
            Amounts.scale2(rs.getBigDecimal("balance")),
            readOffsetDateTime(rs, "created_at")
    );

    private static final RowMapper<WalletTransaction> TRANSACTION_MAPPER = (rs, rowNum) -> new WalletTransaction(
            rs.getLong("id"),
            rs.getLong("wallet_id"),
            TransactionType.from(rs.getString("type")),
            Amounts.scale2(rs.getBigDecimal("amount")),
            Amounts.scale2(rs.getBigDecimal("balance_before")),
            Amounts.scale2(rs.getBigDecimal("balance_after")),
            rs.getString("idempotency_key"),
            rs.getString("description"),
            readOffsetDateTime(rs, "occurred_at")
    );

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final WalletProcedures procedures;
    private final String walletTable;
    private final String transactionTable;

    public JdbcWalletRepository(JdbcTemplate jdbcTemplate,
                                NamedParameterJdbcTemplate namedJdbcTemplate,
                                WalletProcedures procedures,
                                DatabaseProperties databaseProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.procedures = procedures;
        String schema = databaseProperties.getSchema();
        this.walletTable = schema + ".wallet";
        this.transactionTable = schema + ".wallet_transaction";
    }

    @Override
    public Wallet create(String holderName, String holderDocument) {
        long walletId = procedures.createWallet(holderName, holderDocument);
        return findById(walletId).orElseThrow(() -> new WalletException(ErrorCode.INTERNAL_ERROR,
                "carteira " + walletId + " não encontrada após a criação"));
    }

    @Override
    public Optional<Wallet> findById(long walletId) {
        List<Wallet> wallets = jdbcTemplate.query(
                "SELECT " + WALLET_COLUMNS + " FROM " + walletTable + " WHERE id = ?",
                WALLET_MAPPER,
                walletId);
        return wallets.stream().findFirst();
    }

    @Override
    public TransactionReceipt register(long walletId, RegisterTransactionCommand command) {
        StoredProcedureResult result = procedures.registerTransaction(
                walletId,
                command.type().name(),
                command.amount(),
                command.idempotencyKey(),
                command.requestHash(),
                command.description());

        OffsetDateTime occurredAt = jdbcTemplate.queryForObject(
                "SELECT occurred_at FROM " + transactionTable + " WHERE id = ?",
                OffsetDateTime.class,
                result.transactionId());

        return new TransactionReceipt(
                walletId,
                result.transactionId(),
                command.type(),
                command.amount(),
                Amounts.scale2(result.balanceAfter()),
                occurredAt,
                result.replayed());
    }

    @Override
    public List<WalletTransaction> findTransactions(long walletId, TransactionFilter filter,
                                                    int offset, int limit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("walletId", walletId);
        String where = whereClause(filter, parameters);
        String sql = "SELECT " + TRANSACTION_COLUMNS + " FROM " + transactionTable + where
                + " ORDER BY occurred_at DESC, id DESC"
                + " OFFSET :offset ROWS FETCH NEXT :pageSize ROWS ONLY";
        parameters.addValue("offset", offset);
        parameters.addValue("pageSize", limit);
        return namedJdbcTemplate.query(sql, parameters, TRANSACTION_MAPPER);
    }

    @Override
    public long countTransactions(long walletId, TransactionFilter filter) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("walletId", walletId);
        String where = whereClause(filter, parameters);
        Long total = namedJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + transactionTable + where, parameters, Long.class);
        return total == null ? 0L : total;
    }

    private static String whereClause(TransactionFilter filter, MapSqlParameterSource parameters) {
        StringBuilder where = new StringBuilder(" WHERE wallet_id = :walletId");
        if (filter.from() != null) {
            where.append(" AND occurred_at >= :from");
            parameters.addValue("from", filter.from());
        }
        if (filter.to() != null) {
            where.append(" AND occurred_at <= :to");
            parameters.addValue("to", filter.to());
        }
        if (filter.type() != null) {
            where.append(" AND type = :type");
            parameters.addValue("type", filter.type().name());
        }
        return where.toString();
    }

    private static OffsetDateTime readOffsetDateTime(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class);
    }
}
