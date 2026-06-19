package com.monticker.worker.integration

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Integration tests for AlertEvaluator against a real TimescaleDB + Redis.
 * Requires Testcontainers — enable by removing @Disabled and running with
 * the `integration` Gradle source set or `-PrunIntegration=true`.
 */
@Disabled("Testcontainers integration tests run in CI only")
class AlertEvaluatorIntegrationTest {

    @Test
    fun `alert fires when candle close exceeds threshold`() {
        // TODO Week 8: spin up timescale/timescaledb:latest-pg16 via Testcontainers,
        //  seed alert_rules and candles_1m, run AlertEvaluator.evaluate(),
        //  assert alert_histories row inserted.
    }

    @Test
    fun `duplicate alert suppressed within 10 minute cooldown`() {
        // TODO Week 8
    }
}
