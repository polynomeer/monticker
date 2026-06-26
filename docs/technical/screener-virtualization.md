# 스크리너 목록 가상화 — DOM 폭발 방지

## 1. 문제

스크리너는 무한 스크롤로 종목 목록을 표시한다. 스크롤할수록 새로운 데이터가 목록에 누적되고, DOM 노드 수가 계속 증가한다. 500개 종목이 로드되면 헤더와 데이터 셀을 합쳐 수천 개의 DOM 노드가 존재하게 된다.

DOM 노드가 많아지면 두 가지 문제가 발생한다. 첫째, 스크롤 이벤트마다 브라우저가 화면에 보이지 않는 수백 개의 행을 포함하여 레이아웃을 재계산한다. 둘째, GPU에 레이어를 올릴 때 메모리 사용량이 비선형적으로 증가한다. 결과적으로 스크롤이 버벅거리고, 저사양 기기에서는 프레임 드롭이 발생한다.

---

## 2. 브라우저 렌더링 병목

**Layout Thrashing**: DOM을 읽고(offsetHeight, getBoundingClientRect) 쓰는(style 변경) 작업이 교차하면 브라우저가 매번 레이아웃을 재계산한다. 500개 행이 모두 DOM에 존재하면 이 비용이 크다.

**레이어 합성 비용**: `position: sticky`, `transform`, `will-change` 등이 적용된 요소는 별도 합성 레이어로 분리된다. 레이어가 너무 많으면 GPU 메모리를 초과하여 오히려 성능이 저하된다.

**가비지 컬렉션**: React가 1000개 컴포넌트를 마운트하면 GC 부담이 증가한다. 스크롤 중 GC가 발생하면 프레임이 끊긴다.

---

## 3. 가상화 원리

가상화는 "전체 데이터가 존재하는 것처럼 보이되, DOM에는 뷰포트에 보이는 행만 유지"하는 기법이다.

핵심 아이디어는 두 가지다.

1. **전체 높이 공간 확보**: 500개 행 × 52px = 26,000px 높이의 컨테이너를 만든다. 스크롤바는 전체 데이터가 있는 것처럼 움직인다.

2. **뷰포트 내 absolute 배치**: 실제 DOM에는 화면에 보이는 10~15개 행만 렌더링한다. 각 행은 `position: absolute; top: N`으로 정확한 위치에 배치된다.

스크롤이 움직이면 새로운 위치에 맞게 렌더링할 행 집합이 교체된다. DOM 노드가 재활용되는 것이 아니라, React가 필요한 항목만 새로 렌더링한다.

---

## 4. TanStack Virtual 적용

### useVirtualizer 설정

```tsx
const ROW_HEIGHT = 52;   // px — ScreenerRow 고정 높이
const OVERSCAN   = 5;    // 뷰포트 위아래 여분 렌더 행 수

const virtualizer = useVirtualizer({
  count:            items.length,          // 전체 아이템 수
  getScrollElement: () => scrollRef.current, // 스크롤 컨테이너 참조
  estimateSize:     () => ROW_HEIGHT,      // 행 높이 추정
  overscan:         OVERSCAN,              // 여분 렌더링 범위
});
```

각 옵션의 역할:
- `count`: 전체 아이템 수. `items.length`가 바뀌면 virtualizer가 재계산한다.
- `getScrollElement`: 스크롤 이벤트를 감지할 DOM 요소. 반드시 고정 높이와 `overflow-y: auto`가 있어야 한다.
- `estimateSize`: 각 행의 높이 추정값. 고정 높이이므로 정확한 값을 반환한다.
- `overscan`: 뷰포트 바깥 위아래로 추가 렌더링할 행 수. 5로 설정하면 스크롤 방향의 다음 5행이 미리 렌더링된다. 스크롤 시 빈 행이 순간적으로 보이는 것을 방지한다.

### 스크롤 컨테이너 고정 높이

```tsx
<div
  ref={scrollRef}
  className="overflow-y-auto min-w-[720px]"
  style={{ height: "min(600px, 80vh)" }}
>
```

`getScrollElement`가 참조하는 이 요소는 반드시 고정 높이가 있어야 한다. `height: 100%`처럼 부모에 의존하는 높이는 virtualizer가 뷰포트 크기를 계산할 수 없어 동작하지 않는다. `min(600px, 80vh)`는 뷰포트가 작을 때 화면을 넘지 않도록 한다.

### virtualRow.start를 absolute top으로

```tsx
<div style={{ height: totalHeight, position: "relative" }}>
  {virtualItems.map(virtualRow => (
    <div
      key={virtualRow.key}
      style={{
        position: "absolute",
        top:    virtualRow.start,   // 계산된 절대 위치
        left:   0,
        width:  "100%",
        height: ROW_HEIGHT,
      }}
    >
      <ScreenerRow item={items[virtualRow.index]} />
    </div>
  ))}
</div>
```

