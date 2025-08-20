package com.polynomeer.infra.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlphaVantageResponseParserTest {

    private final AlphaVantageResponseParser parser = new AlphaVantageResponseParser(new ObjectMapper());

    @Test
    void shouldParseValidResponse() {
        // given
        String json = """
                [
                  {
                    "Global Quote": {
                      "01. symbol": "AAPL",
                      "05. price": "230.5600",
                      "06. volume": "39402564",
                      "07. latest trading day": "2025-08-19"
                    }
                  }
                ]
                """;

        // when
        List<PriceSnapshot> snapshots = parser.parse(json);

        // then
        assertThat(snapshots).hasSize(1);

        PriceSnapshot snap = snapshots.getFirst();
        assertThat(snap.ticker()).isEqualTo("AAPL");
        assertThat(snap.price()).isEqualTo(23056L); // 230.5600 * 100
        assertThat(snap.volume()).isEqualTo(39402564L);
        assertThat(snap.currency()).isEqualTo("USD");
        assertThat(snap.timestamp()).isEqualTo(Instant.parse("2025-08-19T00:00:00Z"));
    }

    @Test
    void shouldSkipInvalidQuote() {
        // given
        String json = """
                [
                  {
                    "Global Quote": {
                      "01. symbol": "AAPL"
                      // price, volume, date 누락
                    }
                  }
                ]
                """;

        // when
        List<PriceSnapshot> snapshots = parser.parse(json);

        // then
        assertThat(snapshots).isEmpty();
    }

    @Test
    void shouldHandleEmptyArray() {
        // given
        String json = "[]";

        // when
        List<PriceSnapshot> result = parser.parse(json);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldHandleInvalidJson() {
        // given
        String invalidJson = "not-a-json";

        // when
        List<PriceSnapshot> result = parser.parse(invalidJson);

        // then
        assertThat(result).isEmpty();
    }
}
