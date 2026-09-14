package com.monticker.api.common.search

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/** 로컬/dev 편의: ES가 비어 있을 수 있으니 기동 시 전량 동기화. 운영은 false — 관리자 재색인으로. */
@Component
class SearchReindexRunner(
    private val reindexers: List<SearchReindexer>,
    private val manager: SearchIndexManager,
    @Value("\${app.search.reindex-on-startup:false}") private val onStartup: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!onStartup) return
        reindexers.forEach { r ->
            runCatching { r.reindexAll() }
                .onSuccess { log.info("[SearchReindex] {} — {}건 동기화", r.index, it) }
                .onFailure { log.warn("[SearchReindex] {} 건너뜀: {}", r.index, it.message) }
        }
    }

    /** 관리자 재색인: 설계 매핑으로 인덱스를 다시 만들고 채운다. 동적 매핑 잔재를 지우는 유일한 방법. */
    fun recreateAndReindex(index: String): Int {
        val r = reindexers.firstOrNull { it.index == index } ?: throw NoSuchElementException("재색인 가능한 인덱스가 아닙니다: $index")
        manager.recreate(r.documentClass)
        return r.reindexAll()
    }

    fun indices(): List<String> = reindexers.map { it.index }
}
