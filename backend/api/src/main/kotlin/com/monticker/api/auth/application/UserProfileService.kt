package com.monticker.api.auth.application

import com.monticker.api.auth.infrastructure.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** GET /api/users/me — 화면이 계정 표시에 쓰는 최소 정보. 비밀번호 해시·소셜 provider id 같은 내부 값은 내보내지 않는다. */
data class UserProfile(
    val id: Long,
    val email: String,
    val nickname: String,
    /** LOCAL | GOOGLE | KAKAO | NAVER — 가입 경로(설정의 계정 줄, 비밀번호 변경 노출 여부) */
    val provider: String,
    val emailVerified: Boolean,
    val createdAt: Instant,
)

@Service
class UserProfileService(private val users: UserRepository) {

    /** 탈퇴한 계정(익명화된 행)은 없는 사용자로 본다(ADR-114) */
    @Transactional(readOnly = true)
    fun get(userId: Long): UserProfile {
        val u = users.findById(userId).orElseThrow { NoSuchElementException("사용자를 찾을 수 없습니다") }
        if (u.isDeleted) throw NoSuchElementException("사용자를 찾을 수 없습니다")
        return UserProfile(u.id, u.email, u.nickname, u.provider, u.emailVerified, u.createdAt)
    }
}
