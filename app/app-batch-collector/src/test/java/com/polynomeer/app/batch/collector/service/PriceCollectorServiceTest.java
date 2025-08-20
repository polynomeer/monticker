package com.polynomeer.app.batch.collector.service;

import com.polynomeer.infra.external.AlphaVantageClient;
import com.polynomeer.infra.external.AlphaVantageResponseParser;
import com.polynomeer.infra.external.PriceSnapshot;
import com.polynomeer.infra.redis.RedisPriceStore;
import com.polynomeer.infra.timescaledb.TimescalePriceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PriceCollectorServiceTest {

    private AlphaVantageClient client;
    private AlphaVantageResponseParser parser;
    private RedisPriceStore redisStore;
    private TimescalePriceStore timescaleStore;
    private PriceCollectorService service;

    @BeforeEach
    void setUp() {
        client = mock(AlphaVantageClient.class);
        parser = mock(AlphaVantageResponseParser.class);
        redisStore = mock(RedisPriceStore.class);
        timescaleStore = mock(TimescalePriceStore.class);

        service = new PriceCollectorService(client, parser, redisStore, timescaleStore);
    }

    @Test
    void shouldCollectAndSavePrices() {
        // given
        List<String> tickers = List.of("AAPL", "TSLA");
        String fakeResponse = "{...}"; // JSON 생략
        List<PriceSnapshot> parsed = List.of(
                new PriceSnapshot("AAPL", 19435, 1234567, "USD", Instant.now()),
                new PriceSnapshot("TSLA", 25500, 987654, "USD", Instant.now())
        );

        when(client.fetchQuotes(tickers)).thenReturn(fakeResponse);
        when(parser.parse(fakeResponse)).thenReturn(parsed);

        // when
        service.collectPrices(tickers);

        // then
        verify(client).fetchQuotes(tickers);
        verify(parser).parse(fakeResponse);

        ArgumentCaptor<PriceSnapshot> captor = ArgumentCaptor.forClass(PriceSnapshot.class);
        verify(redisStore, times(2)).save(captor.capture());
        verify(timescaleStore, times(2)).save(captor.capture());

        List<PriceSnapshot> allSaved = captor.getAllValues();
        assertThat(allSaved).hasSize(4); // 2 Redis + 2 Timescale
    }
}
