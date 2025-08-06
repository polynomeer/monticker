package com.polynomeer.domain.price.repository;

import com.polynomeer.domain.price.model.Price;

import java.util.Optional;

public interface TimeSeriesPriceRepository {
    Optional<Price> findLatest(String tickerCode);
}
