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
public class AlphaVantageDataProvider implements PriceDataProvider {

    private final AlphaVantageClient client;   // ← 기존 클라이언트
    private final ObjectMapper objectMapper;   // ← 기존 파서를 대체 (내부 파싱)

    @Override
    public List<PriceSnapshot> fetchSnapshots(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return List.of();

        List<PriceSnapshot> out = new ArrayList<>();
        String jsonArray = client.fetchQuotes(tickers); // 기존: 배열 문자열 반환  :contentReference[oaicite:5]{index=5}
        try {
            JsonNode array = objectMapper.readTree(jsonArray);
            if (!array.isArray()) {
                log.warn("Alpha response is not array: {}", jsonArray);
                return List.of();
            }

            for (JsonNode root : array) {
                JsonNode quote = root.path("Global Quote");
                if (quote.isMissingNode() || quote.isEmpty()) continue;

                String symbol = quote.path("01. symbol").asText(null);
                String priceStr = quote.path("05. price").asText(null);
                String volumeStr = quote.path("06. volume").asText("0");
                String dateStr = quote.path("07. latest trading day").asText(null);

                if (symbol == null || priceStr == null || dateStr == null) continue;

                long price = Math.round(Double.parseDouble(priceStr) * 100);
                long volume = Long.parseLong(volumeStr);
                Instant ts = LocalDate.parse(dateStr).atStartOfDay().toInstant(ZoneOffset.UTC);

                out.add(new PriceSnapshot(symbol, price, volume, "USD", ts));
            }
        } catch (Exception e) {
            log.error("Alpha provider parse error", e);
        }
        return out;
    }
}