`virtualRow.start`는 해당 행이 컨테이너 상단으로부터 얼마나 떨어진 위치에 있어야 하는지를 나타낸다. `index × ROW_HEIGHT`와 같다. 부모의 `height: totalHeight`는 `count × ROW_HEIGHT`로 전체 스크롤 영역을 확보한다.

---

## 5. 무한 스크롤과 가상화 통합

IntersectionObserver 센티넬을 가상 목록 내부 하단에 배치한다.

```tsx
const loadMoreRef = useCallback((node: HTMLDivElement | null) => {
  if (!node) return;
  const obs = new IntersectionObserver(
    ([entry]) => {
      if (entry.isIntersecting && hasMore && !loadingMore) onLoadMore();
    },
    { threshold: 0.1 }
  );
  obs.observe(node);
  return () => obs.disconnect();
}, [hasMore, loadingMore, onLoadMore]);

// 가상 스크롤 컨테이너 하단에 위치
<div ref={loadMoreRef} className="h-4" />
```

센티넬이 스크롤 컨테이너 안에 있으므로 사용자가 가상 목록 끝에 도달했을 때 `onLoadMore`가 호출된다. 새 데이터가 로드되면 `items.length`가 늘어나고, virtualizer가 `count`를 업데이트하여 추가 행의 가상 공간을 확보한다.

주의할 점은 센티넬이 `<div style={{ height: totalHeight }}>` 안에 있지 않다는 것이다. 이 안에 있으면 `absolute` 포지셔닝의 영향을 받아 위치가 부정확해질 수 있다. 따라서 `totalHeight` div 바깥, 스크롤 컨테이너 안의 말미에 위치한다.

---

## 6. tr/td에서 div로 전환

HTML `<table>` 레이아웃은 가상화와 호환되지 않는다. `<tr>`에 `position: absolute`를 적용하면 테이블 레이아웃 알고리즘이 무력화되어 열 너비가 맞지 않는다.

ScreenerTable은 이 이유로 의미론적 테이블 요소(`table`, `thead`, `tbody`, `tr`, `td`) 대신 `div`로 구현한다.

```tsx
{/* 헤더: flex row */}
<div className="flex items-center border-b ...">
  {HEADERS.map(h => <div key={h.label} className={h.width}>...</div>)}
</div>

{/* 행: absolute div */}
<div style={{ position: "absolute", top: virtualRow.start, ... }}>
  <ScreenerRow item={item} />
</div>
```

열 너비는 헤더와 행 모두에 동일한 Tailwind 클래스(`w-28`, `flex-1`, `w-24` 등)를 적용하여 일치시킨다. 테이블처럼 자동으로 열이 맞춰지지 않으므로 클래스를 수동으로 동기화해야 한다.

접근성 측면에서 `role="table"`, `role="row"`, `role="cell"`을 div에 추가하면 스크린 리더가 테이블로 인식한다. 현재 구현에는 없으며 추후 개선 대상이다.

---

## 7. 성과

| 항목 | 가상화 전 (500개 로드) | 가상화 후 |
|------|----------------------|-----------|
| 렌더링 DOM 노드 수 | ~3,500개 (행당 7개) | ~119개 (17행 × 7개) |
| 초기 렌더 시간 | 비례 증가 | 데이터 수와 무관 |
| 스크롤 프레임 레이트 | 저하 | 안정적 60fps |

뷰포트에 보이는 행 수는 약 12개(`600px / 52px ≈ 11.5`), overscan 5 × 2 = 10개를 더하면 약 22개가 최대 DOM 행 수다. Jaeger 스팬 등 다른 UI 요소를 고려해도 총 DOM 절감 효과는 매우 크다.

ScreenerTable 하단의 상태 표시줄이 현재 렌더링 행 수를 실시간으로 보여준다.

```tsx
DOM 렌더: {virtualItems.length}행
```

---

## 8. 한계

**가변 높이 행**: 현재 모든 행의 높이가 52px로 고정되어 있다. 종목 설명이나 추가 정보를 펼치는 아코디언 행이 있다면 `estimateSize`로 정확한 높이를 추정할 수 없다. 이 경우 `virtualizer.measureElement`로 실제 렌더링 후 높이를 측정하는 동적 크기 모드가 필요하다.

```tsx
// 동적 크기 모드 (미구현)
ref={virtualizer.measureElement}
```

현재 구현에도 `ref={virtualizer.measureElement}`가 있지만, `estimateSize`가 정확한 고정 값을 반환하므로 실질적인 측정은 일어나지 않는다. 가변 높이로 전환하면 초기 스크롤 위치 추정 오차가 발생할 수 있어 별도 처리가 필요하다.

**스크롤 위치 복원**: 페이지를 이동했다가 돌아올 때 스크롤 위치를 복원하지 않는다. `scrollToIndex`나 `scrollToOffset` API를 사용하면 가능하지만, 현재는 항상 목록 상단에서 시작한다.
