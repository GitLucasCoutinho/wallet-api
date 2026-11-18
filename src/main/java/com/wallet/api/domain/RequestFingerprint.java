package com.wallet.api.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic fingerprint of the semantic payload of a register-operation request.
 *
 * <p>The amount is canonicalized ({@code 10}, {@code 10.0} and {@code 10.00} produce the same
 * hash) and the description is trimmed, so a retry that only differs in JSON formatting is still
 * recognized as the same operation. Type, amount and description are all part of the payload: a
 * different amount or description under the same key is a conflict, not a retry.
 */
public final class RequestFingerprint {

    private static final String SEPARATOR = "|";

    private RequestFingerprint() {
    }

    public static String of(TransactionType type, BigDecimal amount, String description) {
        String canonicalAmount = amount.setScale(Amounts.SCALE, RoundingMode.HALF_UP).toPlainString();
        String canonicalDescription = description == null ? "" : description.trim();
        String canonical = (type == null ? "" : type.name())
                + SEPARATOR + canonicalAmount
                + SEPARATOR + canonicalDescription;
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
