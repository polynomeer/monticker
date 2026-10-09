package com.monticker.api.common.config

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** docs/security-review.md C1 + ADR-102 — 공개된 개발용 시크릿·키 재사용으로는 기동하지 않는다. */
class InsecureSecretGuardTest {

    private val jwt = "fresh-jwt-secret-generated-for-this-test-0001"
    private val enc = "dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1sb25nLWFhYWE="
    private val unsub = "fresh-unsubscribe-secret-for-this-test-0002"

    @Test
    fun `fresh distinct secrets pass`() {
        assertThatCode { InsecureSecretGuard(jwt, enc, unsub, false).verify() }.doesNotThrowAnyException()
    }

    @Test
    fun `the committed dev unsubscribe secret is refused`() {
        assertThatThrownBy { InsecureSecretGuard(jwt, enc, "monticker-dev-unsubscribe-secret-not-for-production", false).verify() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("UNSUBSCRIBE_TOKEN_SECRET")
    }

    @Test
    fun `reusing the jwt secret for unsubscribe tokens is refused`() {
        assertThatThrownBy { InsecureSecretGuard(jwt, enc, jwt, false).verify() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("jwt.secret")
    }

    @Test
    fun `explicit local dev opt-in allows the committed defaults`() {
        assertThatCode {
            InsecureSecretGuard(
                "monticker-dev-secret-key-must-be-at-least-32-bytes-long",
                "HSKZ1kiMBZ80UfTmpyhmFXh6TtteeeweRhbngzaD4yk=",
                "monticker-dev-unsubscribe-secret-not-for-production",
                true,
            ).verify()
        }.doesNotThrowAnyException()
    }
}
