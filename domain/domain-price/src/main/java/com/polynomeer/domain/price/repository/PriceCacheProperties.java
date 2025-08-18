package com.polynomeer.domain.price.repository;

import lombok.Getter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "price.cache")
@Getter
@ToString
public class PriceCacheProperties {
    private final int retries;

    public PriceCacheProperties() {
        this.retries = 2;
    }

    public PriceCacheProperties(int retries) {
        this.retries = retries;
    }
}
