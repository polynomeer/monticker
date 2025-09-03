package com.polynomeer.app.batch.collector.kafka;

public record FetchCommand(String ticker, long requestedAtMs, String correlationId) {
}
