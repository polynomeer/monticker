package com.polynomeer.app.batch.collector.service;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PopularTickerService {

    // Stub: 임시 인기 종목 리스트 반환
    public List<String> getPopularTickers() {
        return List.of(
                "AAPL", "TSLA", "NVDA", "AMZN", "MSFT",
                "GOOGL", "META", "NFLX", "AMD", "INTC"
        );
    }
}
