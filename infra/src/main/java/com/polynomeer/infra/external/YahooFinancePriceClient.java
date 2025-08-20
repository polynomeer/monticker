package com.polynomeer.infra.external;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class YahooFinancePriceClient {

    private static final String BASE_URL = "https://query1.finance.yahoo.com";
    private final WebClient webClient = WebClient.create(BASE_URL);

    /**
     * 여러 종목의 시세 JSON을 가져온다 (원시 응답 그대로 반환)
     */
    public String fetchQuotes(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return "{}";
        }

        String symbolsParam = String.join(",", tickers);
        String uri = UriComponentsBuilder
                .fromPath("/v7/finance/quote")
                .queryParam("symbols", symbolsParam)
                .build()
                .toUriString();

        log.debug("Calling Yahoo Finance API: {}", uri);

        try {
            return webClient
                    .get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorResume(e -> {
                        log.error("Yahoo API 호출 실패: {}", e.getMessage(), e);
                        return Mono.empty();
                    })
                    .blockOptional()
                    .orElse("{}");

        } catch (Exception e) {
            log.error("Yahoo API 요청 예외 발생", e);
            throw new RuntimeException("Yahoo API 호출 실패", e);
        }
    }
}
