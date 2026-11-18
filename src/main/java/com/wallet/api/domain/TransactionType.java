package com.wallet.api.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

public enum TransactionType {

    CREDIT,
    DEBIT;

    /**
     * Accepts {@code CREDIT}/{@code DEBIT} in any case in JSON bodies; anything else is a 400.
     */
    @JsonCreator
    public static TransactionType from(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("tipo deve ser CREDIT ou DEBIT", ex);
        }
    }
}
