package com.monticker.api.quant.infrastructure

import com.monticker.api.quant.domain.RuleSetDocument
import org.springframework.data.mongodb.repository.MongoRepository
import java.util.Optional

/**
 * 이름 검색용 프로젝션 — 룰 정의·버전 이력 없이 id와 이름만 읽는다.
 * 클래스(DTO)여야 한다: 인터페이스 프로젝션은 Spring Data MongoDB가 먼저 [RuleSetDocument]를 만들려다
 * 읽지 않은 필수 필드(userId 등) 때문에 "Failed to instantiate"로 실패했다(로컬 스택에서 확인, 목 테스트는 못 잡음).
 */
data class RuleSetNameView(
    val id: String?,
    val name: String,
)

interface RuleSetRepository : MongoRepository<RuleSetDocument, String> {
    fun findAllByUserId(userId: Long): List<RuleSetDocument>
    fun findByIdAndUserId(id: String, userId: Long): Optional<RuleSetDocument>

    /** 전략 마켓 이름 검색 후보(공개 전략 ruleset_id)의 이름만 — StrategyMarketController.search */
    fun findByIdIn(ids: Collection<String>): List<RuleSetNameView>
}
