package com.monticker.api.auth.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 라이브러리를 올려도 **이미 발급된 토큰**이 계속 검증돼야 한다 — 그렇지 않으면 배포 직후 로그인된 사용자가 전부
 * 로그아웃되고, 리프레시 토큰(7일)까지 무효가 된다. 아래 토큰은 jjwt 0.12.7이 발급했다(2026-10-07, 만료 2100년).
 * jjwt를 올릴 때(docs/dependency-upgrade-plan.md B3) 이 테스트가 통과해야 한다. 서명 키·iss·aud를 바꿀 때도 마찬가지다.
 */
class JwtTokenCompatibilityTest {

    private val provider = JwtTokenProvider(
        secret = "test-secret-key-that-is-at-least-32-bytes!!",
        accessTokenExpiryMs = 900_000L,
        refreshTokenExpiryMs = 604_800_000L,
    )

    @Test
    fun `jjwt 0_12_7이 발급한 access token을 검증하고 클레임을 읽는다`() {
        assertThat(provider.validateToken(ACCESS_0_12_7)).isTrue()
        assertThat(provider.getUserId(ACCESS_0_12_7)).isEqualTo(7L)
        assertThat(provider.getEmail(ACCESS_0_12_7)).isEqualTo("fixture@monticker.local")
        assertThat(provider.getRole(ACCESS_0_12_7)).isEqualTo("USER")
    }

    @Test
    fun `jjwt 0_12_7이 발급한 refresh token을 검증한다`() {
        assertThat(provider.validateToken(REFRESH_0_12_7)).isTrue()
        assertThat(provider.getUserId(REFRESH_0_12_7)).isEqualTo(7L)
    }

    @Test
    fun `서명을 바꾼 토큰은 여전히 거부한다`() {
        val tampered = ACCESS_0_12_7.dropLast(2) + if (ACCESS_0_12_7.endsWith("AA")) "BB" else "AA"
        assertThat(provider.validateToken(tampered)).isFalse()
    }

    private companion object {
        const val ACCESS_0_12_7 =
            "eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiJhNGE3MjI0Ny0wNDBmLTQxNzAtODlkZC02MzNmMTgxY2JjZDgiLCJzdWIiOiI3IiwiaXNzIjoibW9udGlja2VyLWFwaSIsImF1ZCI6WyJtb250aWNrZXItd2ViIl0sImVtYWlsIjoiZml4dHVyZUBtb250aWNrZXIubG9jYWwiLCJyb2xlIjoiVVNFUiIsImlhdCI6MTc5MTM4MjEwNSwiZXhwIjo0MTAyNDQ0ODAwfQ.X9sD92X-7Y5PZCIeC1RJ2UIe3jJvdnga0we_cbFBJcA"
        const val REFRESH_0_12_7 =
            "eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiI0NzcxY2NlMS1iMjM1LTRmMjMtOTkxYi1iODZmNzhjNjgxNmUiLCJzdWIiOiI3IiwiaXNzIjoibW9udGlja2VyLWFwaSIsImF1ZCI6WyJtb250aWNrZXItd2ViIl0sImlhdCI6MTc5MTM4MjEwNSwiZXhwIjo0MTAyNDQ0ODAwfQ.rBEfCG5pVC4F4k3NiC1EEzOAHRlKQv3S4LQHHs7Ws-c"
    }
}
