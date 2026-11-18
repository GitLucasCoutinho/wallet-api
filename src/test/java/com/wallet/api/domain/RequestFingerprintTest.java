package com.wallet.api.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    @Test
    void producesA64CharacterHexDigest() {
        String fingerprint = RequestFingerprint.of(TransactionType.CREDIT, new BigDecimal("10.00"), "pix");

        assertThat(fingerprint).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void isStableForEquivalentAmountRepresentations() {
        String plain = RequestFingerprint.of(TransactionType.CREDIT, new BigDecimal("10"), "pix");
        String scaled = RequestFingerprint.of(TransactionType.CREDIT, new BigDecimal("10.00"), "pix");
        String trailing = RequestFingerprint.of(TransactionType.CREDIT, new BigDecimal("10.000"), "pix");

        assertThat(plain).isEqualTo(scaled).isEqualTo(trailing);
    }

    @Test
    void ignoresFormattingOfTheDescription() {
        assertThat(RequestFingerprint.of(TransactionType.CREDIT, new BigDecimal("10.00"), "  pix  "))
                .isEqualTo(RequestFingerprint.of(TransactionType.CREDIT, new BigDecimal("10.00"), "pix"));
        assertThat(RequestFingerprint.of(TransactionType.CREDIT, new BigDecimal("10.00"), null))
                .isEqualTo(RequestFingerprint.of(TransactionType.CREDIT, new BigDecimal("10.00"), "   "));
    }

    @Test
    void changesWhenAnyPayloadFieldChanges() {
        String base = RequestFingerprint.of(TransactionType.CREDIT, new BigDecimal("10.00"), "pix");

        assertThat(RequestFingerprint.of(TransactionType.DEBIT, new BigDecimal("10.00"), "pix")).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(TransactionType.CREDIT, new BigDecimal("10.01"), "pix")).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(TransactionType.CREDIT, new BigDecimal("10.00"), "ted")).isNotEqualTo(base);
    }
}
