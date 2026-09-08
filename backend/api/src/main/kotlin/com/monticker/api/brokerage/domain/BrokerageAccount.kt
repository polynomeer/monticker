package com.monticker.api.brokerage.domain

import com.monticker.api.common.security.EncryptedStringConverter
import jakarta.persistence.*
import java.time.Instant

enum class BrokerageProvider { KIS, MOCK }
enum class BrokerageAccountType { REAL, DEMO }

@Entity
@Table(name = "brokerage_accounts")
class BrokerageAccount(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val provider: BrokerageProvider = BrokerageProvider.MOCK,

    @Column(name = "account_number", nullable = false)
    val accountNumber: String,

    @Column(name = "account_type", nullable = false)
    @Enumerated(EnumType.STRING)
    val accountType: BrokerageAccountType = BrokerageAccountType.REAL,

    // AES-256-GCM 암호화 저장 (EncryptedStringConverter) — docs/launch-plan.md Phase 0
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "access_token", columnDefinition = "TEXT")
    var accessToken: String? = null,

    // ADR-025: 토큰 발급 이후에도 매 인증 호출마다 appkey/appsecret 헤더가 필요해서 저장한다
    // (재발급 정책은 아직 없음 — 저장 자체만 이번 범위).
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "app_key", columnDefinition = "TEXT")
    var appKey: String? = null,

    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "app_secret", columnDefinition = "TEXT")
    var appSecret: String? = null,

    @Column(name = "token_expires_at")
    var tokenExpiresAt: Instant? = null,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "connected_at", nullable = false)
    val connectedAt: Instant = Instant.now(),
) {
    fun updateToken(token: String, expiresIn: Long) {
        this.accessToken = token
        this.tokenExpiresAt = Instant.now().plusSeconds(expiresIn)
    }

    fun updateCredentials(appKey: String, appSecret: String) {
        this.appKey = appKey
        this.appSecret = appSecret
    }

    fun isTokenValid(): Boolean =
        accessToken != null && (tokenExpiresAt?.isAfter(Instant.now()) == true)
}
