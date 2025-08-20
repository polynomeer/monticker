package com.polynomeer.app.batch.collector.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PopularTickerServiceTest {

    private final PopularTickerService service = new PopularTickerService();

    @Test
    void shouldReturnStubTickers() {
        List<String> tickers = service.getPopularTickers();

        assertThat(tickers).isNotEmpty();
        assertThat(tickers).contains("AAPL", "TSLA", "MSFT");
    }
}
