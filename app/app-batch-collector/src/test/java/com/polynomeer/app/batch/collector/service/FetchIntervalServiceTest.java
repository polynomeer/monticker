package com.polynomeer.app.batch.collector.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

class FetchIntervalServiceTest {

    @Test
    void testIntervalFiltering() throws InterruptedException {
        FetchIntervalService service = new FetchIntervalService();
        service.init();

        List<String> input = List.of("AAPL", "TSLA");

        List<String> first = service.filterTickersToFetch(input);
        assertThat(first).contains("AAPL", "TSLA");

        Thread.sleep(1000); // 1초 대기

        List<String> second = service.filterTickersToFetch(input);
        assertThat(second).contains("AAPL");       // AAPL: 1초 주기 → 통과
        assertThat(second).doesNotContain("TSLA"); // TSLA: 5초 주기 → 아직
    }
}
