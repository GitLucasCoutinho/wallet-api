package com.wallet.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * API key configuration. Keys are supplied through the {@code WALLET_API_KEYS} environment
 * variable (comma separated); there is deliberately no default, so a misconfigured deployment
 * fails at startup instead of running unauthenticated.
 */
@ConfigurationProperties(prefix = "wallet.security")
public class ApiSecurityProperties {

    private String apiKeys = "";

    public String getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(String apiKeys) {
        this.apiKeys = apiKeys;
    }

    public List<String> keys() {
        if (apiKeys == null) {
            return List.of();
        }
        return Arrays.stream(apiKeys.split(","))
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .toList();
    }
}
