package com.wallet.api.web;

import com.wallet.api.application.WalletApplicationService;
import com.wallet.api.config.ApiSecurityProperties;
import com.wallet.api.config.PaginationProperties;
import com.wallet.api.domain.TransactionFilter;
import com.wallet.api.domain.TransactionReceipt;
import com.wallet.api.domain.TransactionSearchResult;
import com.wallet.api.domain.TransactionType;
import com.wallet.api.domain.Wallet;
import com.wallet.api.domain.WalletTransaction;
import com.wallet.api.domain.exception.ErrorCode;
import com.wallet.api.domain.exception.InvalidRequestException;
import com.wallet.api.domain.exception.WalletNotFoundException;
import com.wallet.api.infrastructure.OracleErrorTranslator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WalletController.class)
@EnableConfigurationProperties({ApiSecurityProperties.class, PaginationProperties.class})
@Import({ApiKeyVerifier.class, ApiErrorFactory.class, ErrorResponseWriter.class, OracleErrorTranslator.class})
@TestPropertySource(properties = {
        "wallet.security.api-keys=test-api-key,second-key",
        "wallet.pagination.default-page-size=20",
        "wallet.pagination.max-page-size=100",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class WalletControllerTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY = "test-api-key";
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 1, 2, 10, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WalletApplicationService service;

    @Test
    void createWalletReturns201WithLocationAndBody() throws Exception {
        when(service.createWallet("Ana Souza", null))
                .thenReturn(new Wallet(1000L, "Ana Souza", null, "BRL", new BigDecimal("0.00"), NOW));

        mockMvc.perform(post("/api/v1/wallets")
                        .header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holderName\":\"Ana Souza\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/wallets/1000"))
                .andExpect(jsonPath("$.id").value(1000))
                .andExpect(jsonPath("$.holderName").value("Ana Souza"))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(content().json("{\"balance\":0.00}", false));
    }

    @Test
    void createWalletReturns400WithFieldViolationsWhenTheHolderIsBlank() throws Exception {
        mockMvc.perform(post("/api/v1/wallets")
                        .header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holderName\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations", hasSize(1)))
                .andExpect(jsonPath("$.violations[0].field").value("holderName"))
                .andExpect(jsonPath("$.path").value("/api/v1/wallets"));
    }

    @Test
    void getWalletReturns404WithTheBusinessCode() throws Exception {
        when(service.getWallet(42L)).thenThrow(new WalletNotFoundException(42L));

        mockMvc.perform(get("/api/v1/wallets/42").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Carteira 42 não encontrada"))
                .andExpect(jsonPath("$.type").value("https://wallet-api/errors/wallet-not-found"));
    }

    @Test
    void listTransactionsAppliesFiltersAndPagination() throws Exception {
        WalletTransaction transaction = new WalletTransaction(11L, 7L, TransactionType.CREDIT,
                new BigDecimal("10.00"), new BigDecimal("0.00"), new BigDecimal("10.00"),
                "key-000001", "pix", NOW);
        when(service.searchTransactions(eq(7L), any(TransactionFilter.class), eq(1), eq(5)))
                .thenReturn(new TransactionSearchResult(List.of(transaction), 7L, 1, 5));

        mockMvc.perform(get("/api/v1/wallets/7/transactions")
                        .param("page", "1")
                        .param("size", "5")
                        .param("type", "credit")
                        .param("from", "2026-01-01T00:00:00Z")
                        .header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].type").value("CREDIT"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasPrevious").value(true))
                .andExpect(content().json("{\"content\":[{\"amount\":10.00,\"balanceAfter\":10.00}]}", false));

        verify(service).searchTransactions(eq(7L),
                eq(new TransactionFilter(
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"), null, TransactionType.CREDIT)),
                eq(1), eq(5));
    }

    @Test
    void listTransactionsUsesTheConfiguredDefaultPageSize() throws Exception {
        when(service.searchTransactions(eq(7L), any(TransactionFilter.class), eq(0), eq(20)))
                .thenReturn(new TransactionSearchResult(List.of(), 0L, 0, 20));

        mockMvc.perform(get("/api/v1/wallets/7/transactions").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void listTransactionsReturns400WhenTheServiceRejectsThePagination() throws Exception {
        when(service.searchTransactions(anyLong(), any(TransactionFilter.class), anyInt(), anyInt()))
                .thenThrow(new InvalidRequestException(ErrorCode.INVALID_PARAMETER,
                        "size deve estar entre 1 e 100"));

        mockMvc.perform(get("/api/v1/wallets/7/transactions")
                        .param("size", "1000")
                        .header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("size deve estar entre 1 e 100"));
    }

    @Test
    void registerTransactionReturns201WhenTheOperationIsApplied() throws Exception {
        when(service.registerTransaction(eq(7L), eq(TransactionType.CREDIT), any(BigDecimal.class),
                anyString(), anyString()))
                .thenReturn(new TransactionReceipt(7L, 555L, TransactionType.CREDIT,
                        new BigDecimal("10.00"), new BigDecimal("10.00"), NOW, false));

        mockMvc.perform(post("/api/v1/wallets/7/transactions")
                        .header(API_KEY_HEADER, API_KEY)
                        .header("Idempotency-Key", "order-000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"credit\",\"amount\":10.00,\"description\":\"pix\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.transactionId").value(555))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(content().json("{\"balanceAfter\":10.00}", false));
    }

    @Test
    void registerTransactionReturns200WhenTheOperationIsReplayed() throws Exception {
        when(service.registerTransaction(eq(7L), eq(TransactionType.DEBIT), any(BigDecimal.class),
                isNull(), anyString()))
                .thenReturn(new TransactionReceipt(7L, 555L, TransactionType.DEBIT,
                        new BigDecimal("5.00"), new BigDecimal("5.00"), NOW, true));

        mockMvc.perform(post("/api/v1/wallets/7/transactions")
                        .header(API_KEY_HEADER, API_KEY)
                        .header("Idempotency-Key", "order-000002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DEBIT\",\"amount\":5.00}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void registerTransactionReturns400WhenTheIdempotencyKeyIsMissing() throws Exception {
        when(service.registerTransaction(anyLong(), any(), any(), any(), isNull()))
                .thenThrow(new InvalidRequestException(ErrorCode.MISSING_IDEMPOTENCY_KEY,
                        "cabeçalho Idempotency-Key é obrigatório"));

        mockMvc.perform(post("/api/v1/wallets/7/transactions")
                        .header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CREDIT\",\"amount\":10.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void registerTransactionReturns400WhenTheAmountHasThreeDecimals() throws Exception {
        mockMvc.perform(post("/api/v1/wallets/7/transactions")
                        .header(API_KEY_HEADER, API_KEY)
                        .header("Idempotency-Key", "order-000003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CREDIT\",\"amount\":10.005}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("amount"));
    }

    @Test
    void requestWithoutApiKeyIsRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void requestWithInvalidApiKeyIsRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/1").header(API_KEY_HEADER, "nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        // The actuator slice is not loaded here; a 404 is fine as long as the filter let the
        // request through (it would be a 401 if the API key were required).
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/wallets")
                        .header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holderName\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unsupportedMethodReturns405() throws Exception {
        mockMvc.perform(put("/api/v1/wallets/1").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedMediaTypeReturns415() throws Exception {
        mockMvc.perform(post("/api/v1/wallets")
                        .header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unknownPathReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/nope").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void errorPayloadCarriesTheRequestId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/wallets/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                .andReturn();

        String requestIdHeader = result.getResponse().getHeader(RequestIdFilter.HEADER_NAME);
        String body = result.getResponse().getContentAsString();
        assertThat(requestIdHeader).isNotBlank();
        assertThat(body).contains("\"traceId\":\"" + requestIdHeader + "\"");
    }
}
