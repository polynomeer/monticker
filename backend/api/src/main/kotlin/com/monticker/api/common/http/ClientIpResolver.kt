package com.monticker.api.common.http

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.InetAddress

/**
 * 요청의 클라이언트 IP를 정하는 단일 지점 (ADR-084).
 *
 * 기본은 `request.remoteAddr`(TCP 피어)다. `X-Forwarded-For`는 **직전 피어가 신뢰 프록시일 때만** 읽고,
 * 오른쪽(가장 최근 홉)부터 신뢰 프록시를 건너뛰며 처음 나오는 신뢰하지 않는 주소를 클라이언트로 본다.
 * 왼쪽 값은 클라이언트가 마음대로 써 넣을 수 있으므로 절대 첫 값을 믿지 않는다 — 이전 구현(첫 값 신뢰)은
 * 헤더 한 줄로 IP 레이트리밋을 우회하거나 남의 IP 버킷을 소진시킬 수 있었다.
 *
 * - `app.http.trusted-proxies`: 쉼표 구분 CIDR/주소 목록(예: ingress-nginx 파드 CIDR). 기본값은 비어 있음 =
 *   어떤 헤더도 믿지 않음(로컬·직접 연결에 안전한 기본값). 잘못된 CIDR은 기동 실패로 드러낸다.
 * - 헤더 값이 깨져 있으면(호스트명, 포트 포함, 빈 항목 등) 예외 없이 remoteAddr로 돌아간다.
 * - 주소는 리터럴로만 해석한다 — 호스트명이 와도 DNS 조회를 하지 않는다.
 */
@Component
class ClientIpResolver(
    @Value("\${app.http.trusted-proxies:}") trustedProxies: String,
) {
    private val trusted: List<Cidr> = trustedProxies.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { Cidr.parse(it) ?: throw IllegalArgumentException("app.http.trusted-proxies: 잘못된 CIDR/주소 '$it'") }

    fun resolve(request: HttpServletRequest): String {
        val remote = request.remoteAddr ?: return UNKNOWN
        val peer = parseIpLiteral(remote) ?: return remote
        if (trusted.isEmpty() || !isTrusted(peer)) return peer.hostAddress

        val hops = request.getHeaders(X_FORWARDED_FOR)?.toList().orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim() }

        var candidate = peer
        for (raw in hops.asReversed()) {
            val addr = parseIpLiteral(raw) ?: return peer.hostAddress
            if (!isTrusted(addr)) return addr.hostAddress
            candidate = addr
        }
        // 체인 전체가 신뢰 프록시(클러스터 내부 발신 등) — 가장 먼 홉을 클라이언트로 본다.
        return candidate.hostAddress
    }

    private fun isTrusted(addr: InetAddress) = trusted.any { it.contains(addr) }

    internal class Cidr(private val network: ByteArray, private val prefix: Int) {
        fun contains(addr: InetAddress): Boolean {
            val bytes = addr.address
            if (bytes.size != network.size) return false
            val full = prefix / 8
            for (i in 0 until full) if (bytes[i] != network[i]) return false
            val rem = prefix % 8
            if (rem == 0) return true
            val mask = (0xFF shl (8 - rem)) and 0xFF
            return (bytes[full].toInt() and mask) == (network[full].toInt() and mask)
        }

        companion object {
            fun parse(text: String): Cidr? {
                val slash = text.indexOf('/')
                val addr = parseIpLiteral(if (slash < 0) text else text.substring(0, slash)) ?: return null
                val maxBits = addr.address.size * 8
                val prefix = if (slash < 0) maxBits else text.substring(slash + 1).toIntOrNull() ?: return null
                if (prefix !in 0..maxBits) return null
                return Cidr(addr.address, prefix)
            }
        }
    }

    companion object {
        const val X_FORWARDED_FOR = "X-Forwarded-For"
        const val UNKNOWN = "unknown"

        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
        private val IPV6_CHARS = Regex("""^[0-9a-fA-F:][0-9a-fA-F:.]*$""")

        /**
         * IP 리터럴만 받아들인다. 호스트명·포트·존 ID 등은 null.
         * IPv4는 직접 파싱하고, IPv6는 문자 집합을 먼저 검증해 JDK가 리터럴 경로로만 해석하게 한다(DNS 조회 없음).
         * IPv4-mapped IPv6(::ffff:a.b.c.d)는 JDK가 Inet4Address로 돌려준다.
         */
        internal fun parseIpLiteral(raw: String): InetAddress? {
            var s = raw.trim()
            if (s.isEmpty() || s.length > 45 + 2) return null
            if (s.startsWith('[') && s.endsWith(']')) s = s.substring(1, s.length - 1)

            IPV4.matchEntire(s)?.let { m ->
                val octets = m.groupValues.drop(1).map { it.toInt() }
                if (octets.any { it > 255 }) return null
                return InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())
            }
            if (!s.contains(':') || !IPV6_CHARS.matches(s)) return null
            return runCatching { InetAddress.getByName(s) }.getOrNull()
        }
    }
}
