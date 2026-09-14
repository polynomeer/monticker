package com.monticker.api.common.search

import com.monticker.api.common.aop.Audited
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** ADR-042 §5 — 인덱스 상태 조회와 재색인. 재색인은 인덱스를 지우고 다시 만드니 검색이 잠시 비는 것을 감수한다. */
@RestController
@RequestMapping("/api/admin/search")
@PreAuthorize("hasRole('ADMIN')")
@Audited
class SearchAdminController(
    private val manager: SearchIndexManager,
    private val runner: SearchReindexRunner,
) {
    @GetMapping("/indices")
    fun indices(): ResponseEntity<List<SearchIndexManager.IndexStatus>> = ResponseEntity.ok(manager.ensureAll())

    @PostMapping("/reindex/{index}")
    fun reindex(@PathVariable index: String): ResponseEntity<Map<String, Any>> {
        val n = runner.recreateAndReindex(index)
        return ResponseEntity.ok(mapOf("index" to index, "documents" to n))
    }
}
