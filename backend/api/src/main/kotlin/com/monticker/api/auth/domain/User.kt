package com.monticker.api.auth.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "users")
class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    var email: String,

    @Column(name = "password_hash")
    var passwordHash: String? = null,

    @Column(nullable = false)
    var nickname: String,

    @Column(nullable = false)
    val provider: String = "LOCAL",

    @Column(name = "provider_id")
    var providerId: String? = null,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var role: UserRole = UserRole.USER,

    @Column(nullable = false)
    var emailVerified: Boolean = false,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),

    var deletedAt: Instant? = null,
) {
    val isDeleted get() = deletedAt != null

    /**
     * ADR-109 — 탈퇴. 행은 남긴다(결제·실거래·동의 기록이 이 id를 참조하고 보존 의무가 있다). 사람을 알아볼 수 있는 값은 지운다:
     * 이메일은 id로 만든 주소로 바꿔 UNIQUE를 지키면서 같은 이메일로 바로 다시 가입할 수 있게 하고, 소셜 연결(provider_id)도
     * 끊어 같은 소셜 계정의 재가입이 새 계정이 되게 한다. 커뮤니티 댓글 작성자는 이 닉네임으로 보인다.
     */
    fun anonymize(at: Instant = Instant.now()) {
        email = "deleted-$id@deleted.invalid"
        nickname = DELETED_NICKNAME
        passwordHash = null
        providerId = null
        emailVerified = false
        deletedAt = at
        updatedAt = at
    }

    companion object {
        const val DELETED_NICKNAME = "탈퇴한 사용자"
    }
}

enum class UserRole { USER, ADMIN, SYSTEM_WORKER }
