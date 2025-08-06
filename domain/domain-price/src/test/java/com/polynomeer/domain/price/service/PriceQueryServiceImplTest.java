package com.polynomeer.domain.price.service;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.CachePriceRepository;
import com.polynomeer.domain.price.repository.TimeSeriesPriceRepository;
import com.polynomeer.shared.common.error.PriceErrorCode;
import com.polynomeer.shared.common.error.PriceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PriceQueryServiceImplTest {

    private CachePriceRepository cacheRepo;
    private TimeSeriesPriceRepository dbRepo;
    private PriceQueryServiceImpl priceService;

    private final Price dummyPrice = new Price("AAPL", 19500, 200, 1.03, 10000000L, ZonedDateTime.now());

    @BeforeEach
    void setUp() {
        cacheRepo = mock(CachePriceRepository.class);
        dbRepo = mock(TimeSeriesPriceRepository.class);
        priceService = new PriceQueryServiceImpl(cacheRepo, dbRepo);
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
        verify(cacheRepo, times(1)).find("AAPL");
        verifyNoInteractions(dbRepo);
    }

    @Test
    @DisplayName("캐시에 없고 DB에 가격 정보가 있으면 DB에서 가져오고, 캐시에 저장한다")
    void shouldReturnPriceFromDBIfCacheMiss() {
        // given
        when(cacheRepo.find("AAPL")).thenReturn(Optional.empty());
        when(dbRepo.findLatest("AAPL")).thenReturn(Optional.of(dummyPrice));

        // when
        Price result = priceService.getCurrentPrice("AAPL");

        // then
        assertEquals(dummyPrice, result);
        verify(cacheRepo, times(1)).find("AAPL");
        verify(dbRepo, times(1)).findLatest("AAPL");
        verify(cacheRepo, times(1)).save("AAPL", dummyPrice); // 캐시에 저장됨
    }

    @Test
    @DisplayName("캐시와 DB 모두에 가격 정보가 없으면 PriceNotFoundException을 던진다")
    void shouldThrowExceptionIfNotFoundAnywhere() {
        // given
        when(cacheRepo.find("AAPL")).thenReturn(Optional.empty());
        when(dbRepo.findLatest("AAPL")).thenReturn(Optional.empty());

        // when & then
        PriceNotFoundException ex = assertThrows(PriceNotFoundException.class, () ->
                priceService.getCurrentPrice("AAPL"));

        assertEquals(PriceErrorCode.PRICE_NOT_FOUND, ex.getErrorCode());
        verify(cacheRepo, times(1)).find("AAPL");
        verify(dbRepo, times(1)).findLatest("AAPL");
        verify(cacheRepo, never()).save(anyString(), any());
    }
}
