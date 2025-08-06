package com.polynomeer.domain.price.repository;

import com.polynomeer.domain.price.model.Price;

import java.util.Optional;

public interface CachePriceRepository {
    Optional<Price> find(String tickerCode);

    void save(String tickerCode, Price latestFromDb);
}
