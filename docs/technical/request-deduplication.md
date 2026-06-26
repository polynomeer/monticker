# TanStack Query를 이용한 요청 중복 제거 (Single Flight)

## 1. 문제: 동일 엔드포인트를 여러 컴포넌트가 독립 폴링

React 컴포넌트가 자체적으로 데이터를 페칭하는 패턴은 간단하지만, 같은 데이터를 필요로 하는 컴포넌트가 여럿 생기면 중복 요청 문제가 발생한다.

현재 코드베이스에서 이 문제가 가장 명확하게 드러나는 지점은 `/api/stocks/{id}/events` 엔드포인트다.

```typescript
// useStockChart.ts — StockChart 컴포넌트에서 사용
useEffect(() => {
  const fetchAll = async () => {
    const [candleRes, eventRes] = await Promise.all([
      fetch(`/api/stocks/${stockId}/candles?interval=${interval}`),
      fetch(`/api/stocks/${stockId}/events`),  // events 폴링
    ]);
    // ...
  };
  fetchAll();
  const interval_ = setInterval(fetchAll, 10000);  // 10초마다
  return () => clearInterval(interval_);
}, [stockId, interval]);
```

```typescript
// EventTimeline.tsx — EventTimeline 컴포넌트에서 독립적으로 폴링
useEffect(() => {
  fetchEvents();
  const interval = setInterval(fetchEvents, 5000);  // 5초마다
  return () => clearInterval(interval);
}, [stockId]);
```

종목 상세 페이지에 두 컴포넌트가 함께 마운트되면 `/api/stocks/{id}/events`에 대해 두 개의 독립적인 폴링 타이머가 동시에 돌아간다. 10초 동안 최대 **3번**(10s 주기 1번 + 5s 주기 2번)의 요청이 발생한다.

컴포넌트가 늘어나거나 폴링 주기가 짧아질수록 이 문제는 선형적으로 악화된다. 사용자 입장에서는 캔들과 이벤트가 서로 다른 시점에 갱신되어 차트와 타임라인이 잠시 불일치하는 시각적 부작용도 생긴다.

---

## 2. Single Flight 패턴

Single Flight는 "동일한 키로 진행 중인 요청이 있으면 새 요청을 날리지 않고 기존 요청의 결과를 기다린다"는 패턴이다. Go의 `singleflight` 패키지가 대표적 구현이다.

네트워크 요청 맥락에서는 세 가지 동작을 포함한다.

1. **요청 병합(deduplication)**: 동일 키의 요청이 in-flight 중이면 새 요청을 억제하고 기존 프로미스를 공유한다.
2. **결과 공유(cache)**: 완료된 요청의 결과를 일정 시간 캐시하여 같은 데이터를 다시 가져오지 않는다.
3. **구독자 통지**: 캐시된 결과가 갱신되면 해당 데이터를 사용 중인 모든 컴포넌트에 동시에 전달한다.

TanStack Query(구 React Query)는 React 생태계에서 이 세 가지를 제공하는 라이브러리다.

---

## 3. TanStack Query 적용

### `stockKeys` 팩토리 — 동일 queryKey 보장

중복 제거의 핵심은 같은 데이터에 대해 항상 동일한 `queryKey`를 사용하는 것이다. 키가 조금이라도 다르면 TanStack Query는 별개의 쿼리로 인식한다.

```typescript
// lib/queryKeys.ts
export const stockKeys = {
  all: ["stocks"] as const,
  detail: (stockId: number) => ["stocks", stockId] as const,
  candles: (stockId: number, interval: string) =>
    ["stocks", stockId, "candles", interval] as const,
  events: (stockId: number) => ["stocks", stockId, "events"] as const,
  price: (stockId: number) => ["stocks", stockId, "price"] as const,
};
```

팩토리 함수로 키를 중앙화하면 오타나 불일치로 인한 키 불일치를 방지할 수 있다.

### `staleTime: 10_000` — 10초 캐시 윈도우

