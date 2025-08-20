package com.polynomeer.infra.external;

import java.time.Instant;

public record PriceSnapshot(
        String ticker,
        long price,
        long volume,
        String currency,
        Instant timestamp
) {
}
