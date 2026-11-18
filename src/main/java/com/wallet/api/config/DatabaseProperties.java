package com.wallet.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

/**
 * Name of the schema that owns the ledger objects.
 *
 * <p>The runtime user ({@code WALLET_APP}) only holds object grants, so every reference must be
 * schema qualified: Oracle resolves unqualified names in the current schema only, and we do not
 * want to change the session's current schema (which would hide the privilege model) nor to create
 * synonyms (which would need extra privileges).
 *
 * <p>Configurable so the schema can be renamed per environment; validated because the value is
 * concatenated into SQL as an identifier.
 */
@ConfigurationProperties(prefix = "wallet.database")
public class DatabaseProperties {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]{0,127}");

    private String schema = "WALLET_OWNER";

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        if (schema == null || !IDENTIFIER.matcher(schema.trim()).matches()) {
            throw new IllegalArgumentException("wallet.database.schema must be a valid Oracle identifier: " + schema);
        }
        this.schema = schema.trim();
    }
}
