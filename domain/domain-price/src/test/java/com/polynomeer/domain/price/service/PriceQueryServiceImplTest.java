package com.polynomeer.domain.price.service;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.BackoffStrategy;
import com.polynomeer.domain.price.repository.CachePriceRepository;
import com.polynomeer.domain.price.repository.PriceCacheProperties;
import com.polynomeer.domain.price.repository.TimeSeriesPriceRepository;
import com.polynomeer.shared.common.error.PriceErrorCode;
import com.polynomeer.shared.common.error.PriceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.polynomeer.domain.price.service.FakeRepositories.FakeRedisRepository;
import static com.polynomeer.domain.price.service.FakeRepositories.FakeTimeSeriesRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PriceQueryServiceImplTest {

    private CachePriceRepository cacheRepo;
    private TimeSeriesPriceRepository dbRepo;
    private PriceQueryServiceImpl priceService;

    private final Executor executor = Runnable::run;
    private final BackoffStrategy noBackoff = () -> {
    };
    private final PriceCacheProperties cacheProps = new PriceCacheProperties();

    private final Price dummyPrice =
            new Price("AAPL", 19500, 200, 1.03, 10_000_000L, ZonedDateTime.now());

    @BeforeEach
    void setUp() {
        cacheRepo = mock(CachePriceRepository.class);
        dbRepo = mock(TimeSeriesPriceRepository.class);
        SingleFlightExecutor<String, Price> singleFlight = new SingleFlightExecutor<>();

        priceService = new PriceQueryServiceImpl(
                cacheRepo, dbRepo, singleFlight, executor, noBackoff, cacheProps
        );
    }

    @Test
    @DisplayName("캐시에 가격 정보가 있으면 DB를 조회하지 않고 바로 반환한다")
    void shouldReturnPriceFromCacheIfExists() {
        when(cacheRepo.find("AAPL")).thenReturn(Optional.of(dummyPrice));

        Price result = priceService.getCurrentPrice("AAPL");

        assertEquals(dummyPrice, result);
        verify(cacheRepo, atLeastOnce()).find("AAPL");
        verifyNoInteractions(dbRepo);
        verify(cacheRepo, never()).saveIfAbsent(anyString(), any());
        verify(cacheRepo, never()).save(anyString(), any());
    }

    @Test
    @DisplayName("캐시에 없고 DB에 가격 정보가 있으면 DB에서 가져오고, 캐시에 NX로 저장한다")
    void shouldReturnPriceFromDBIfCacheMiss() {
        when(cacheRepo.find("AAPL"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(dbRepo.findLatest("AAPL")).thenReturn(Optional.of(dummyPrice));
        when(cacheRepo.saveIfAbsent("AAPL", dummyPrice)).thenReturn(true);

        Price result = priceService.getCurrentPrice("AAPL");

        assertEquals(dummyPrice, result);
        verify(cacheRepo, atLeastOnce()).find("AAPL");
        verify(dbRepo, times(1)).findLatest("AAPL");
        verify(cacheRepo, times(1)).saveIfAbsent("AAPL", dummyPrice);
        verify(cacheRepo, never()).save(anyString(), any());
    }

    @Test
    @DisplayName("캐시와 DB 모두에 가격 정보가 없으면 PriceNotFoundException을 던진다")
    void shouldThrowExceptionIfNotFoundAnywhere() {
        when(cacheRepo.find("AAPL")).thenReturn(Optional.empty());
        when(dbRepo.findLatest("AAPL")).thenReturn(Optional.empty());

        PriceNotFoundException ex = assertThrows(
                PriceNotFoundException.class,
                () -> priceService.getCurrentPrice("AAPL")
        );

        assertEquals(PriceErrorCode.PRICE_NOT_FOUND, ex.getErrorCode());
        verify(cacheRepo, atLeastOnce()).find("AAPL");
        verify(dbRepo, times(1)).findLatest("AAPL");
        verify(cacheRepo, never()).saveIfAbsent(anyString(), any());
        verify(cacheRepo, never()).save(anyString(), any());
    }

    @Test
    @DisplayName("캐시 적중 시 DB 접근 없이 즉시 반환하고 추가 쓰기 없음")
    void cache_hit_returns_immediately_without_db_or_extra_writes() {
        FakeRedisRepository redis = new FakeRedisRepository();
        FakeTimeSeriesRepository db = new FakeTimeSeriesRepository("AAPL", 111.0, 0);
        redis.save("AAPL", new Price("AAPL", 111L, 0, 0.0, 0, ZonedDateTime.now()));

        var sut = new PriceQueryServiceImpl(redis, db, new SingleFlightExecutor<>(), executor, noBackoff, cacheProps);

        Price p = sut.getCurrentPrice("AAPL");

        assertEquals(111.0, p.price(), 0.0);
        assertEquals(1, redis.writeCount());
        assertEquals(0, db.reads.get());
    }

    @Test
    @DisplayName("동시 캐시 미스 상황에서도 단일 DB 접근 및 단일 캐시 쓰기로 수렴")
    void concurrent_cache_miss_collapses_to_single_write_via_single_flight_and_nx() throws Exception {
        FakeRedisRepository redis = new FakeRedisRepository();
        FakeTimeSeriesRepository db = new FakeTimeSeriesRepository("AAPL", 123.45, 80);
        var singleFlight = new SingleFlightExecutor<String, Price>();
        ExecutorService pool = Executors.newFixedThreadPool(64);

        var sut = new PriceQueryServiceImpl(redis, db, singleFlight, pool, noBackoff, cacheProps);

        int N = 200;
        CountDownLatch ready = new CountDownLatch(N);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(N);

        for (int i = 0; i < N; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                }
                sut.getCurrentPrice("AAPL");
                done.countDown();
            }).start();
        }

        ready.await();
        start.countDown();
        done.await();

        assertEquals(1, redis.writeCount());
        assertTrue(db.reads.get() <= 3);
        pool.shutdownNow();
    }

    @Test
    @DisplayName("백오프 중 다른 스레드가 캐시 채우면 재사용하고 쓰기 생략")
    void when_peer_fills_cache_during_backoff_service_reuses_cache_and_skips_write() {
        FakeRedisRepository redis = new FakeRedisRepository();
        FakeTimeSeriesRepository db = new FakeTimeSeriesRepository("AAPL", 200.0, 50);

        BackoffStrategy tinyBackoff = () -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        var service = new PriceQueryServiceImpl(
                redis, db, new SingleFlightExecutor<>(), executor, tinyBackoff, new PriceCacheProperties(3)
        );

        new Thread(() -> {
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            redis.saveIfAbsent("AAPL", new Price("AAPL", 200L, 200L, 0.0, 0, ZonedDateTime.now()));
        }).start();

        Price p = service.getCurrentPrice("AAPL");

        assertEquals(200.0, p.price(), 0.0);
        assertEquals(1, redis.writeCount());
        assertEquals(0, db.reads.get());
    }
}
