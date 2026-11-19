package com.wallet.api.observability;

import com.wallet.api.domain.TransactionType;
import com.wallet.api.domain.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Business metrics of the wallet.
 *
 * <p>Infrastructure metrics (JVM, HTTP, pool) are provided by the Actuator. What matters for a
 * ledger is the outcome distribution: how many operations were applied, replayed by idempotency,
 * rejected by a business rule or failed on infrastructure. That is what tells you, in one query,
 * whether a deploy changed behaviour or a client started retrying wrong.
 *
 * <p>Tags: {@code operation} (create_wallet, register_transaction), {@code outcome}
 * (applied, replayed, error or the {@link ErrorCode} in lower case) and {@code type}
 * (CREDIT, DEBIT, none).
 */
@Component
public class WalletMetrics {

    static final String OPERATIONS = "wallet.operations";
    static final String OPERATION_DURATION = "wallet.operation.duration";

    private final MeterRegistry registry;

    public WalletMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void applied(String operation, TransactionType type, Timer.Sample sample) {
        record(operation, "applied", type, sample);
    }

    public void replayed(String operation, TransactionType type, Timer.Sample sample) {
        record(operation, "replayed", type, sample);
    }

    public void rejected(String operation, TransactionType type, ErrorCode code, Timer.Sample sample) {
        record(operation, code.name().toLowerCase(Locale.ROOT), type, sample);
    }

    public void failed(String operation, TransactionType type, Timer.Sample sample) {
        record(operation, "error", type, sample);
    }

    private void record(String operation, String outcome, TransactionType type, Timer.Sample sample) {
        String typeTag = type == null ? "none" : type.name();
        registry.counter(OPERATIONS,
                "operation", operation,
                "outcome", outcome,
                "type", typeTag).increment();
        if (sample != null) {
            sample.stop(registry.timer(OPERATION_DURATION,
                    "operation", operation,
                    "outcome", outcome,
                    "type", typeTag));
        }
    }
}
