package com.monticker.api.common.notification

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** ADR-102 — 원클릭 수신 거부 토큰 서명·검증 */
class UnsubscribeTokenServiceTest {

    private val secret = "unit-test-unsubscribe-secret-0123456789abcdef"
    private val tokens = UnsubscribeTokenService(secret).apply {
        clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC)
    }

    @Test
    fun `issued token verifies back to the same user and scope`() {
        val t = tokens.issue(42, UnsubscribeScope.WEEKLY_REPORT)
        assertThat(t).startsWith("v1.weekly_report.42.${Instant.parse("2026-10-12T00:00:00Z").epochSecond}.")
        assertThat(tokens.verify(t, UnsubscribeScope.WEEKLY_REPORT)).isEqualTo(42L)
        assertThat(tokens.verify(t)).isEqualTo(UnsubscribeClaim(42, UnsubscribeScope.WEEKLY_REPORT))
    }

    @Test
    fun `token is url safe and carries no email`() {
        val t = tokens.issue(Long.MAX_VALUE, UnsubscribeScope.WEEKLY_REPORT)
        assertThat(t).matches("[A-Za-z0-9._-]+").doesNotContain("@")
        assertThat(tokens.verify(t, UnsubscribeScope.WEEKLY_REPORT)).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `swapping the user id invalidates the signature`() {
        val t = tokens.issue(42, UnsubscribeScope.WEEKLY_REPORT)
        val other = t.replace(".42.", ".43.")
        assertThat(tokens.verify(other, UnsubscribeScope.WEEKLY_REPORT)).isNull()
    }

    @Test
    fun `tampering any part or the signature is rejected`() {
        val t = tokens.issue(42, UnsubscribeScope.WEEKLY_REPORT)
        val parts = t.split('.')
        val sig = parts[4]
        val flipped = (if (sig[0] == 'A') 'B' else 'A') + sig.substring(1)
        // 마지막 문자의 남는 비트만 바꾼 변형(디코딩하면 같은 바이트)도 거부한다
        val lastChar = sig.last()
        val sameBytesVariant = sig.dropLast(1) + (if (lastChar == 'A') 'B' else 'A')
        listOf(
            parts.take(4).joinToString(".") + "." + flipped,
            parts.take(4).joinToString(".") + "." + sameBytesVariant,
            t.replace(".${parts[3]}.", ".${parts[3].toLong() - 1}."),     // 발급 시각
            "v2" + t.removePrefix("v1"),                                   // 버전
            t + "A",
            t.dropLast(1),
            "",
            "garbage",
            "v1.weekly_report.42.1.",
            "v1.weekly_report.0.1.${sig}",
            "v1.weekly_report.-1.1.${sig}",
            t.uppercase(),
            "x".repeat(10_000),
        ).forEach { bad ->
            assertThat(tokens.verify(bad, UnsubscribeScope.WEEKLY_REPORT)).describedAs(bad.take(80)).isNull()
        }
        assertThat(tokens.verify(null, UnsubscribeScope.WEEKLY_REPORT)).isNull()
    }

    @Test
    fun `a token signed for another scope is not accepted for weekly report`() {
        // 아직 다른 범위가 없으니 같은 키로 다른 범위 코드에 서명한 토큰을 손으로 만든다
        val forged = forge(secret, "v1.marketing.42.1700000000")
        assertThat(tokens.verify(forged)).isNull()                                   // 모르는 범위
        assertThat(tokens.verify(forged, UnsubscribeScope.WEEKLY_REPORT)).isNull()
        // 서명이 맞는 정상 토큰의 범위 문자열만 바꾸면 서명이 깨진다
        val t = tokens.issue(42, UnsubscribeScope.WEEKLY_REPORT)
        assertThat(tokens.verify(t.replace("weekly_report", "marketing"))).isNull()
    }

    @Test
    fun `a token signed with another secret is rejected`() {
        val other = UnsubscribeTokenService("another-secret-of-at-least-32-bytes!!")
        val t = other.issue(42, UnsubscribeScope.WEEKLY_REPORT)
        assertThat(tokens.verify(t, UnsubscribeScope.WEEKLY_REPORT)).isNull()
    }

    @Test
    fun `tokens never expire (documented in ADR-102)`() {
        val old = UnsubscribeTokenService(secret).apply { clock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC) }
            .issue(7, UnsubscribeScope.WEEKLY_REPORT)
        assertThat(tokens.verify(old, UnsubscribeScope.WEEKLY_REPORT)).isEqualTo(7L)
    }

    @Test
    fun `short secrets are refused at startup`() {
        assertThatThrownBy { UnsubscribeTokenService("too-short") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("UNSUBSCRIBE_TOKEN_SECRET")
    }

    private fun forge(key: String, payload: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        val sig = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("monticker-unsubscribe|$payload".toByteArray()))
        return "$payload.$sig"
    }
}
