package com.wallet.api.web;

import com.wallet.api.config.ApiSecurityProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Verifies the API key in constant time: every configured key is compared, and the result is
 * accumulated instead of short-circuiting, so response time does not reveal how many keys exist.
 */
@Component
public class ApiKeyVerifier {

    private final ApiSecurityProperties properties;

    public ApiKeyVerifier(ApiSecurityProperties properties) {
        this.properties = properties;
        if (properties.keys().isEmpty()) {
            throw new IllegalStateException(
                    "Nenhuma API key configurada. Defina WALLET_API_KEYS (lista separada por vírgula).");
        }
    }

    public boolean isValid(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        byte[] candidateBytes = candidate.getBytes(StandardCharsets.UTF_8);
        List<String> keys = properties.keys();
        boolean valid = false;
        for (String key : keys) {
            valid |= MessageDigest.isEqual(candidateBytes, key.getBytes(StandardCharsets.UTF_8));
        }
        return valid;
    }
}
