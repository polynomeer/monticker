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
            .claim(TYPE_CLAIM, TYPE_ACCESS)
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
            .claim(TYPE_CLAIM, TYPE_REFRESH)
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

    /**
     * refresh token에서만 사용자 ID를 읽는다 — access token이면 [IllegalArgumentException].
     * 두 토큰은 같은 키·iss·aud로 서명되므로 서명 검증만으로는 구분되지 않는다. 구분하지 않으면 access token을 refresh 경로에
     * 넣었을 때 DB에 행이 없어 재사용 탐지(모든 세션 폐기)가 돌아, access token을 가진 쪽(XSS·로그 유출)이 피해자를 계속
     * 로그아웃시킬 수 있다(ADR-109 보안 검토 M1). `typ` 클레임 이전에 발급된 토큰은 role 클레임 유무로 가른다.
     */
    fun getRefreshUserId(token: String): Long {
        val claims = parseClaims(token)
        val type = claims[TYPE_CLAIM] as? String
        require(type == TYPE_REFRESH || (type == null && claims["role"] == null)) { "refresh token이 아닙니다." }
        return claims.subject.toLong()
    }

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
        private const val TYPE_CLAIM = "typ"
        private const val TYPE_ACCESS = "access"
        private const val TYPE_REFRESH = "refresh"
    }
}
