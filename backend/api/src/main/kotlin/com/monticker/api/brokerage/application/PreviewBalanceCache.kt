package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.infrastructure.BrokerageBalance
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-116 — 리스크 미리보기 전용 증권사 잔고 캐시. 입력이 바뀔 때마다 증권사를 부르지 않기 위한 것이다.
 *
 * 인스턴스별 메모리에 짧게만 둔다. 인스턴스가 여럿이면 각자 한 번씩 더 부를 뿐 판정이 틀어지지 않고,
 * 잔고를 Redis로 내보내지 않아도 된다. 동시 미스는 합치지 않는다 — 증권사 호출 동안 락을 잡지 않기 위해서이며,
 * 호출 횟수는 엔드포인트의 사용자별 호출 제한이 막는다.
 *
 * **실주문 게이트는 이 캐시를 쓰지 않는다.** 항상 새 잔고로 판정한다.
 */
internal class PreviewBalanceCache(
    private val ttl: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Entry(val balance: BrokerageBalance, val loadedAt: Instant)

    private val entries = ConcurrentHashMap<Long, Entry>()

    /** 실패는 저장하지 않는다 — 예외는 그대로 나가고 다음 호출이 다시 부른다. */
    fun getOrLoad(accountId: Long, load: () -> BrokerageBalance): BrokerageBalance {
        entries[accountId]?.takeIf { isFresh(it) }?.let { return it.balance }
        val balance = load()
        // 지나간 항목을 쓸 때 함께 비운다 — 맵 크기는 TTL 안에 미리보기를 쓴 계좌 수를 넘지 않는다.
        entries.values.removeIf { !isFresh(it) }
        entries[accountId] = Entry(balance, clock.instant())
        return balance
    }

    private fun isFresh(e: Entry) = Duration.between(e.loadedAt, clock.instant()) < ttl
}
