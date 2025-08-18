package com.polynomeer.domain.price.service;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.BackoffStrategy;
import com.polynomeer.domain.price.repository.CachePriceRepository;
import com.polynomeer.domain.price.repository.TimeSeriesPriceRepository;
import com.polynomeer.shared.common.error.PriceErrorCode;
import com.polynomeer.shared.common.error.PriceNotFoundException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.*;

import static com.polynomeer.domain.price.service.FakeRepositories.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PriceQueryServiceImplTest {

    private CachePriceRepository cacheRepo;
    private TimeSeriesPriceRepository dbRepo;
    private PriceQueryServiceImpl priceService;

    private final Executor executor = Runnable::run;
    private final BackoffStrategy noBackoff = () -> {
    };
    private final int retries = 0;
    private final ConcurrentHashMap<String, CompletableFuture<Price>> flights = new ConcurrentHashMap<>();

    private final Price dummyPrice =
            new Price("AAPL", 19500, 200, 1.03, 10_000_000L, ZonedDateTime.now());

    @BeforeEach
    void setUp() {
        cacheRepo = mock(CachePriceRepository.class);
        dbRepo = mock(TimeSeriesPriceRepository.class);

        priceService = new PriceQueryServiceImpl(
                cacheRepo, dbRepo, executor, noBackoff, retries, flights
        );
    }

    @Test
    @DisplayName("캐시에 가격 정보가 있으면 DB를 조회하지 않고 바로 반환한다")
    void shouldReturnPriceFromCacheIfExists() {
        // given
        when(cacheRepo.find("AAPL")).thenReturn(Optional.of(dummyPrice));

        // when
        Price result = priceService.getCurrentPrice("AAPL");

        // then
        assertEquals(dummyPrice, result);

        verify(cacheRepo, atLeastOnce()).find("AAPL");
        verifyNoInteractions(dbRepo);

        verify(cacheRepo, never()).saveIfAbsent(anyString(), any());
        verify(cacheRepo, never()).save(anyString(), any());
    }

    @Test
    @DisplayName("캐시에 없고 DB에 가격 정보가 있으면 DB에서 가져오고, 캐시에 NX로 저장한다")
    void shouldReturnPriceFromDBIfCacheMiss() {
        // given
        when(cacheRepo.find("AAPL"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(dbRepo.findLatest("AAPL")).thenReturn(Optional.of(dummyPrice));
        when(cacheRepo.saveIfAbsent("AAPL", dummyPrice)).thenReturn(true);

        // when
        Price result = priceService.getCurrentPrice("AAPL");

        // then
        assertEquals(dummyPrice, result);
        verify(cacheRepo, atLeastOnce()).find("AAPL");
        verify(dbRepo, times(1)).findLatest("AAPL");

        verify(cacheRepo, times(1)).saveIfAbsent("AAPL", dummyPrice);
        verify(cacheRepo, never()).save(anyString(), any());
    }

    @Test
    @DisplayName("캐시와 DB 모두에 가격 정보가 없으면 PriceNotFoundException을 던진다")
    void shouldThrowExceptionIfNotFoundAnywhere() {
        // given
        when(cacheRepo.find("AAPL")).thenReturn(Optional.empty());
        when(dbRepo.findLatest("AAPL")).thenReturn(Optional.empty());

        // when & then
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
        // given
        FakeRedisRepository redis = new FakeRedisRepository();
        FakeTimeSeriesRepository db = new FakeTimeSeriesRepository("AAPL", 111.0, 0);
        // 사전 캐시
        redis.save("AAPL", new Price("AAPL", 111L, 0, 0.0, 0, ZonedDateTime.now()));

        Executor sameThread = Runnable::run; // 동기 실행 → 결정적 테스트
        BackoffStrategy noBackoff = new NoopBackoff();
        int retries = 0;
        var flights = new ConcurrentHashMap<String, CompletableFuture<Price>>();

        PriceQueryServiceImpl sut = new PriceQueryServiceImpl(
                redis, db, sameThread, noBackoff, retries, flights
        );

        // when
        Price p = sut.getCurrentPrice("AAPL");

        // then
        assertEquals(111.0, p.price(), 0.0);
        assertEquals(1, redis.writeCount(), "no extra writes beyond the pre-populated one");
        assertEquals(0, db.reads.get(), "DB should not be hit on cache hit");
    }

    @Test
    @DisplayName("동시 캐시 미스 상황에서도 단일 DB 접근 및 단일 캐시 쓰기로 수렴")
    void concurrent_cache_miss_collapses_to_single_write_via_single_flight_and_nx() throws Exception {
        // given
        FakeRedisRepository redis = new FakeRedisRepository();
        FakeTimeSeriesRepository db = new FakeTimeSeriesRepository("AAPL", 123.45, 80); // 느리게 → 경합 유도

        ExecutorService pool = Executors.newFixedThreadPool(64);
        BackoffStrategy noBackoff = new NoopBackoff();
        int retries = 0;
        var flights = new ConcurrentHashMap<String, CompletableFuture<Price>>();

        PriceQueryServiceImpl sut = new PriceQueryServiceImpl(
                redis, db, pool, noBackoff, retries, flights
        );

        int N = 200;
        CountDownLatch ready = new CountDownLatch(N);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(N);

        // when: 동일 키로 동시에 진입
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

        // then
        assertEquals(1, redis.writeCount(), "writes should collapse to a single NX SET");
        assertTrue(db.reads.get() <= 3, "DB calls should collapse to ~1 (scheduler may cause small >1)");
        pool.shutdownNow();
    }

    @Test
    @DisplayName("백오프 중 다른 스레드가 캐시 채우면 재사용하고 쓰기 생략")
    void when_peer_fills_cache_during_backoff_service_reuses_cache_and_skips_write() {
        // given
        FakeRedisRepository redis = new FakeRedisRepository();
        FakeTimeSeriesRepository db = new FakeTimeSeriesRepository("AAPL", 200.0, 50);

        PriceQueryServiceImpl priceQueryService = getPriceQueryService(redis, db);

        new Thread(() -> {
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            redis.saveIfAbsent("AAPL", new Price("AAPL", 200L, 200L, 0.0, 0, ZonedDateTime.now()));
        }).start();

        // when
        Price p = priceQueryService.getCurrentPrice("AAPL");

        // then: 캐시를 재사용했고, 추가 쓰기 발생하지 않음
        assertEquals(200.0, p.price(), 0.0);
        assertEquals(1, redis.writeCount(), "only the peer write should exist; service should skip");
        assertEquals(0, db.reads.get(), "backoff window allowed reuse without DB in this path");
    }

    @NotNull
    private static PriceQueryServiceImpl getPriceQueryService(FakeRedisRepository redis, FakeTimeSeriesRepository db) {
        Executor sameThread = Runnable::run;
        BackoffStrategy tinyBackoff = () -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        int retries = 3;
        var flights = new ConcurrentHashMap<String, CompletableFuture<Price>>();

        return new PriceQueryServiceImpl(
                redis, db, sameThread, tinyBackoff, retries, flights
        );
    }
}
