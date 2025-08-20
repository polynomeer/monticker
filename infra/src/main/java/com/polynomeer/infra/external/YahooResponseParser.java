package com.polynomeer.infra.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class YahooResponseParser {

    private final ObjectMapper objectMapper;

    public List<PriceSnapshot> parse(String json) {
        List<PriceSnapshot> result = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode quotes = root.path("quoteResponse").path("result");

            for (JsonNode node : quotes) {
                String ticker = node.path("symbol").asText();
                double priceRaw = node.path("regularMarketPrice").asDouble(Double.NaN);
                long volume = node.path("regularMarketVolume").asLong(0);
                String currency = node.path("currency").asText("USD");
                long timestamp = node.path("regularMarketTime").asLong(0);

                if (Double.isNaN(priceRaw) || priceRaw <= 0) {
                    log.warn("종목 {}: 가격 정보 없음 → 제외", ticker);
                    continue;
                }

                long price = Math.round(priceRaw * 100); // 소수점 둘째 자리까지
                Instant time = (timestamp > 0) ? Instant.ofEpochSecond(timestamp) : Instant.now();

                result.add(new PriceSnapshot(ticker, price, volume, currency, time));
            }

        } catch (Exception e) {
            log.error("Yahoo JSON 파싱 실패", e);
        }

        return result;
    }
}
