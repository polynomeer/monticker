package com.monticker.worker.kis

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KisClientTest {

    private val cbRegistry: CircuitBreakerRegistry = mockk(relaxed = true)

    @Test
    fun `isConfigured returns false when keys are blank`() {
        val client = KisClient(appKey = "", appSecret = "", cbRegistry = cbRegistry)
        assertThat(client.isConfigured).isFalse()
    }

    @Test
    fun `isConfigured returns true when keys are present`() {
        val client = KisClient(appKey = "test-key", appSecret = "test-secret", cbRegistry = cbRegistry)
        assertThat(client.isConfigured).isTrue()
    }

    @Test
    fun `getAccessToken returns null when not configured`() {
        val client = KisClient(appKey = "", appSecret = "", cbRegistry = cbRegistry)
        assertThat(client.getAccessToken()).isNull()
    }
}
