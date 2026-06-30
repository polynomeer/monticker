# 체결 엔진 — CLOB 기반 주문 매칭

## 1. 개요

대부분의 모의투자 앱은 "현재가에 즉시 전량 체결"로 매수/매도를 구현한다. 이 방식은 구현이 간단하지만 실제 거래소의 체결 메커니즘과 무관하다. monticker는 실거래소가 사용하는 **Central Limit Order Book(CLOB)** 구조를 별도 모듈(`matching`)로 구현해, 가격·시간 우선 원칙에 따른 진짜 매칭 로직을 갖는다.

기존 `PaperTradingService`(단순 즉시체결)와 신규 `MatchingService`(CLOB)는 공존한다. 후자는 호가창 자료구조, 부분 체결, 슬리피지를 시연하기 위한 별도 경로다.

---

## 2. Order Book 자료구조

```kotlin
class OrderBook(val stockId: Long) {
    val asks = TreeMap<BigDecimal, ArrayDeque<Order>>()                  // 매도: 낮은 가격 우선
    val bids = TreeMap<BigDecimal, ArrayDeque<Order>>(reverseOrder())    // 매수: 높은 가격 우선
}
```

`TreeMap`의 키 비교자만 바꾸면 매수/매도의 정렬 방향이 자동으로 뒤집힌다. 같은 가격대의 여러 주문은 `ArrayDeque`로 FIFO를 유지해 시간 우선 원칙을 구현한다.

```kotlin
fun addBid(order: Order) {
    bids.getOrPut(order.limitPrice!!) { ArrayDeque() }.addLast(order)
}
```

가격 레벨에 주문이 더 이상 없으면 해당 키를 맵에서 제거한다. 이를 누락하면 `getBestBid()`/`getBestAsk()`가 빈 큐를 가리키는 가격을 반환할 수 있다.

```kotlin
fun removeOrder(orderId: Long, side: OrderSide): Boolean {
    val book = if (side == OrderSide.BUY) bids else asks
    for ((price, deque) in book) {
        val removed = deque.removeIf { it.id == orderId }
        if (removed) {
            if (deque.isEmpty()) book.remove(price)   // 빈 레벨 정리
            return true
        }
    }
    return false
}
```

### 발견된 버그: `getBestBid()`의 즉시평가 문제

```kotlin
// 버그가 있던 버전
fun getBestBid(): BigDecimal? = bids.firstKey().takeIf { bids.isNotEmpty() }
```

`firstKey()`는 `takeIf`보다 먼저 평가된다. `bids`가 비어 있으면 `TreeMap.firstKey()`가 `NoSuchElementException`을 던지므로, `takeIf { isNotEmpty() }` 가드는 결코 실행되지 않는다. nullable 반환 타입이 암시하는 "비었으면 null"이라는 계약이 실제로는 지켜지지 않았다.

```kotlin
// 수정된 버전
fun getBestBid(): BigDecimal? = if (bids.isEmpty()) null else bids.firstKey()
```

이 버그는 테스트 작성 과정(`OrderBookTest`)에서 발견했다. 빈 호가창에 대한 동작을 명시적으로 검증하지 않았다면 운영 환경에서 예외로 드러났을 것이다.

---

## 3. 매칭 알고리즘

매칭은 주문 유형에 따라 두 경로로 나뉜다.

```kotlin
fun submit(order: Order): List<MatchResult> {
    val book = getOrCreate(order.stockId)
    synchronized(book) {
        when (order.orderType) {
            OrderType.MARKET -> matchMarket(book, order, results)
            OrderType.LIMIT  -> matchLimit(book, order, results)
        }
    }
    return results
}
```

`synchronized(book)`으로 종목별 호가창 단위 락을 건다. 전체 서비스를 락하지 않고 종목별로만 직렬화하여 서로 다른 종목의 주문은 병렬로 처리된다.

### MARKET 주문

반대편 호가창의 최우선 가격부터 수량을 모두 소진할 때까지 순회한다.