```typescript
// hooks/useStockEvents.ts
import { useQuery } from "@tanstack/react-query";
import { stockKeys } from "@/lib/queryKeys";

export function useStockEvents(stockId: number) {
  return useQuery({
    queryKey: stockKeys.events(stockId),
    queryFn: () =>
      fetch(`/api/stocks/${stockId}/events`).then((r) => r.json()),
    staleTime: 10_000,      // 10초 이내 재요청 시 캐시 반환
    refetchInterval: 10_000, // 10초마다 백그라운드 갱신
  });
}
```

`staleTime: 10_000`은 "마지막 fetch로부터 10초 이내에 같은 키로 `useQuery`가 호출되면 네트워크 요청 없이 캐시된 데이터를 반환한다"는 의미다.

`EventTimeline`과 `useStockChart`가 동시에 마운트되어 둘 다 `useStockEvents(stockId)`를 호출해도, 첫 번째 호출이 완료된 후 10초 안에 두 번째 호출이 들어오면 요청은 한 번만 발생한다.

### `refetchInterval` — setInterval 교체

기존 코드의 `setInterval(fetchEvents, 5000)` 패턴을 `refetchInterval`로 대체한다.

```typescript
// 기존 패턴 — 컴포넌트마다 독립적인 타이머
useEffect(() => {
  const interval = setInterval(fetchEvents, 5000);
  return () => clearInterval(interval);
}, [stockId]);

// TanStack Query 패턴 — 단일 타이머, 모든 구독자에게 공유
const { data: events } = useQuery({
  queryKey: stockKeys.events(stockId),
  queryFn: fetchEvents,
  refetchInterval: 10_000,
});
```

`refetchInterval`은 TanStack Query 내부에서 단일 타이머로 관리된다. 같은 `queryKey`를 사용하는 컴포넌트가 10개라도 타이머는 하나다. 타이머가 발동하면 요청을 한 번 보내고 모든 구독자의 상태를 한 번에 갱신한다.

---

## 4. QueryProvider 설정

TanStack Query를 사용하려면 애플리케이션 최상단에 `QueryClientProvider`를 배치해야 한다.

```typescript
// components/QueryProvider.tsx (구현 예정)
"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";

export default function QueryProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  // useState로 생성하여 SSR 시 동일한 인스턴스를 재사용하지 않도록 함
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 10_000,
            refetchOnWindowFocus: false,
          },
        },
      })
  );

  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}
```

**`useState`로 `QueryClient`를 생성하는 이유**: Next.js App Router의 서버 컴포넌트는 여러 요청에서 모듈 스코프 변수를 공유할 수 있다. `queryClient`를 모듈 최상단에 선언하면 서버에서 여러 사용자의 요청이 동일한 캐시를 공유하게 되어 데이터가 오염된다. `useState`로 생성하면 각 클라이언트 세션마다 별도의 인스턴스가 생성된다.

**`refetchOnWindowFocus: false`로 설정하는 이유**: TanStack Query의 기본 동작은 브라우저 탭이 포커스를 얻을 때 stale 상태의 모든 쿼리를 자동으로 재요청한다. 시세 데이터는 `refetchInterval`로 이미 주기적으로 갱신되므로, 탭 전환 시마다 발생하는 추가 요청은 불필요하다. 특히 여러 탭을 오가는 멀티태스킹 사용자 환경에서 예상치 못한 요청 급증을 방지한다.

---

## 5. 적용 전/후 비교

종목 상세 페이지(`/stocks/{symbol}`)에는 현재 `useStockChart`와 `EventTimeline`이 함께 마운트된다.

### 적용 전 (현재 상태)

| 엔드포인트 | 호출 주체 | 폴링 주기 | 10분간 요청 수 |
|-----------|---------|---------|-------------|
| `/api/stocks/{id}/events` | `useStockChart` | 10초 | 60회 |
| `/api/stocks/{id}/events` | `EventTimeline` | 5초 | 120회 |
| `/api/stocks/{id}/candles` | `useStockChart` | 10초 | 60회 |
| **합계** | — | — | **240회** |

두 컴포넌트가 `/events`를 독립적으로 폴링하므로 10분간 180회의 이벤트 요청이 발생한다.

### 적용 후 (TanStack Query)

| 엔드포인트 | queryKey | 폴링 주기 | 10분간 요청 수 |
|-----------|---------|---------|-------------|
| `/api/stocks/{id}/events` | `stockKeys.events(id)` | 10초 | **60회** |
| `/api/stocks/{id}/candles` | `stockKeys.candles(id, interval)` | 10초 | 60회 |
| **합계** | — | — | **120회** |

