package com.wallet.api.application;

import com.wallet.api.config.PaginationProperties;
import com.wallet.api.domain.RegisterTransactionCommand;
import com.wallet.api.domain.TransactionFilter;
import com.wallet.api.domain.TransactionReceipt;
import com.wallet.api.domain.TransactionSearchResult;
import com.wallet.api.domain.TransactionType;
import com.wallet.api.domain.Wallet;
import com.wallet.api.domain.WalletRepository;
import com.wallet.api.domain.WalletTransaction;
import com.wallet.api.domain.exception.ErrorCode;
import com.wallet.api.domain.exception.InsufficientFundsException;
import com.wallet.api.domain.exception.InvalidRequestException;
import com.wallet.api.domain.exception.WalletException;
import com.wallet.api.domain.exception.WalletNotFoundException;
import com.wallet.api.observability.WalletMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletApplicationServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 1, 2, 10, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private WalletRepository repository;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private WalletApplicationService service;

    @BeforeEach
    void setUp() {
        PaginationProperties pagination = new PaginationProperties();
        pagination.setDefaultPageSize(20);
        pagination.setMaxPageSize(100);
        service = new WalletApplicationService(repository, pagination, new WalletMetrics(meterRegistry));
    }

    @Test
    void createWalletTrimsTheInputAndDelegatesToTheRepository() {
        when(repository.create("Ana Souza", "12345678900"))
                .thenReturn(new Wallet(1000L, "Ana Souza", "12345678900", "BRL", new BigDecimal("0.00"), NOW));

        Wallet wallet = service.createWallet("  Ana Souza  ", "  12345678900  ");

        assertThat(wallet.id()).isEqualTo(1000L);
        verify(repository).create("Ana Souza", "12345678900");
    }

    @Test
    void createWalletTurnsBlankDocumentIntoNull() {
        when(repository.create(anyString(), eq(null)))
                .thenReturn(new Wallet(1L, "Ana", null, "BRL", BigDecimal.ZERO, NOW));

        service.createWallet("Ana", "   ");

        verify(repository).create("Ana", null);
    }

    @Test
    void createWalletRejectsBlankHolderName() {
        assertThatThrownBy(() -> service.createWallet("  ", null))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((WalletException) ex).code())
                .isEqualTo(ErrorCode.INVALID_HOLDER);
        verifyNoInteractions(repository);
    }

    @Test
    void createWalletRejectsHolderNameAboveTheColumnSize() {
        String tooLong = "a".repeat(121);

        assertThatThrownBy(() -> service.createWallet(tooLong, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("120");
        verifyNoInteractions(repository);
    }

    @Test
    void getWalletThrowsNotFoundWhenTheRepositoryIsEmpty() {
        when(repository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWallet(42L))
                .isInstanceOf(WalletNotFoundException.class)
                .hasMessageContaining("42");
    }

    @Test
    void searchTransactionsRejectsNegativePage() {
        assertThatThrownBy(() -> service.searchTransactions(1L, TransactionFilter.none(), -1, 20))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((WalletException) ex).code())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(repository);
    }

    @Test
    void searchTransactionsRejectsSizeAboveTheConfiguredLimit() {
        assertThatThrownBy(() -> service.searchTransactions(1L, TransactionFilter.none(), 0, 101))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("100");
        verifyNoInteractions(repository);
    }

    @Test
    void searchTransactionsRejectsSizeZero() {
        assertThatThrownBy(() -> service.searchTransactions(1L, TransactionFilter.none(), 0, 0))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void searchTransactionsRejectsAnInvertedPeriod() {
        TransactionFilter filter = new TransactionFilter(NOW, NOW.minusDays(1), null);

        assertThatThrownBy(() -> service.searchTransactions(1L, filter, 0, 20))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("from");
        verifyNoInteractions(repository);
    }

    @Test
    void searchTransactionsReturnsNotFoundForAnUnknownWallet() {
        when(repository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.searchTransactions(7L, TransactionFilter.none(), 0, 20))
                .isInstanceOf(WalletNotFoundException.class);
        verify(repository, never()).findTransactions(anyLong(), any(), anyInt(), anyInt());
    }

    @Test
    void searchTransactionsComputesTheOffsetAndReturnsThePage() {
        WalletTransaction transaction = new WalletTransaction(1L, 7L, TransactionType.CREDIT,
                new BigDecimal("10.00"), new BigDecimal("0.00"), new BigDecimal("10.00"),
                "key-000001", "pix", NOW);
        when(repository.findById(7L)).thenReturn(Optional.of(
                new Wallet(7L, "Ana", null, "BRL", new BigDecimal("10.00"), NOW)));
        when(repository.countTransactions(eq(7L), any())).thenReturn(65L);
        when(repository.findTransactions(eq(7L), any(), eq(40), eq(20))).thenReturn(List.of(transaction));

        TransactionSearchResult result = service.searchTransactions(7L, TransactionFilter.none(), 2, 20);

        assertThat(result.items()).containsExactly(transaction);
        assertThat(result.totalElements()).isEqualTo(65L);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
    }

    @Test
    void searchTransactionsSkipsTheQueryWhenThePageIsBeyondTheLastOne() {
        when(repository.findById(7L)).thenReturn(Optional.of(
                new Wallet(7L, "Ana", null, "BRL", BigDecimal.ZERO, NOW)));
        when(repository.countTransactions(eq(7L), any())).thenReturn(3L);

        TransactionSearchResult result = service.searchTransactions(7L, TransactionFilter.none(), 5, 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(3L);
        verify(repository, never()).findTransactions(anyLong(), any(), anyInt(), anyInt());
    }

    @Test
    void registerTransactionNormalizesThePayloadBeforeCallingTheDatabase() {
        when(repository.register(eq(7L), any())).thenReturn(new TransactionReceipt(
                7L, 1L, TransactionType.CREDIT, new BigDecimal("10.00"), new BigDecimal("10.00"), NOW, false));

        service.registerTransaction(7L, TransactionType.CREDIT, new BigDecimal("10.000"),
                "  pix recebido  ", "  order-0001  ");

        ArgumentCaptor<RegisterTransactionCommand> captor = ArgumentCaptor.forClass(RegisterTransactionCommand.class);
        verify(repository).register(eq(7L), captor.capture());
        RegisterTransactionCommand command = captor.getValue();

        assertThat(command.type()).isEqualTo(TransactionType.CREDIT);
        assertThat(command.amount()).isEqualByComparingTo("10.00").hasScaleOf(2);
        assertThat(command.description()).isEqualTo("pix recebido");
        assertThat(command.idempotencyKey()).isEqualTo("order-0001");
        assertThat(command.requestHash()).hasSize(64);
    }

    @Test
    void registerTransactionIsDeterministicForTheSamePayload() {
        when(repository.register(eq(7L), any())).thenReturn(new TransactionReceipt(
                7L, 1L, TransactionType.DEBIT, new BigDecimal("10.00"), new BigDecimal("90.00"), NOW, false));

        service.registerTransaction(7L, TransactionType.DEBIT, new BigDecimal("10"), "pix", "key-000001");
        service.registerTransaction(7L, TransactionType.DEBIT, new BigDecimal("10.00"), "pix", "key-000002");

        ArgumentCaptor<RegisterTransactionCommand> captor = ArgumentCaptor.forClass(RegisterTransactionCommand.class);
        verify(repository, org.mockito.Mockito.times(2)).register(eq(7L), captor.capture());
        assertThat(captor.getAllValues().get(0).requestHash())
                .isEqualTo(captor.getAllValues().get(1).requestHash());
    }

    @Test
    void registerTransactionRejectsMissingIdempotencyKey() {
        assertThatThrownBy(() -> service.registerTransaction(7L, TransactionType.CREDIT,
                new BigDecimal("10.00"), null, null))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((WalletException) ex).code())
                .isEqualTo(ErrorCode.MISSING_IDEMPOTENCY_KEY);
        verifyNoInteractions(repository);
    }

    @Test
    void registerTransactionRejectsNegativeAmount() {
        assertThatThrownBy(() -> service.registerTransaction(7L, TransactionType.CREDIT,
                new BigDecimal("-10.00"), null, "key-000001"))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((WalletException) ex).code())
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
        verifyNoInteractions(repository);
    }

    @Test
    void registerTransactionRejectsNullType() {
        assertThatThrownBy(() -> service.registerTransaction(7L, null,
                new BigDecimal("10.00"), null, "key-000001"))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((WalletException) ex).code())
                .isEqualTo(ErrorCode.INVALID_TRANSACTION_TYPE);
        verifyNoInteractions(repository);
    }

    @Test
    void registerTransactionRejectsLongDescriptions() {
        String description = "d".repeat(256);

        assertThatThrownBy(() -> service.registerTransaction(7L, TransactionType.CREDIT,
                new BigDecimal("10.00"), description, "key-000001"))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((WalletException) ex).code())
                .isEqualTo(ErrorCode.INVALID_DESCRIPTION);
        verifyNoInteractions(repository);
    }

    @Test
    void registerTransactionReturnsTheRepositoryReceiptUntouched() {
        TransactionReceipt receipt = new TransactionReceipt(7L, 555L, TransactionType.CREDIT,
                new BigDecimal("10.00"), new BigDecimal("20.00"), NOW, true);
        when(repository.register(eq(7L), any())).thenReturn(receipt);

        TransactionReceipt result = service.registerTransaction(7L, TransactionType.CREDIT,
                new BigDecimal("10.00"), "pix", "key-000001");

        assertThat(result).isSameAs(receipt);
    }

    @Test
    void registerTransactionCountsAppliedOutcome() {
        when(repository.register(eq(7L), any())).thenReturn(new TransactionReceipt(
                7L, 1L, TransactionType.CREDIT, new BigDecimal("10.00"), new BigDecimal("10.00"), NOW, false));

        service.registerTransaction(7L, TransactionType.CREDIT, new BigDecimal("10.00"), "pix", "key-000001");

        assertThat(metric("applied", "CREDIT")).isEqualTo(1.0);
    }

    @Test
    void registerTransactionCountsReplayOutcome() {
        when(repository.register(eq(7L), any())).thenReturn(new TransactionReceipt(
                7L, 1L, TransactionType.CREDIT, new BigDecimal("10.00"), new BigDecimal("10.00"), NOW, true));

        service.registerTransaction(7L, TransactionType.CREDIT, new BigDecimal("10.00"), "pix", "key-000001");

        assertThat(metric("replayed", "CREDIT")).isEqualTo(1.0);
    }

    @Test
    void registerTransactionCountsDatabaseRejectionByErrorCode() {
        when(repository.register(eq(7L), any()))
                .thenThrow(new InsufficientFundsException("balance 0.00 is lower than 10.00"));

        assertThatThrownBy(() -> service.registerTransaction(7L, TransactionType.DEBIT,
                new BigDecimal("10.00"), "pix", "key-000001"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(metric("insufficient_funds", "DEBIT")).isEqualTo(1.0);
    }

    @Test
    void registerTransactionCountsValidationRejection() {
        assertThatThrownBy(() -> service.registerTransaction(7L, TransactionType.DEBIT,
                new BigDecimal("10.005"), "pix", "key-000001"))
                .isInstanceOf(InvalidRequestException.class);

        assertThat(metric("invalid_amount", "DEBIT")).isEqualTo(1.0);
    }

    private double metric(String outcome, String type) {
        var counter = meterRegistry.find("wallet.operations")
                .tags("operation", "register_transaction", "outcome", outcome, "type", type)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
