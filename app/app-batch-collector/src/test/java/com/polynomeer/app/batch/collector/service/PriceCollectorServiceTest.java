package com.polynomeer.app.batch.collector.service;

import com.polynomeer.infra.external.PriceDataProvider;
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

    private PriceDataProvider dataProvider;
    private RedisPriceStore redisStore;
    private TimescalePriceStore timescaleStore;
    private PriceCollectorService service;

    @BeforeEach
    void setUp() {
        dataProvider = mock(PriceDataProvider.class);
        redisStore = mock(RedisPriceStore.class);
        timescaleStore = mock(TimescalePriceStore.class);
        service = new PriceCollectorService(dataProvider, redisStore, timescaleStore);
    }

    @Test
    void shouldCollectAndSavePricesUsingPriceDataProvider() {
        // given
        List<String> tickers = List.of("AAPL", "TSLA");
        List<PriceSnapshot> snapshots = List.of(
                new PriceSnapshot("AAPL", 19435, 1_234_567, "USD", Instant.now()),
                new PriceSnapshot("TSLA", 25500, 987_654, "USD", Instant.now())
        );
        when(dataProvider.fetchSnapshots(tickers)).thenReturn(snapshots);

        // when
        service.collectPrices(tickers);

        // then
        verify(dataProvider).fetchSnapshots(tickers);

        ArgumentCaptor<PriceSnapshot> captor = ArgumentCaptor.forClass(PriceSnapshot.class);
        verify(redisStore, times(2)).save(captor.capture());
        verify(timescaleStore, times(2)).save(captor.capture());

        // Redis + Timescale 호출합계 4회(각 2회) 검증
        List<PriceSnapshot> allSaved = captor.getAllValues();
        assertThat(allSaved).hasSize(4);
        assertThat(allSaved.stream().map(PriceSnapshot::ticker))
                .contains("AAPL", "TSLA");
    }

    @Test
    void shouldSkipWhenNoTickers() {
        service.collectPrices(List.of());
        verifyNoInteractions(dataProvider, redisStore, timescaleStore);
    }
}
