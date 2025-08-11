package com.polynomeer.domain.price.service;

import com.polynomeer.domain.price.model.Price;

public interface PriceQueryService {
    Price getCurrentPrice(String tickerCode);
}
