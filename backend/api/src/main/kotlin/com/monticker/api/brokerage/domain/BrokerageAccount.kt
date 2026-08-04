package com.monticker.api.brokerage.domain

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

    // AES-256 암호화 저장 (현재는 Mock이므로 평문 허용)
    @Column(name = "access_token", columnDefinition = "TEXT")
    var accessToken: String? = null,

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

    fun isTokenValid(): Boolean =
        accessToken != null && (tokenExpiresAt?.isAfter(Instant.now()) == true)
}
