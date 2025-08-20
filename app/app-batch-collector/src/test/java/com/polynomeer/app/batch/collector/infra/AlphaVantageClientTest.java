package com.polynomeer.app.batch.collector.infra;

import com.polynomeer.infra.external.AlphaVantageClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AlphaVantageClientTest {

    private final AlphaVantageClient client = new AlphaVantageClient();

    @Test
    void shouldFetchQuotesFromAlphaVantage() {
        String json = client.fetchQuotes(List.of("AAPL", "TSLA"));

        System.out.println("Alpha Vantage 응답: " + json);

        assertThat(json).contains("Global Quote");
        assertThat(json).contains("AAPL");
    }
}
