package com.polynomeer.domain.price.repository;

import com.polynomeer.domain.price.model.Price;

public interface ExternalPriceClient {
    Price fetchPrice(String tickerCode);
}
