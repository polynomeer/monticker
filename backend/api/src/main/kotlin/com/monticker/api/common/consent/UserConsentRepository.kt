package com.monticker.api.common.consent

import org.springframework.data.jpa.repository.JpaRepository

interface UserConsentRepository : JpaRepository<UserConsent, Long> {
    fun findAllByUserIdOrderByRecordedAtDescIdDesc(userId: Long): List<UserConsent>
}
