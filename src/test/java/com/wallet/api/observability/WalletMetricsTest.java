package com.wallet.api.observability;

import com.wallet.api.domain.TransactionType;
import com.wallet.api.domain.exception.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WalletMetricsTest {

    private static final String OPERATION = "register_transaction";
    private static final String COUNTER = "wallet.operations";
    private static final String TIMER = "wallet.operation.duration";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WalletMetrics metrics = new WalletMetrics(registry);

    @Test
    void appliedOperationIncrementsTheCounterAndRecordsTheDuration() {
        metrics.applied(OPERATION, TransactionType.CREDIT, metrics.start());

        assertThat(count("applied", "CREDIT")).isEqualTo(1.0);
        assertThat(timer("applied", "CREDIT")).isEqualTo(1L);
    }

    @Test
    void replayedOperationIsCountedApartFromApplied() {
        metrics.replayed(OPERATION, TransactionType.CREDIT, metrics.start());
        metrics.replayed(OPERATION, TransactionType.CREDIT, metrics.start());

        assertThat(count("replayed", "CREDIT")).isEqualTo(2.0);
        assertThat(count("applied", "CREDIT")).isZero();
    }

    @Test
    void rejectionIsTaggedWithTheErrorCode() {
        metrics.rejected(OPERATION, TransactionType.DEBIT, ErrorCode.INSUFFICIENT_FUNDS, metrics.start());

        assertThat(count("insufficient_funds", "DEBIT")).isEqualTo(1.0);
        assertThat(count("applied", "DEBIT")).isZero();
    }

    @Test
    void infrastructureFailureHasItsOwnOutcome() {
        metrics.failed(OPERATION, TransactionType.DEBIT, metrics.start());

        assertThat(count("error", "DEBIT")).isEqualTo(1.0);
    }

    @Test
    void operationWithoutTypeIsTaggedAsNone() {
        metrics.applied("create_wallet", null, metrics.start());

        Counter counter = registry.find(COUNTER)
                .tags("operation", "create_wallet", "outcome", "applied", "type", "none")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void withoutASampleOnlyTheCounterIsRecorded() {
        metrics.applied(OPERATION, TransactionType.CREDIT, null);

        assertThat(count("applied", "CREDIT")).isEqualTo(1.0);
        assertThat(registry.find(TIMER).timer()).isNull();
    }

    private double count(String outcome, String type) {
        Counter counter = registry.find(COUNTER)
                .tags("operation", OPERATION, "outcome", outcome, "type", type)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long timer(String outcome, String type) {
        Timer measured = registry.find(TIMER)
                .tags("operation", OPERATION, "outcome", outcome, "type", type)
                .timer();
        return measured == null ? 0L : measured.count();
    }
}
