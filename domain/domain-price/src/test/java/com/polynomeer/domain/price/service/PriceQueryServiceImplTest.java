package com.polynomeer.domain.price.service;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.BackoffStrategy;
import com.polynomeer.domain.price.repository.CachePriceRepository;
import com.polynomeer.domain.price.repository.TimeSeriesPriceRepository;
import com.polynomeer.shared.common.error.PriceErrorCode;
import com.polynomeer.shared.common.error.PriceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PriceQueryServiceImplTest {

    private CachePriceRepository cacheRepo;
    private TimeSeriesPriceRepository dbRepo;
    private PriceQueryServiceImpl priceService;

    // 테스트 안정성을 위해 동기 실행 / no-op backoff / 재시도 0으로 고정
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
        // find는 내부적으로 한 번 이상 호출될 수 있으므로 atLeastOnce로 검증
        verify(cacheRepo, atLeastOnce()).find("AAPL");
        verifyNoInteractions(dbRepo);
        // 캐시에 이미 있으니 저장 함수는 호출되지 않아야 함
        verify(cacheRepo, never()).saveIfAbsent(anyString(), any());
        verify(cacheRepo, never()).save(anyString(), any());
    }

    @Test
    @DisplayName("캐시에 없고 DB에 가격 정보가 있으면 DB에서 가져오고, 캐시에 NX로 저장한다")
    void shouldReturnPriceFromDBIfCacheMiss() {
        // given
        when(cacheRepo.find("AAPL"))
                .thenReturn(Optional.empty())  // 초기 미스
                .thenReturn(Optional.empty()); // 동일값 비교용 재조회도 미스 처리(저장 전에)
        when(dbRepo.findLatest("AAPL")).thenReturn(Optional.of(dummyPrice));
        when(cacheRepo.saveIfAbsent("AAPL", dummyPrice)).thenReturn(true);

        // when
        Price result = priceService.getCurrentPrice("AAPL");

        // then
        assertEquals(dummyPrice, result);
        verify(cacheRepo, atLeastOnce()).find("AAPL"); // 최소 1회 이상
        verify(dbRepo, times(1)).findLatest("AAPL");
        // 개선된 구현은 save가 아니라 saveIfAbsent(NX)를 호출
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
}
