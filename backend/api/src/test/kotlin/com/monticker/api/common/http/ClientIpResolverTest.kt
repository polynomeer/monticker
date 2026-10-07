package com.monticker.api.common.http

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest

/** ADR-084 — X-Forwarded-For는 신뢰 프록시를 거쳤을 때만, 오른쪽부터 읽는다. */
class ClientIpResolverTest {

    private val ingressCidr = "10.244.0.0/16"

    private fun request(remote: String, vararg xff: String) = MockHttpServletRequest().apply {
        remoteAddr = remote
        xff.forEach { addHeader("X-Forwarded-For", it) }
    }

    @Test
    fun `설정이 비어 있으면 헤더를 무시하고 remoteAddr를 쓴다`() {
        val resolver = ClientIpResolver("")
        assertThat(resolver.resolve(request("203.0.113.7", "1.1.1.1"))).isEqualTo("203.0.113.7")
    }

    @Test
    fun `공백·빈 항목만 있는 설정도 비어 있는 것으로 본다`() {
        val resolver = ClientIpResolver(" , ")
        assertThat(resolver.resolve(request("203.0.113.7", "1.1.1.1"))).isEqualTo("203.0.113.7")
    }

    @Test
    fun `신뢰하지 않는 피어가 보낸 스푸핑 XFF는 무시한다`() {
        val resolver = ClientIpResolver(ingressCidr)
        assertThat(resolver.resolve(request("203.0.113.7", "6.6.6.6"))).isEqualTo("203.0.113.7")
    }

    @Test
    fun `신뢰 프록시 뒤 - 클라이언트가 왼쪽에 써 넣은 값이 아니라 프록시가 덧붙인 값을 쓴다`() {
        // ingress-nginx $proxy_add_x_forwarded_for: "<클라이언트가 보낸 값>, <ingress가 본 $remote_addr>"
        val resolver = ClientIpResolver(ingressCidr)
        assertThat(resolver.resolve(request("10.244.1.5", "6.6.6.6, 198.51.100.20"))).isEqualTo("198.51.100.20")
    }

    @Test
    fun `XFF 없이 신뢰 프록시에서 온 요청은 프록시 주소를 쓴다`() {
        val resolver = ClientIpResolver(ingressCidr)
        assertThat(resolver.resolve(request("10.244.1.5"))).isEqualTo("10.244.1.5")
    }

    @Test
    fun `여러 신뢰 홉을 오른쪽부터 건너뛴다`() {
        // 클라이언트 → LB(192.0.2.10) → ingress(10.244.1.5) → api
        val resolver = ClientIpResolver("$ingressCidr, 192.0.2.10")
        val req = request("10.244.1.5", "6.6.6.6, 198.51.100.20, 192.0.2.10")
        assertThat(resolver.resolve(req)).isEqualTo("198.51.100.20")
    }

    @Test
    fun `여러 XFF 헤더 줄은 순서대로 이어 붙여 해석한다`() {
        val resolver = ClientIpResolver(ingressCidr)
        val req = request("10.244.1.5", "6.6.6.6", "198.51.100.20, 10.244.9.9")
        assertThat(resolver.resolve(req)).isEqualTo("198.51.100.20")
    }

    @Test
    fun `체인 전체가 신뢰 프록시면 가장 먼 홉을 쓴다`() {
        val resolver = ClientIpResolver(ingressCidr)
        assertThat(resolver.resolve(request("10.244.1.5", "10.244.3.3, 10.244.2.2"))).isEqualTo("10.244.3.3")
    }

    @ParameterizedTest
    @ValueSource(strings = ["evil.example.com", "198.51.100.20:4567", "999.1.1.1", "1.2.3", "", "unknown", "1.1.1.1, ", "::g"])
    fun `깨진 XFF 값은 예외 없이 remoteAddr로 돌아간다`(xff: String) {
        val resolver = ClientIpResolver(ingressCidr)
        assertThat(resolver.resolve(request("10.244.1.5", xff))).isEqualTo("10.244.1.5")
    }

    @Test
    fun `깨진 값이 신뢰하지 않는 주소보다 왼쪽에 있으면 영향이 없다`() {
        val resolver = ClientIpResolver(ingressCidr)
        assertThat(resolver.resolve(request("10.244.1.5", "garbage, 198.51.100.20"))).isEqualTo("198.51.100.20")
    }

    @Test
    fun `IPv6 클라이언트와 IPv6 신뢰 프록시`() {
        val resolver = ClientIpResolver("fd00::/8")
        val req = request("fd00::1", "2001:db8::abcd")
        assertThat(resolver.resolve(req)).isEqualTo("2001:db8:0:0:0:0:0:abcd")
    }

    @Test
    fun `대괄호 IPv6와 IPv4-mapped IPv6를 해석한다`() {
        val resolver = ClientIpResolver(ingressCidr)
        assertThat(resolver.resolve(request("10.244.1.5", "[2001:db8::1]"))).isEqualTo("2001:db8:0:0:0:0:0:1")
        assertThat(resolver.resolve(request("::ffff:10.244.1.5", "198.51.100.20"))).isEqualTo("198.51.100.20")
    }

    @Test
    fun `IPv4 CIDR는 IPv6 주소와 매치하지 않는다`() {
        val resolver = ClientIpResolver("0.0.0.0/0")
        assertThat(resolver.resolve(request("2001:db8::1", "6.6.6.6"))).isEqualTo("2001:db8:0:0:0:0:0:1")
    }

    @Test
    fun `비트 경계가 아닌 프리픽스`() {
        val resolver = ClientIpResolver("10.0.0.0/12") // 10.0.0.0 ~ 10.15.255.255
        assertThat(resolver.resolve(request("10.15.0.1", "198.51.100.20"))).isEqualTo("198.51.100.20")
        assertThat(resolver.resolve(request("10.16.0.1", "198.51.100.20"))).isEqualTo("10.16.0.1")
    }

    @Test
    fun `remoteAddr가 IP 리터럴이 아니면 그대로 돌려준다`() {
        val resolver = ClientIpResolver(ingressCidr)
        assertThat(resolver.resolve(request("localhost", "6.6.6.6"))).isEqualTo("localhost")
    }

    @ParameterizedTest
    @ValueSource(strings = ["10.0.0.0/33", "not-a-cidr", "10.0.0.0/x", "::/129"])
    fun `잘못된 신뢰 프록시 설정은 기동 시 실패한다`(config: String) {
        assertThatThrownBy { ClientIpResolver(config) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