```kotlin
private fun matchAgainstBook(
    counterBook: TreeMap<BigDecimal, ArrayDeque<Order>>,
    taker: Order, results: MutableList<MatchResult>, priceLimit: ((BigDecimal) -> Boolean)?,
) {
    while (taker.remainingQty > 0 && counterBook.isNotEmpty()) {
        val bestEntry = counterBook.firstEntry() ?: break
        if (priceLimit != null && !priceLimit(bestEntry.key)) break

        val maker = bestEntry.value.first()
        val matchQty = minOf(taker.remainingQty, maker.remainingQty)
        results.add(MatchResult(maker.id, taker.id, bestEntry.key, matchQty))

        maker.filledQty += matchQty
        taker.filledQty += matchQty
        if (maker.remainingQty == 0) {
            bestEntry.value.removeFirst()
            if (bestEntry.value.isEmpty()) counterBook.remove(bestEntry.key)
        }
    }
}
```

`priceLimit = null`이면 MARKET 주문이므로 가격 조건 없이 끝까지 소진한다. 한 레벨의 수량이 부족하면 다음 레벨로 자동 이동하며 — 이것이 **슬리피지**가 발생하는 지점이다. 예를 들어 1,000주 매수 시 300주@50,000원, 400주@50,100원, 300주@50,200원로 분할 체결되면 평균 체결가는 단순 호가보다 불리해진다.

### LIMIT 주문

```kotlin
private fun matchLimit(book: OrderBook, taker: Order, results: MutableList<MatchResult>) {
    val priceFilter = if (taker.side == OrderSide.BUY) { p -> p <= taker.limitPrice!! }
                       else { p -> p >= taker.limitPrice!! }
    matchAgainstBook(counterBook, taker, results, priceFilter)

    if (taker.remainingQty > 0) {                 // 체결 안 된 잔량은 호가창에 등록
        if (taker.side == OrderSide.BUY) book.addBid(taker) else book.addAsk(taker)
    }
}
```

매수 LIMIT 주문은 "지정가 이하의 매도 호가"와만 매칭된다. 매칭 후에도 수량이 남으면 호가창에 등록해 다음 반대 주문을 기다린다.

### 가격 우선 → 시간 우선 검증

세 개의 매도 호가를 일부러 가격 순서가 뒤섞인 채로 등록한 뒤 대량 매수 주문을 넣으면, 체결은 항상 **가격 오름차순**으로 일어나야 한다 — `TreeMap`의 정렬 보장 덕분에 별도 정렬 로직 없이 자동으로 만족된다. `MatchingOrderBookServiceTest`의 `price-then-time priority across non-sorted insertion order is swept ascending` 테스트가 이를 검증한다.

---

## 4. MatchingService — 오케스트레이션과 단순화

`MatchingService.submitOrder()`는 리스크 체크 → 주문 저장 → 체결 → 원장 기록의 흐름을 담당한다. 다만 모의투자 환경의 특성상 한 가지 단순화가 있다.

```kotlin
val fillPrice: BigDecimal? = when {
    req.orderType == "MARKET" -> currentPrice
    req.side == "BUY" && req.limitPrice!! >= currentPrice -> currentPrice
    req.side == "SELL" && req.limitPrice!! <= currentPrice -> currentPrice
    else -> null
}
```

**MARKET 주문은 실제 호가창 유동성을 조회하지 않고 `candles_1m`의 현재가에 즉시 전량 체결된다.** `MatchingOrderBookService`가 구현하는 실제 매칭 로직(슬리피지, 부분 체결)은 LIMIT 주문이 즉시 체결되지 않을 때 호가창에 등록하는 용도로만 쓰인다. 이는 테스트 작성 과정에서 명확히 드러난 설계 단순화이며, 실거래소를 완전히 모사하려면 MARKET 주문도 `OrderBookService.submit()`을 통해 실제 반대 호가 유동성을 소진하도록 바꿔야 한다.

---

## 5. 한계와 향후 확장

- MARKET 주문이 호가창 유동성을 무시한다 (위 설명 참고)
- 동시성은 종목 단위로만 보장되며, 다중 인스턴스 환경에서는 메모리 내 호가창이 공유되지 않는다 (Redis 기반 분산 호가창 필요)
- 체결 수수료는 0으로 고정되어 있다 (`Fill.fee = BigDecimal.ZERO`)
