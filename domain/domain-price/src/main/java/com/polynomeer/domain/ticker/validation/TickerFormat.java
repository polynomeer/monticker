package com.polynomeer.domain.ticker.validation;

import java.util.regex.Pattern;

public final class TickerFormat {

    private TickerFormat() {
    }

    public static final Pattern TICKER_PATTERN = Pattern.compile("^[A-Z]{1,5}([.-][A-Z0-9]{1,4})?$");

    public static boolean isValid(String ticker) {
        return ticker != null && TICKER_PATTERN.matcher(ticker).matches();
    }
}
