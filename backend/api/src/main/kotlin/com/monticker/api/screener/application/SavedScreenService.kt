package com.monticker.api.screener.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.screener.domain.ScreenerCriteria
import com.monticker.api.screener.infrastructure.SavedScreenRepository
import com.monticker.api.screener.infrastructure.SavedScreenRow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class SavedScreen(
    val id: Long,
    val name: String,
    val criteria: ScreenerCriteria,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * ADR-072 — 스크리너 저장 조건. 조건은 쿼리 파라미터와 같은 [ScreenerCriteria.normalized] 검증을 통과한 것만 저장한다.
 * 사용자당 [MAX_PER_USER]개. 개수 확인과 INSERT 사이 경쟁은 사용자별 advisory lock으로 막는다.
 */
@Service
class SavedScreenService(
    private val repo: SavedScreenRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MAX_PER_USER = 20
        const val MAX_NAME_LENGTH = 40
    }

    @Transactional(readOnly = true)
    fun list(userId: Long): List<SavedScreen> = repo.findAll(userId).mapNotNull { it.toDomain() }

    @Transactional(readOnly = true)
    fun get(userId: Long, id: Long): SavedScreen =
        repo.find(userId, id)?.toDomain() ?: throw NoSuchElementException("저장한 스크린을 찾을 수 없습니다: $id")

    @Transactional
    fun create(userId: Long, name: String, criteria: ScreenerCriteria): SavedScreen {
        val cleanName = validName(name)
        val json = objectMapper.writeValueAsString(criteria.normalized())
        repo.lockUser(userId)
        if (repo.count(userId) >= MAX_PER_USER) throw BusinessRuleException("스크린은 최대 ${MAX_PER_USER}개까지 저장할 수 있습니다")
        if (repo.existsName(userId, cleanName)) throw BusinessRuleException("같은 이름의 스크린이 이미 있습니다")
        val id = repo.insert(userId, cleanName, json)
        return get(userId, id)
    }

    @Transactional
    fun update(userId: Long, id: Long, name: String, criteria: ScreenerCriteria): SavedScreen {
        val cleanName = validName(name)
        val json = objectMapper.writeValueAsString(criteria.normalized())
        repo.lockUser(userId)
        if (repo.find(userId, id) == null) throw NoSuchElementException("저장한 스크린을 찾을 수 없습니다: $id")
        if (repo.existsName(userId, cleanName, exceptId = id)) throw BusinessRuleException("같은 이름의 스크린이 이미 있습니다")
        repo.update(userId, id, cleanName, json)
        return get(userId, id)
    }

    @Transactional
    fun delete(userId: Long, id: Long) {
        if (!repo.delete(userId, id)) throw NoSuchElementException("저장한 스크린을 찾을 수 없습니다: $id")
    }

    private fun validName(name: String): String {
        val n = name.trim()
        require(n.isNotEmpty()) { "스크린 이름을 입력하세요" }
        require(n.length <= MAX_NAME_LENGTH) { "스크린 이름은 ${MAX_NAME_LENGTH}자 이하로 입력하세요" }
        return n
    }

    private fun SavedScreenRow.toDomain(): SavedScreen? = try {
        SavedScreen(id, name, objectMapper.readValue<ScreenerCriteria>(criteriaJson).normalized(), createdAt, updatedAt)
    } catch (e: Exception) {
        // 저장 후 조건 규칙이 바뀌어 더는 유효하지 않은 행 — 목록에서 빼고 남긴다(실행하면 잘못된 SQL이 될 수 있다)
        log.warn("saved screen {} has invalid criteria: {}", id, e.message)
        null
    }
}
