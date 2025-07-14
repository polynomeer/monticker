package com.polynomeer.app.api.price;

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
