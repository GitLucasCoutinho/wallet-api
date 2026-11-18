package com.wallet.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wallet.pagination")
public class PaginationProperties {

    private int defaultPageSize = 20;
    private int maxPageSize = 100;

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize > 0 ? defaultPageSize : 20;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize > 0 ? maxPageSize : 100;
    }

    public int effectiveDefaultPageSize() {
        return Math.min(defaultPageSize, maxPageSize);
    }
}
