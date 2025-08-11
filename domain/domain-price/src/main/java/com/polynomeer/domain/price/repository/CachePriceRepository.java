package com.polynomeer.domain.price.repository;

import com.polynomeer.domain.price.model.Price;

public interface CachePriceRepository {
    Price find(String tickerCode);

    void save(String tickerCode, Price latestFromDb);
}
