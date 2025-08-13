package com.polynomeer.domain.price.model;

import java.time.ZonedDateTime;

public record ChartPoint(
        ZonedDateTime timestamp,
        long open,
        long high,
        long low,
        long close,
        long volume
) {
}
