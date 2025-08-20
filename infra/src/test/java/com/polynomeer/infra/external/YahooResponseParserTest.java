package com.polynomeer.infra.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

class YahooResponseParserTest {

    private final YahooResponseParser parser = new YahooResponseParser(new ObjectMapper());

    @Test
    void shouldParseValidQuotes() {
        String json = """
                {
                  "quoteResponse": {
                    "result": [
                      {
                        "symbol": "AAPL",
                        "regularMarketPrice": 194.35,
                        "regularMarketVolume": 1234567,
                        "currency": "USD",
                        "regularMarketTime": 1692543300
                      }
                    ]
                  }
                }
                """;

        List<PriceSnapshot> result = parser.parse(json);
        assertThat(result).hasSize(1);
        PriceSnapshot snap = result.getFirst();
        assertThat(snap.ticker()).isEqualTo("AAPL");
        assertThat(snap.price()).isEqualTo(19435);
    }
}
