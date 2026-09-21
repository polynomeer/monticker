package com.monticker.api.auth.infrastructure

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

/**
 * 다른 모듈에서 JWT로부터 사용자 식별자를 추출할 때 사용하는 auth 모듈의 공개 API.
 * auth.infrastructure의 나머지 타입(OAuth2/필터 내부 구현)은 auth 모듈 내부로 한정된다.
 */
@NamedInterface("api")
@Component
class JwtTokenProvider(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.access-token-expiry-ms:900000}") private val accessTokenExpiryMs: Long,
    @Value("\${jwt.refresh-token-expiry-ms:604800000}") private val refreshTokenExpiryMs: Long,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))

    fun generateAccessToken(userId: Long, email: String, role: String): String {
        val now = System.currentTimeMillis()
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(userId.toString())
            .issuer(ISSUER)
            .audience().add(AUDIENCE).and()
            .claim("email", email)
            .claim("role", role)
            .issuedAt(Date(now))
            .expiration(Date(now + accessTokenExpiryMs))
            .signWith(key)
            .compact()
    }

    // jti — iat/exp는 초 단위라 같은 사용자에게 같은 초에 발급한 두 토큰은 바이트까지 동일했다.
    // refresh_tokens.token_hash에 UNIQUE가 걸려 있어 가입 직후 로그인(또는 연속 로그인)이
    // DuplicateKeyException으로 500을 냈다. 무작위 jti를 넣어 발급마다 토큰을 유일하게 만든다.
    fun generateRefreshToken(userId: Long): String {
        val now = System.currentTimeMillis()
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(userId.toString())
            .issuer(ISSUER)
            .audience().add(AUDIENCE).and()
            .issuedAt(Date(now))
            .expiration(Date(now + refreshTokenExpiryMs))
            .signWith(key)
            .compact()
    }

    fun validateToken(token: String): Boolean {
        return runCatching { parseClaims(token) }.isSuccess
    }

    fun getUserId(token: String): Long =
        parseClaims(token).subject.toLong()

    fun getEmail(token: String): String =
        parseClaims(token)["email"] as String

    fun getRole(token: String): String =
        parseClaims(token)["role"] as String

    fun refreshTokenExpiryMs(): Long = refreshTokenExpiryMs

    // L2 — iss/aud를 검증하면 이 서비스가 발급하지 않은(또는 다른 용도의) 토큰이 같은
    // JWT_SECRET을 알아도 여기서 곧바로 거부된다. 비밀키 자체가 새면 이 검증도 우회되지만,
    // 서명 키 재사용·설정 실수 같은 인접 실패 모드에 대한 방어선을 하나 더 둔다.
    private fun parseClaims(token: String): Claims =
        Jwts.parser().verifyWith(key)
            .requireIssuer(ISSUER)
            .requireAudience(AUDIENCE)
            .build().parseSignedClaims(token).payload

    companion object {
        private const val ISSUER = "monticker-api"
        private const val AUDIENCE = "monticker-web"
    }
}
