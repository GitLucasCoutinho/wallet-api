package com.wallet.api.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.api.web.ApiErrorResponse;
import com.wallet.api.web.dto.TransactionReceiptResponse;
import com.wallet.api.web.dto.WalletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * End-to-end tests against a real Oracle Database Free container.
 *
 * <p>The container starts with the same bootstrap script used by docker-compose (three schemas,
 * least privilege) and the application applies the Flyway migrations on startup, so what runs here
 * is the production path: HTTP -> Spring -> PKG_WALLET -> Oracle.
 *
 * <p>They are part of {@code mvn verify} (failsafe) and skipped automatically when Docker is
 * unavailable ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class WalletApiIT {

    private static final String ORACLE_IMAGE =
            System.getProperty("wallet.it.image", "gvenzl/oracle-free:23.26.4-slim-faststart");
    private static final String SYS_PASSWORD = "OracleSys123456";
    private static final String OWNER_PASSWORD = "WalletOwner123456";
    private static final String AUDIT_PASSWORD = "WalletAudit123456";
    private static final String APP_PASSWORD = "WalletApp123456";
    private static final String API_KEY = "it-api-key-0001";
    private static final String BOOTSTRAP_SCRIPT = "db/bootstrap/01_schemas.sh";

    @Container
    static final GenericContainer<?> ORACLE = new GenericContainer<>(DockerImageName.parse(ORACLE_IMAGE))
            .withEnv("ORACLE_PASSWORD", SYS_PASSWORD)
            .withEnv("WALLET_OWNER_PASSWORD", OWNER_PASSWORD)
            .withEnv("WALLET_AUDIT_PASSWORD", AUDIT_PASSWORD)
            .withEnv("WALLET_APP_PASSWORD", APP_PASSWORD)
            .withExposedPorts(1521)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(bootstrapScript(), 0755),
                    "/container-entrypoint-initdb.d/01_schemas.sh")
            .waitingFor(Wait.forLogMessage(".*DATABASE IS READY TO USE.*", 1))
            .withStartupTimeout(Duration.ofMinutes(8));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", WalletApiIT::jdbcUrl);
        registry.add("spring.datasource.username", () -> "WALLET_APP");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
        registry.add("spring.flyway.url", WalletApiIT::jdbcUrl);
        registry.add("spring.flyway.user", () -> "WALLET_OWNER");
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("spring.flyway.connect-retries", () -> "10");
        registry.add("wallet.security.api-keys", () -> API_KEY);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------------------------------------------------------------------------------
    // Wallet creation and balance
    // ---------------------------------------------------------------------------------

    @Test
    void createsWalletWithZeroBalanceAndLocationHeader() {
        ResponseEntity<WalletResponse> response = createWallet("Ana Souza", "12345678900");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        WalletResponse wallet = response.getBody();
        assertThat(wallet).isNotNull();
        assertThat(wallet.id()).isPositive();
        assertThat(wallet.currency()).isEqualTo("BRL");
        assertThat(wallet.balance()).isEqualByComparingTo("0.00");
        assertThat(wallet.createdAt()).isNotNull();
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().toString())
                .endsWith("/api/v1/wallets/" + wallet.id());
    }

    @Test
    void creditAndDebitUpdateTheBalance() {
        long walletId = newWallet("Balance");

        TransactionReceiptResponse credit = apply(walletId, "CREDIT", "150.75", uniqueKey("credit")).getBody();
        assertThat(credit).isNotNull();
        assertThat(credit.replayed()).isFalse();
        assertThat(credit.balanceAfter()).isEqualByComparingTo("150.75");

        TransactionReceiptResponse debit = apply(walletId, "DEBIT", "50.25", uniqueKey("debit")).getBody();
        assertThat(debit).isNotNull();
        assertThat(debit.balanceAfter()).isEqualByComparingTo("100.50");

        ResponseEntity<WalletResponse> wallet = getWallet(walletId);
        assertThat(wallet.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wallet.getBody().balance()).isEqualByComparingTo("100.50");
        assertThat(statementCount(walletId)).isEqualTo(2);
        assertThat(auditCount(walletId)).isEqualTo(2);
    }

    @Test
    void debitAboveBalanceIsRejectedWith422AndKeepsTheBalance() {
        long walletId = newWallet("NoFunds");
        apply(walletId, "CREDIT", "10.00", uniqueKey("credit"));

        ResponseEntity<ApiErrorResponse> response =
                applyExpectingError(walletId, "DEBIT", "10.01", uniqueKey("debit"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(response.getBody().message()).contains("10.00");

        assertThat(balanceOf(walletId)).isEqualByComparingTo("10.00");
        assertThat(statementCount(walletId)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------
    // Idempotency
    // ---------------------------------------------------------------------------------

    @Test
    void repeatedRequestWithSameKeyReturnsTheOriginalResult() throws Exception {
        long walletId = newWallet("Retry");
        String key = uniqueKey("retry");

        ResponseEntity<TransactionReceiptResponse> first = apply(walletId, "CREDIT", "25.00", key);
        ResponseEntity<TransactionReceiptResponse> second = apply(walletId, "CREDIT", "25.00", key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().transactionId()).isEqualTo(first.getBody().transactionId());
        assertThat(second.getBody().replayed()).isTrue();
        assertThat(second.getBody().balanceAfter()).isEqualByComparingTo("25.00");

        assertThat(balanceOf(walletId)).isEqualByComparingTo("25.00");
        assertThat(statementCount(walletId)).isEqualTo(1);
        assertThat(auditCount(walletId)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentPayloadIsAConflict() {
        long walletId = newWallet("Conflict");
        String key = uniqueKey("conflict");
        apply(walletId, "CREDIT", "25.00", key);

        ResponseEntity<ApiErrorResponse> response =
                applyExpectingError(walletId, "CREDIT", "26.00", key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(balanceOf(walletId)).isEqualByComparingTo("25.00");
        assertThat(statementCount(walletId)).isEqualTo(1);
    }

    @Test
    void sameKeyCanBeUsedByDifferentWallets() {
        long first = newWallet("WalletA");
        long second = newWallet("WalletB");
        String sharedKey = uniqueKey("shared");

        ResponseEntity<TransactionReceiptResponse> responseA = apply(first, "CREDIT", "10.00", sharedKey);
        ResponseEntity<TransactionReceiptResponse> responseB = apply(second, "CREDIT", "10.00", sharedKey);

        assertThat(responseA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(responseB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balanceOf(first)).isEqualByComparingTo("10.00");
        assertThat(balanceOf(second)).isEqualByComparingTo("10.00");
    }

    // ---------------------------------------------------------------------------------
    // Statement
    // ---------------------------------------------------------------------------------

    @Test
    void statementIsPagedAndFiltered() throws Exception {
        long walletId = newWallet("Statement");
        apply(walletId, "CREDIT", "10.00", uniqueKey("c1"));
        apply(walletId, "CREDIT", "20.00", uniqueKey("c2"));
        apply(walletId, "DEBIT", "5.00", uniqueKey("d1"));

        JsonNode firstPage = json(rest.exchange(
                "/api/v1/wallets/{id}/transactions?page=0&size=2", HttpMethod.GET,
                new HttpEntity<>(headers(null)), String.class, walletId).getBody());

        assertThat(firstPage.get("content")).hasSize(2);
        assertThat(firstPage.get("totalElements").asLong()).isEqualTo(3);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
        assertThat(firstPage.get("hasNext").asBoolean()).isTrue();
        assertThat(firstPage.get("hasPrevious").asBoolean()).isFalse();
        // Newest first.
        assertThat(firstPage.get("content").get(0).get("type").asText()).isEqualTo("DEBIT");

        JsonNode secondPage = json(rest.exchange(
                "/api/v1/wallets/{id}/transactions?page=1&size=2", HttpMethod.GET,
                new HttpEntity<>(headers(null)), String.class, walletId).getBody());
        assertThat(secondPage.get("content")).hasSize(1);
        assertThat(secondPage.get("hasNext").asBoolean()).isFalse();

        JsonNode credits = json(rest.exchange(
                "/api/v1/wallets/{id}/transactions?type=CREDIT", HttpMethod.GET,
                new HttpEntity<>(headers(null)), String.class, walletId).getBody());
        assertThat(credits.get("totalElements").asLong()).isEqualTo(2);

        String future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString();
        JsonNode empty = json(rest.exchange(
                "/api/v1/wallets/{id}/transactions?from={from}", HttpMethod.GET,
                new HttpEntity<>(headers(null)), String.class, walletId, future).getBody());
        assertThat(empty.get("totalElements").asLong()).isZero();
    }

    @Test
    void statementUsesTwoDecimalsAndKeepsTheBalanceChain() throws Exception {
        long walletId = newWallet("Chain");
        apply(walletId, "CREDIT", "0.10", uniqueKey("small"));
        apply(walletId, "CREDIT", "0.20", uniqueKey("small2"));

        JsonNode page = json(rest.exchange(
                "/api/v1/wallets/{id}/transactions", HttpMethod.GET,
                new HttpEntity<>(headers(null)), String.class, walletId).getBody());
        JsonNode newest = page.get("content").get(0);
        JsonNode oldest = page.get("content").get(1);

        assertThat(newest.get("balanceBefore").decimalValue()).isEqualByComparingTo("0.10");
        assertThat(newest.get("balanceAfter").decimalValue()).isEqualByComparingTo("0.30");
        assertThat(oldest.get("balanceBefore").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(oldest.get("balanceAfter").decimalValue()).isEqualByComparingTo("0.10");
        assertThat(balanceOf(walletId)).isEqualByComparingTo("0.30");
    }

    // ---------------------------------------------------------------------------------
    // Database enforced invariants
    // ---------------------------------------------------------------------------------

    @Test
    void statementCannotBeUpdatedOrDeletedNotEvenByTheOwner() throws Exception {
        long walletId = newWallet("Immutable");
        long transactionId = apply(walletId, "CREDIT", "10.00", uniqueKey("imm")).getBody().transactionId();

        try (Connection owner = connection("WALLET_OWNER", OWNER_PASSWORD)) {
            assertThat(sqlErrorFrom(owner, "UPDATE wallet_transaction SET amount = 1 WHERE id = " + transactionId))
                    .isEqualTo(20010);
            assertThat(sqlErrorFrom(owner, "DELETE FROM wallet_transaction WHERE id = " + transactionId))
                    .isEqualTo(20010);
        }

        assertThat(statementCount(walletId)).isEqualTo(1);
    }

    @Test
    void applicationUserHasNoDmlPrivileges() throws Exception {
        long walletId = newWallet("Privileges");

        try (Connection app = connection("WALLET_APP", APP_PASSWORD)) {
            // Oracle 23ai reports missing object privileges as ORA-41900 instead of ORA-01031.
            assertThat(sqlErrorFrom(app, "UPDATE WALLET_OWNER.WALLET SET balance = 1 WHERE id = " + walletId))
                    .isIn(1031, 41900);
            assertThat(sqlErrorFrom(app, "DELETE FROM WALLET_OWNER.WALLET_TRANSACTION WHERE wallet_id = " + walletId))
                    .isIn(1031, 41900);
            assertThat(sqlErrorFrom(app, "INSERT INTO WALLET_OWNER.WALLET (id, holder_name, balance) VALUES (999999, 'x', 0)"))
                    .isIn(1031, 41900);
            assertThat(sqlErrorFrom(app, "SELECT COUNT(*) FROM wallet_audit.balance_audit")).isEqualTo(942);
        }
    }

    @Test
    void everyBalanceChangeIsWrittenToTheAuditTrail() throws Exception {
        long walletId = newWallet("Audit");
        apply(walletId, "CREDIT", "100.00", uniqueKey("a1"));
        apply(walletId, "DEBIT", "40.00", uniqueKey("a2"));

        try (Connection audit = connection("WALLET_AUDIT", AUDIT_PASSWORD);
             PreparedStatement statement = audit.prepareStatement("""
                     SELECT COUNT(*) AS total,
                            SUM(delta) AS delta_sum,
                            COUNT(transaction_id) AS with_transaction
                       FROM wallet_audit.balance_audit
                      WHERE wallet_id = ?
                     """)) {
            statement.setLong(1, walletId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getLong("total")).isEqualTo(2);
                assertThat(resultSet.getBigDecimal("delta_sum")).isEqualByComparingTo("60.00");
                assertThat(resultSet.getLong("with_transaction")).isEqualTo(2);
            }
        }
    }

    @Test
    void manualBalanceChangeIsAuditedWithoutATransactionId() throws Exception {
        long walletId = newWallet("Manual");
        apply(walletId, "CREDIT", "10.00", uniqueKey("m1"));

        try (Connection owner = connection("WALLET_OWNER", OWNER_PASSWORD);
             PreparedStatement statement = owner.prepareStatement(
                     "UPDATE wallet SET balance = balance + 5.00, updated_at = systimestamp WHERE id = ?")) {
            statement.setLong(1, walletId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        try (Connection audit = connection("WALLET_AUDIT", AUDIT_PASSWORD);
             PreparedStatement statement = audit.prepareStatement("""
                     SELECT transaction_id, previous_balance, new_balance
                       FROM wallet_audit.balance_audit
                      WHERE wallet_id = ?
                      ORDER BY id DESC
                      FETCH FIRST 1 ROWS ONLY
                     """)) {
            statement.setLong(1, walletId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getObject("transaction_id")).isNull();
                assertThat(resultSet.getBigDecimal("previous_balance")).isEqualByComparingTo("10.00");
                assertThat(resultSet.getBigDecimal("new_balance")).isEqualByComparingTo("15.00");
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Concurrency
    // ---------------------------------------------------------------------------------

    @Test
    void concurrentDebitsNeverMakeTheBalanceNegative() throws Exception {
        long walletId = newWallet("Concurrent");
        apply(walletId, "CREDIT", "100.00", uniqueKey("seed"));

        int attempts = 20;
        List<ResponseEntity<String>> responses = runConcurrently(attempts, index -> rest.exchange(
                "/api/v1/wallets/{id}/transactions", HttpMethod.POST,
                new HttpEntity<>(operationBody("DEBIT", "10.00", "concurrent debit"), headers("debit-%04d".formatted(index))),
                String.class, walletId));

        long applied = countStatuses(responses, HttpStatus.CREATED);
        long rejected = countStatuses(responses, HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(applied).isEqualTo(10);
        assertThat(rejected).isEqualTo(10);
        assertThat(balanceOf(walletId)).isEqualByComparingTo("0.00");
        assertThat(statementCount(walletId)).isEqualTo(11);
        assertThat(auditCount(walletId)).isEqualTo(11);
    }

    @Test
    void concurrentRetriesOfTheSameOperationAreAppliedOnlyOnce() throws Exception {
        long walletId = newWallet("ConcurrentIdempotent");
        String key = uniqueKey("same");
        int attempts = 8;

        List<ResponseEntity<String>> responses = runConcurrently(attempts, index -> rest.exchange(
                "/api/v1/wallets/{id}/transactions", HttpMethod.POST,
                new HttpEntity<>(operationBody("CREDIT", "50.00", "concurrent credit"), headers(key)),
                String.class, walletId));

        assertThat(countStatuses(responses, HttpStatus.CREATED)).isEqualTo(1);
        assertThat(countStatuses(responses, HttpStatus.OK)).isEqualTo(attempts - 1);
        assertThat(balanceOf(walletId)).isEqualByComparingTo("50.00");
        assertThat(statementCount(walletId)).isEqualTo(1);

        String transactionId = json(responses.get(0).getBody()).get("transactionId").asText();
        long distinctIds = responses.stream()
                .map(ResponseEntity::getBody)
                .map(this::transactionIdOf)
                .distinct()
                .count();
        assertThat(distinctIds).isEqualTo(1);
        assertThat(transactionIdOf(responses.get(0).getBody())).isEqualTo(transactionId);
    }

    // ---------------------------------------------------------------------------------
    // API contract
    // ---------------------------------------------------------------------------------

    @Test
    void unknownWalletReturns404() {
        ResponseEntity<ApiErrorResponse> response = rest.exchange(
                "/api/v1/wallets/{id}", HttpMethod.GET,
                new HttpEntity<>(headers(null)), ApiErrorResponse.class, 987654321L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    void requestsWithoutApiKeyAreRejected() {
        ResponseEntity<ApiErrorResponse> response = rest.exchange(
                "/api/v1/wallets/{id}", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), ApiErrorResponse.class, 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void invalidPayloadIsRejectedWith400() {
        ResponseEntity<ApiErrorResponse> blankHolder = rest.exchange(
                "/api/v1/wallets", HttpMethod.POST,
                new HttpEntity<>(Map.of("holderName", " "), headers(null)), ApiErrorResponse.class);
        assertThat(blankHolder.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blankHolder.getBody().code()).isEqualTo("VALIDATION_ERROR");

        long walletId = newWallet("Validation");
        assertThat(applyExpectingError(walletId, "CREDIT", "0", uniqueKey("v1")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(applyExpectingError(walletId, "CREDIT", "10.005", uniqueKey("v2")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(applyExpectingError(walletId, "CREDIT", "-5.00", uniqueKey("v3")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(applyExpectingError(walletId, "TRANSFER", "5.00", uniqueKey("v4")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statementCount(walletId)).isZero();
    }

    @Test
    void missingIdempotencyKeyIsRejectedWith400() {
        long walletId = newWallet("NoKey");

        ResponseEntity<ApiErrorResponse> response = applyExpectingError(walletId, "CREDIT", "10.00", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
        assertThat(statementCount(walletId)).isZero();
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private long newWallet(String holderName) {
        ResponseEntity<WalletResponse> response = createWallet(holderName, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().id();
    }

    private ResponseEntity<WalletResponse> createWallet(String holderName, String holderDocument) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("holderName", holderName);
        if (holderDocument != null) {
            body.put("holderDocument", holderDocument);
        }
        return rest.exchange("/api/v1/wallets", HttpMethod.POST,
                new HttpEntity<>(body, headers(null)), WalletResponse.class);
    }

    private ResponseEntity<WalletResponse> getWallet(long walletId) {
        return rest.exchange("/api/v1/wallets/{id}", HttpMethod.GET,
                new HttpEntity<>(headers(null)), WalletResponse.class, walletId);
    }

    private ResponseEntity<TransactionReceiptResponse> apply(long walletId, String type, String amount, String key) {
        return rest.exchange("/api/v1/wallets/{walletId}/transactions", HttpMethod.POST,
                new HttpEntity<>(operationBody(type, amount, "pix"), headers(key)),
                TransactionReceiptResponse.class, walletId);
    }

    private ResponseEntity<ApiErrorResponse> applyExpectingError(long walletId, String type, String amount, String key) {
        return rest.exchange("/api/v1/wallets/{walletId}/transactions", HttpMethod.POST,
                new HttpEntity<>(operationBody(type, amount, "pix"), headers(key)),
                ApiErrorResponse.class, walletId);
    }

    private Map<String, Object> operationBody(String type, String amount, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type.toUpperCase(Locale.ROOT));
        body.put("amount", new BigDecimal(amount));
        if (description != null) {
            body.put("description", description);
        }
        return body;
    }

    private HttpHeaders headers(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        headers.set("X-API-Key", API_KEY);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private String transactionIdOf(String body) {
        try {
            return json(body).get("transactionId").asText();
        } catch (Exception exception) {
            throw new IllegalStateException("could not read transactionId from " + body, exception);
        }
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private static String jdbcUrl() {
        if (!ORACLE.isRunning()) {
            ORACLE.start();
        }
        return "jdbc:oracle:thin:@//" + ORACLE.getHost() + ":" + ORACLE.getMappedPort(1521) + "/FREEPDB1";
    }

    private static String bootstrapScript() {
        Path path = Path.of(BOOTSTRAP_SCRIPT).toAbsolutePath();
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("Bootstrap script not found at " + path
                    + ". Run the integration tests from the project root.");
        }
        return path.toString();
    }

    private Connection connection(String user, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), user, password);
    }

    private static int sqlErrorFrom(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            fail("expected a SQL error for: " + sql);
            return 0;
        } catch (SQLException exception) {
            return exception.getErrorCode();
        }
    }

    private BigDecimal balanceOf(long walletId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM WALLET_OWNER.WALLET WHERE id = ?", BigDecimal.class, walletId);
    }

    private long statementCount(long walletId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM WALLET_OWNER.WALLET_TRANSACTION WHERE wallet_id = ?", Long.class, walletId);
        return count == null ? 0 : count;
    }

    private long auditCount(long walletId) {
        try (Connection connection = connection("WALLET_AUDIT", AUDIT_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM wallet_audit.balance_audit WHERE wallet_id = ?")) {
            statement.setLong(1, walletId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("could not read the audit trail", exception);
        }
    }

    private <T> List<ResponseEntity<T>> runConcurrently(int threads, IntFunction<ResponseEntity<T>> action)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<T>>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return action.apply(index);
                }));
            }
            startGate.countDown();
            List<ResponseEntity<T>> results = new ArrayList<>(threads);
            for (Future<ResponseEntity<T>> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long countStatuses(List<ResponseEntity<String>> responses, HttpStatus status) {
        return responses.stream().filter(response -> response.getStatusCode() == status).count();
    }

    private static String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
