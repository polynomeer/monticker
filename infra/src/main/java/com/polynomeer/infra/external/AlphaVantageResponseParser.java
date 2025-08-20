package com.polynomeer.infra.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlphaVantageResponseParser {

    private final ObjectMapper objectMapper;

    /**
     * AlphaVantage 응답 JSON 배열 문자열 → PriceSnapshot 리스트로 파싱
     */
    public List<PriceSnapshot> parse(String jsonArrayString) {
        List<PriceSnapshot> result = new ArrayList<>();

        try {
            JsonNode array = objectMapper.readTree(jsonArrayString);

            if (!array.isArray()) {
                log.warn("Alpha 응답이 배열이 아님: {}", jsonArrayString);
                return List.of();
            }

            for (JsonNode root : array) {
                JsonNode quote = root.path("Global Quote");
                if (quote.isMissingNode() || quote.isEmpty()) {
                    continue;
                }

                String symbol = quote.path("01. symbol").asText(null);
                String priceStr = quote.path("05. price").asText(null);
                String volumeStr = quote.path("06. volume").asText(null);
                String dateStr = quote.path("07. latest trading day").asText(null);

                if (symbol == null || priceStr == null || dateStr == null) {
                    log.warn("누락된 필드 있음 → skip: {}", quote);
                    continue;
                }

                long price = (long) (Double.parseDouble(priceStr) * 100);
                long volume = volumeStr != null ? Long.parseLong(volumeStr) : 0;
                Instant timestamp = LocalDate.parse(dateStr).atStartOfDay().toInstant(ZoneOffset.UTC);

                result.add(new PriceSnapshot(symbol, price, volume, "USD", timestamp));
            }

        } catch (Exception e) {
            log.error("AlphaVantage 응답 파싱 실패", e);
        }

        return result;
    }
}
