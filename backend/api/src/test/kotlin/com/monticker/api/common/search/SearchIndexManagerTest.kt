package com.monticker.api.common.search

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SearchIndexManagerTest {

    private val manager = SearchIndexManager(mockk(relaxed = true), SimpleMeterRegistry(), enabled = true)

    private fun props(vararg fields: Pair<String, Map<String, Any>>) = mapOf("properties" to fields.toMap())

    // CH-04가 본 실제 상태: 설계는 date/keyword/nori text 인데 인덱스는 첫 save()의 동적 매핑(text)이었다
    @Test
    fun `reports type and analyzer drift between the designed and the live mapping`() {
        val expected = props(
            "eventTime" to mapOf("type" to "date", "format" to "epoch_millis"),
            "symbol" to mapOf("type" to "keyword"),
            "title" to mapOf("type" to "text", "analyzer" to "nori_analyzer"),
            "importanceScore" to mapOf("type" to "integer"),
        )
        val actual = props(
            "eventTime" to mapOf("type" to "text"),
            "symbol" to mapOf("type" to "text"),
            "title" to mapOf("type" to "text"),          // 표준 분석기 — nori가 아니다
            "importanceScore" to mapOf("type" to "integer"),
        )

        val diffs = manager.compare(expected, actual)

        assertThat(diffs).hasSize(3)
        assertThat(diffs).anyMatch { it.startsWith("eventTime: type 기대 date / 실제 text") }
        assertThat(diffs).anyMatch { it.startsWith("symbol: type") }
        assertThat(diffs).anyMatch { it.startsWith("title: analyzer 기대 nori_analyzer") }
    }

    @Test
    fun `extra live fields are tolerated and a missing designed field is reported`() {
        val expected = props("title" to mapOf("type" to "text", "analyzer" to "nori_analyzer"), "memo" to mapOf("type" to "text"))
        val actual = props("title" to mapOf("type" to "text", "analyzer" to "nori_analyzer"), "leftover" to mapOf("type" to "long"))

        val diffs = manager.compare(expected, actual)

        assertThat(diffs).containsExactly("memo: 실제 매핑에 없음 (기대 text)")
    }

    @Test
    fun `every document class in the api is discovered so no index escapes management`() {
        val names = manager.documentClasses().map { it.simpleName }

        assertThat(names).contains("WatchlistItemDocument", "NewsDocument", "StockEventDocument", "StockDocument", "AlertHistoryDocument", "SummaryDocument")
    }
}
