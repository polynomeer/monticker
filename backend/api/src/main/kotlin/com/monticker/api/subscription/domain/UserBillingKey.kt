package com.monticker.api.subscription.domain

import com.monticker.api.common.security.EncryptedStringConverter
import jakarta.persistence.*
import java.time.Instant

enum class BillingProvider { MOCK, TOSS }

/**
 * 정기결제(자동 갱신)용 빌링키. 사용자당 하나만 존재한다(user_id UNIQUE) — 카드를 다시
 * 등록하면 기존 레코드를 갱신한다.
 */
@Entity
@Table(name = "user_billing_keys")
class UserBillingKey(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false, unique = true)
    val userId: Long,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val provider: BillingProvider = BillingProvider.MOCK,

    @Column(name = "customer_key", nullable = false)
    var customerKey: String,

    // AES-256-GCM 암호화 저장 — 이 값만 있으면 결제를 실행할 수 있으므로
    // BrokerageAccount.accessToken과 동일한 민감도로 취급한다(EncryptedStringConverter 재사용).
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "billing_key", nullable = false, columnDefinition = "TEXT")
    var billingKeyValue: String,

    @Column(name = "card_company")
    var cardCompany: String? = null,

    @Column(name = "card_last4")
    var cardLast4: String? = null,

    @Column(name = "issued_at", nullable = false)
    val issuedAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun replace(billingKeyValue: String, cardCompany: String?, cardLast4: String?) {
        this.billingKeyValue = billingKeyValue
        this.cardCompany = cardCompany
        this.cardLast4 = cardLast4
        this.updatedAt = Instant.now()
    }
}
