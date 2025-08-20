package com.polynomeer.app.batch.collector.infra;

import com.polynomeer.infra.external.YahooFinancePriceClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class YahooFinancePriceClientTest {

    private final YahooFinancePriceClient client = new YahooFinancePriceClient();

    @Test
    void shouldFetchQuotesFromYahoo() {
        String json = client.fetchQuotes(List.of("AAPL", "TSLA"));

        System.out.println("Yahoo 응답: " + json);

        assertThat(json).contains("quoteResponse");
        assertThat(json).contains("AAPL");
    }
}
