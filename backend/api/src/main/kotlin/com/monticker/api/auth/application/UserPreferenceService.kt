package com.monticker.api.auth.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 온보딩 "관심 분야". 이름은 V86 `user_preferences_sectors_chk`와 같아야 한다. 화면 라벨은 웹이 갖는다. */
enum class InterestSector {
    SEMICONDUCTOR, SECONDARY_BATTERY, INTERNET_PLATFORM, BIO, FINANCE, AUTOMOTIVE, DIVIDEND, ETF, SHIPBUILDING_DEFENSE,
}

/** 온보딩 "어떻게 쓰고 싶으세요?". V86 `user_preferences_style_chk`와 같아야 한다. */
enum class UsageStyle { OBSERVE, EVENT_TRADING, QUANT }

data class UserPreferences(
    val interestSectors: List<InterestSector> = emptyList(),
    val usageStyle: UsageStyle? = null,
    /** 저장한 적이 없으면 null(기본값). */
    val updatedAt: Instant? = null,
)

/**
 * ADR-089 — 관심 분야·사용 방식 저장/조회. 지금은 **저장·조회만** 한다 — 홈·알림 우선순위 반영은 후속 작업이다
 * (docs/design-rollout-plan.md §6). 사용자는 토큰의 userId로만 정해진다(요청 본문·경로에 userId가 없다).
 */
@Service
class UserPreferenceService(private val jdbc: JdbcTemplate) {

    companion object {
        /** 본문 목록 길이 상한 — 중복 제거 전 원본 기준. 분야 수(9)보다 넉넉하되 큰 배열로 서버를 괴롭히지 못하게. */
        const val MAX_RAW_SECTORS = 20

        /** 화이트리스트 검증 + 중복 제거(처음 나온 순서 유지). 모르는 값·과도한 길이는 400(IllegalArgumentException). */
        fun parse(rawSectors: List<String?>?, rawStyle: String?): Pair<List<InterestSector>, UsageStyle?> {
            val raw = rawSectors.orEmpty()
            require(raw.size <= MAX_RAW_SECTORS) { "interestSectors는 최대 ${MAX_RAW_SECTORS}개까지 보낼 수 있습니다" }
            val sectors = raw.map { s ->
                InterestSector.entries.firstOrNull { it.name == s }
                    ?: throw IllegalArgumentException("interestSectors는 ${InterestSector.entries.joinToString()} 중에서 골라야 합니다")
            }.distinct()
            val style = rawStyle?.let { s ->
                UsageStyle.entries.firstOrNull { it.name == s }
                    ?: throw IllegalArgumentException("usageStyle은 ${UsageStyle.entries.joinToString()} 중 하나여야 합니다")
            }
            return sectors to style
        }
    }

    @Transactional(readOnly = true)
    fun get(userId: Long): UserPreferences =
        jdbc.query(
            "SELECT interest_sectors, usage_style, updated_at FROM user_preferences WHERE user_id = ?",
            { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                val arr = (rs.getArray("interest_sectors")?.array as? Array<Any?>).orEmpty()
                UserPreferences(
                    // DB CHECK가 막지만, 나중에 enum에서 값을 뺐을 때 조회가 500이 되지 않게 모르는 값은 버린다
                    interestSectors = arr.mapNotNull { v -> InterestSector.entries.firstOrNull { it.name == v } },
                    usageStyle = rs.getString("usage_style")?.let { v -> UsageStyle.entries.firstOrNull { it.name == v } },
                    updatedAt = rs.getTimestamp("updated_at")?.toInstant(),
                )
            },
            userId,
        ).firstOrNull() ?: UserPreferences()

    @Transactional
    fun save(userId: Long, sectors: List<InterestSector>, style: UsageStyle?): UserPreferences {
        jdbc.update { con ->
            con.prepareStatement(
                """
                INSERT INTO user_preferences (user_id, interest_sectors, usage_style, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (user_id) DO UPDATE SET
                    interest_sectors = EXCLUDED.interest_sectors, usage_style = EXCLUDED.usage_style, updated_at = now()
                """.trimIndent(),
            ).apply {
                setLong(1, userId)
                setArray(2, con.createArrayOf("varchar", sectors.map { it.name }.toTypedArray()))
                setString(3, style?.name)
            }
        }
        return get(userId)
    }
}
