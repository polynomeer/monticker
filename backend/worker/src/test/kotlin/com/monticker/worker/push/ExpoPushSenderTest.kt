package com.monticker.worker.push

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExpoPushSenderTest {
    private val sender = ExpoPushSender()

    @Test
    fun `PushMessage has correct defaults`() {
        val msg = PushMessage(to = "token", title = "t", body = "b")
        assertThat(msg.data).isEmpty()
        assertThat(msg.sound).isEqualTo("default")
        assertThat(msg.priority).isEqualTo("high")
    }

    @Test
    fun `send returns empty list when messages is empty`() {
        val results = sender.send(emptyList())
        assertThat(results).isEmpty()
    }

    @Test
    fun `PushMessage token and title are stored correctly`() {
        val msg = PushMessage(
            to = "ExponentPushToken[test]",
            title = "테스트",
            body = "본문",
        )
        assertThat(msg.to).isEqualTo("ExponentPushToken[test]")
        assertThat(msg.title).isEqualTo("테스트")
        assertThat(msg.body).isEqualTo("본문")
    }

    @Test
    fun `PushResult stores status and message`() {
        val result = PushResult(token = "tok", status = "ok", message = null)
        assertThat(result.status).isEqualTo("ok")
        assertThat(result.message).isNull()
    }
}
