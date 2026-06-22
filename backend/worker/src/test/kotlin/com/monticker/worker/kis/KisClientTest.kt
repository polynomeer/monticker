package com.monticker.worker.kis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KisClientTest {

    @Test
    fun `isConfigured returns false when keys are blank`() {
        val client = KisClient(appKey = "", appSecret = "")
        assertThat(client.isConfigured).isFalse()
    }

    @Test
    fun `isConfigured returns true when keys are present`() {
        val client = KisClient(appKey = "test-key", appSecret = "test-secret")
        assertThat(client.isConfigured).isTrue()
    }

    @Test
    fun `getAccessToken returns null when not configured`() {
        val client = KisClient(appKey = "", appSecret = "")
        assertThat(client.getAccessToken()).isNull()
    }
}