동일 queryKey 덕분에 `/events` 요청이 절반으로 줄었다. 컴포넌트 수가 늘어날수록 절감 효과는 커진다.

**요청 수 감소율**: 이 시나리오에서 약 50% 감소. 동일 데이터를 사용하는 컴포넌트가 N개일 때 이론적 최대 절감률은 `(N-1)/N × 100%`다.

---

## 6. 추가 최적화: `candlesEqual()` 비교로 불필요한 state 교체 방지

`useStockChart`는 10초마다 캔들 데이터를 다시 가져온다. 그런데 장 마감 이후나 데이터 변경이 없는 구간에도 fetch가 완료되면 `setCandles(newData)`가 호출되어 새 배열 참조가 생성된다. 이는 `StockChart` 컴포넌트의 불필요한 리렌더링을 유발한다.

```typescript
// 현재 구현 — 데이터가 같아도 매번 상태 교체
if (candleRes.ok) {
  const data = await candleRes.json();
  setCandles(data.map((c) => ({ ... })));  // 항상 새 배열
}
```

`candlesEqual()` 비교를 추가하면 실제로 변경이 있을 때만 상태를 교체한다.

```typescript
function candlesEqual(prev: CandleData[], next: CandleData[]): boolean {
  if (prev.length !== next.length) return false;
  const last = prev.length - 1;
  // 마지막 캔들(현재 진행 중인 봉)만 비교하면 충분함
  // 과거 봉은 확정된 이후 변경되지 않음
  return (
    prev[last]?.close === next[last]?.close &&
    prev[last]?.volume === next[last]?.volume
  );
}

// 개선된 구현
const mapped = data.map((c) => ({ ... }));
setCandles((prev) => (candlesEqual(prev, mapped) ? prev : mapped));
```

TanStack Query를 사용하면 `select` 옵션으로 이 비교를 선언적으로 처리할 수 있다.

```typescript
const { data: candles } = useQuery({
  queryKey: stockKeys.candles(stockId, interval),
  queryFn: fetchCandles,
  select: (newData) => newData,  // structuralSharing이 기본으로 동작
});
```

TanStack Query는 기본적으로 `structuralSharing`을 활성화한다. 새 응답 데이터가 이전과 깊이 동일(deep equal)하면 기존 객체 참조를 재사용한다. `candlesEqual()` 같은 커스텀 비교가 없어도 불필요한 리렌더링이 방지된다.

---

## 7. 한계: WebSocket 구독은 TanStack Query 범위 밖

TanStack Query는 Promise를 반환하는 비동기 함수(fetch, axios 등)를 대상으로 설계되었다. WebSocket과 같이 지속적으로 메시지를 수신하는 커넥션은 `queryFn`의 반환값 모델에 맞지 않는다.

현재 `useStockPrice`의 REST 폴링을 TanStack Query로 마이그레이션하는 것은 가능하다.

```typescript
export function useStockPrice(stockId: number) {
  return useQuery({
    queryKey: stockKeys.price(stockId),
    queryFn: () =>
      fetch(`/api/stocks/${stockId}/price`).then((r) => r.json()),
    refetchInterval: 3_000,
  });
}
```

그러나 WebSocket 구독이 구현되면 이 훅은 TanStack Query 밖에서 동작해야 한다. WS 메시지를 수신할 때마다 `queryClient.setQueryData(stockKeys.price(stockId), newData)`를 호출하여 캐시를 수동으로 갱신하는 하이브리드 방식을 사용한다.

```typescript
// WS 메시지 수신 시 TanStack Query 캐시 수동 갱신
client.subscribe(`/topic/stocks/${stockId}`, (msg) => {
  const tick = JSON.parse(msg.body);
  queryClient.setQueryData(stockKeys.price(stockId), tick);
});
```

이 방식을 사용하면 WS로 받은 가격도 `useQuery`로 구독하는 컴포넌트에 자동으로 전파된다. WS와 REST 폴링을 동일한 캐시 레이어로 통합하는 것이 가능하다.
