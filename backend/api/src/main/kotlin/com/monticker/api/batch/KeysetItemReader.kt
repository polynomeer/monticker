package com.monticker.api.batch

import org.springframework.batch.item.ExecutionContext
import org.springframework.batch.item.ItemStreamReader
import org.springframework.batch.item.ItemStreamSupport

/**
 * `id > 마지막으로 읽은 id`로 다음 페이지를 가져오는 리더.
 *
 * 처리하면 조회 조건에서 빠지는 대상(PENDING 정산, PENDING 결제, 만료 임박 구독)을 offset 페이징
 * (`RepositoryItemReader`)으로 읽으면, 청크가 커밋될 때마다 결과 집합이 줄어 다음 페이지가 그만큼을
 * 건너뛴다 — 50건 청크라면 2페이지째부터 50건씩 다음 실행으로 밀린다(2026-10 설계 리뷰 10a).
 * 키셋은 결과 집합이 줄어도 위치가 흔들리지 않고, 실패해 조건에 남은 건도 같은 실행에서 다시 집지 않는다.
 *
 * [fetch]는 `id > afterId`를 `id` 오름차순으로 최대 [pageSize]건 돌려줘야 한다.
 * 재시작 시 이어 읽도록 마지막 id를 ExecutionContext에 남긴다.
 *
 * `open`이어야 한다 — `@StepScope` 빈은 CGLIB 클래스 프록시로 감싸지는데, final 클래스는 상속할 수 없다.
 */
open class KeysetItemReader<T : Any>(
    name: String,
    private val pageSize: Int,
    private val idOf: (T) -> Long,
    private val fetch: (afterId: Long, limit: Int) -> List<T>,
) : ItemStreamSupport(), ItemStreamReader<T> {

    private var lastId = 0L
    private var buffer: ArrayDeque<T> = ArrayDeque()
    private var exhausted = false

    init {
        setName(name)
    }

    override fun open(executionContext: ExecutionContext) {
        lastId = executionContext.getLong(getExecutionContextKey(LAST_ID), 0L)
        buffer = ArrayDeque()
        exhausted = false
    }

    override fun update(executionContext: ExecutionContext) {
        executionContext.putLong(getExecutionContextKey(LAST_ID), lastId)
    }

    override fun read(): T? {
        if (buffer.isEmpty() && !exhausted) {
            val page = fetch(lastId, pageSize)
            if (page.size < pageSize) exhausted = true
            buffer.addAll(page)
        }
        val item = buffer.removeFirstOrNull() ?: return null
        lastId = idOf(item)
        return item
    }

    private companion object {
        const val LAST_ID = "lastId"
    }
}
