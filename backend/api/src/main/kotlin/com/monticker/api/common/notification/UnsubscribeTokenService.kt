package com.monticker.api.common.notification

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** ADR-102 — 로그인 없이 끌 수 있는 이메일 종류. [code]는 토큰에 그대로 들어가므로 바꾸면 이미 보낸 링크가 무효가 된다. */
enum class UnsubscribeScope(val code: String) {
    WEEKLY_REPORT("weekly_report"),
    ;

    companion object {
        fun ofCode(code: String): UnsubscribeScope? = entries.firstOrNull { it.code == code }
    }
}

data class UnsubscribeClaim(val userId: Long, val scope: UnsubscribeScope)

/**
 * ADR-102 — 이메일 원클릭 수신 거부(RFC 8058) 토큰. 서버에 아무것도 저장하지 않는 HMAC-SHA256 서명 토큰이다.
 *
 * 형식: `v1.{scope}.{userId}.{issuedAtEpochSec}.{base64url(HMAC)}` — 서명 대상은 마지막 점 앞 전체(도메인 구분 접두어 포함).
 * - 이메일 주소는 담지 않는다. 사용자 id(내부 숫자)는 평문이다 — 링크를 본 사람은 그 id를 알 수 있지만 그 id로 할 수 있는 건
 *   서명이 맞는 토큰의 그 범위(주간 리포트 끄기)뿐이다.
 * - **만료 없음**: 수신 거부 링크는 몇 달 뒤 메일함에서 눌러도 동작해야 한다(CAN-SPAM은 발송 후 30일 이상 유효를 요구,
 *   정보통신망법 제50조도 수신 거부를 쉽게 할 것을 요구한다). 링크가 새도 할 수 있는 일은 "그 사람이 리포트를 덜 받는 것"뿐이다.
 *   모든 링크를 한꺼번에 무효화하려면 시크릿을 교체한다(그 뒤엔 메일 안의 '알림 설정' 링크로 끈다). issuedAt은 나중에
 *   "이 시각 이전 발급분 거부"를 시크릿 교체 없이 하려고 남겨 둔다.
 * - 비교는 상수 시간([MessageDigest.isEqual]). 형식이 틀린 토큰은 서명 계산 전에 거부하지만, 그 판정은 공개된 형식 규칙뿐이라
 *   타이밍으로 알 수 있는 비밀이 없다.
 */
@Component
class UnsubscribeTokenService(
    @Value("\${app.unsubscribe.secret}") secret: String,
) {
    /** 테스트에서 발급 시각을 고정할 때만 바꾼다. */
    var clock: Clock = Clock.systemUTC()

    private val key: SecretKeySpec

    init {
        val bytes = secret.toByteArray(Charsets.UTF_8)
        require(bytes.size >= MIN_SECRET_BYTES) {
            "app.unsubscribe.secret(UNSUBSCRIBE_TOKEN_SECRET)는 최소 ${MIN_SECRET_BYTES}바이트여야 합니다 — `openssl rand -base64 48`로 발급하세요"
        }
        key = SecretKeySpec(bytes, ALGORITHM)
    }

    fun issue(userId: Long, scope: UnsubscribeScope): String {
        require(userId > 0) { "userId must be positive" }
        val payload = "$VERSION.${scope.code}.$userId.${clock.instant().epochSecond}"
        return "$payload.${b64(mac(payload))}"
    }

    /** 서명이 맞고 형식이 올바르면 그 주장을, 아니면 null. 어떤 경우에도 예외를 던지지 않는다. */
    fun verify(token: String?): UnsubscribeClaim? {
        if (token == null || token.length > MAX_TOKEN_LENGTH) return null
        val m = TOKEN.matchEntire(token) ?: return null
        val (scopeCode, userIdText, _, sig) = m.destructured
        val payload = token.substring(0, token.lastIndexOf('.'))
        // 인코딩한 문자열끼리 비교한다 — 디코딩해 바이트를 비교하면 마지막 문자의 남는 비트만 다른 변형 토큰도 통과한다
        if (!MessageDigest.isEqual(b64(mac(payload)).toByteArray(Charsets.US_ASCII), sig.toByteArray(Charsets.US_ASCII))) return null
        val scope = UnsubscribeScope.ofCode(scopeCode) ?: return null
        val userId = userIdText.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return UnsubscribeClaim(userId, scope)
    }

    /** [verify] + 범위 확인 — 다른 범위로 발급된 토큰이면 null. */
    fun verify(token: String?, scope: UnsubscribeScope): Long? = verify(token)?.takeIf { it.scope == scope }?.userId

    private fun mac(payload: String): ByteArray =
        Mac.getInstance(ALGORITHM).apply { init(key) }.doFinal("$DOMAIN|$payload".toByteArray(Charsets.UTF_8))

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        const val MIN_SECRET_BYTES = 32
        private const val ALGORITHM = "HmacSHA256"
        private const val VERSION = "v1"
        /** 같은 시크릿이 혹시 다른 서명 용도에 재사용돼도 서로의 서명이 섞이지 않게 하는 도메인 구분자 */
        private const val DOMAIN = "monticker-unsubscribe"
        private const val MAX_TOKEN_LENGTH = 160
        // HMAC-SHA256 = 32바이트 → base64url(무패딩) 43자
        private val TOKEN = Regex("""v1\.([a-z_]{1,32})\.([1-9][0-9]{0,18})\.([0-9]{1,12})\.([A-Za-z0-9_-]{43})""")
    }
}
