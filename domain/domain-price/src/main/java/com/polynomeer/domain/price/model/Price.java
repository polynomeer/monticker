package com.polynomeer.domain.price.model;

import java.time.ZonedDateTime;

public record Price(
        String tickerCode,
        long price,
        long change,
        double changeRate,
        long volume,
        ZonedDateTime timestamp
) {
}
