package com.monticker.api.paper.infrastructure
import com.monticker.api.paper.domain.PaperAccount
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
interface PaperAccountRepository : JpaRepository<PaperAccount, Long> {
    fun findByUserId(userId: Long): Optional<PaperAccount>
}
