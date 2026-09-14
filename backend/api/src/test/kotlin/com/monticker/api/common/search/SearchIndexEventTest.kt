package com.monticker.api.common.search

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.modulith.events.Externalized

class SearchIndexEventTest {

    // 키가 "{index}:{docId}"여야 같은 문서의 색인·삭제가 같은 파티션에 순서대로 들어간다 (ADR-042)
    @Test
    fun `is externalized to search index keyed by index and document id`() {
        val target = SearchIndexEvent::class.java.getAnnotation(Externalized::class.java).value

        assertThat(target).startsWith("search.index::")
        assertThat(target).contains("#this.index").contains("#this.docId")
    }

    @Test
    fun `a delete event carries no payload`() {
        val ev = SearchIndexEvent.delete("watchlist_items", "42")

        assertThat(ev.op).isEqualTo(SearchIndexEvent.Op.DELETE)
        assertThat(ev.payload).isNull()
    }
}

class SearchIndexKafkaConfigTest {
    // KafkaTemplate 빈이 둘이면 Boot 자동구성 템플릿이 물러나 Modulith 외부화가 컨버터 없는 템플릿을 쓴다 — 라이브에서 겪은 사고
    @Test
    fun `does not expose a second KafkaTemplate bean`() {
        val beanMethods = SearchIndexKafkaConfig::class.java.declaredMethods
            .filter { it.isAnnotationPresent(org.springframework.context.annotation.Bean::class.java) }
        assertThat(beanMethods.map { it.returnType.simpleName }).doesNotContain("KafkaTemplate")
    }
}
