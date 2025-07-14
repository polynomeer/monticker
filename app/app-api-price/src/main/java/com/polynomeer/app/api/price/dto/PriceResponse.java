package com.polynomeer.app.api.price.dto;

import com.polynomeer.app.api.price.Price;

import java.time.ZonedDateTime;

public record PriceResponse(
        String tickerCode,
        long price,
        long change,
        double changeRate,
        long volume,
        ZonedDateTime timestamp
) {
    public static PriceResponse from(Price price) {
        return new PriceResponse(
                price.tickerCode(),
                price.price(),
                price.change(),
                price.changeRate(),
                price.volume(),
                price.timestamp()
        );
    }
}
