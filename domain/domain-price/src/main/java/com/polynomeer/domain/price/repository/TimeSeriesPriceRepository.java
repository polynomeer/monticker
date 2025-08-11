package com.polynomeer.domain.price.repository;

import com.polynomeer.domain.price.model.Price;

public interface TimeSeriesPriceRepository {
    Price findLatest(String tickerCode);
}
