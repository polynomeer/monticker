package com.polynomeer.infra.external;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlphaVantageClient {

    private static final String BASE_URL = "https://www.alphavantage.co/query";
    private static final String API_KEY = "<YOUR_API_KEY>";

    private final RestClient restClient = RestClient.builder()
            .baseUrl(BASE_URL)
            .build();

    /**
     * 여러 종목의 시세를 JSON 묶음으로 받아온다
     */
    public String fetchQuotes(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return "{}";
        }

        return tickers.stream()
                .map(this::fetchSingleQuote)
                .collect(Collectors.joining(",", "[", "]")); // JSON array 형태로 묶기
    }

    private String fetchSingleQuote(String symbol) {
        String uri = UriComponentsBuilder.fromPath("")
                .queryParam("function", "GLOBAL_QUOTE")
                .queryParam("symbol", symbol)
                .queryParam("apikey", API_KEY)
                .build()
                .toUriString();

        log.debug("Alpha Vantage 요청: {}", uri);

        try {
            String response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            log.debug("Alpha 응답({}): {}", symbol, response);
            return response;
        } catch (Exception e) {
            log.error("Alpha Vantage 요청 실패: {}", symbol, e);
            return "{}";
        }
    }
}
