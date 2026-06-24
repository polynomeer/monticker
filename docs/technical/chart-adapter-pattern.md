# 차트 어댑터 패턴

**대상 독자:** 프론트엔드 아키텍처에 관심 있는 시니어 엔지니어  
**최종 수정:** 2026-06-24  
**관련 파일:**
- `apps/web/src/components/stock/chart/types.ts`
- `apps/web/src/components/stock/chart/StockChart.tsx`
- `apps/web/src/components/stock/chart/EChartsAdapter.tsx`
- `apps/web/src/stores/themeStore.ts`

---

## 배경과 문제

monticker의 초기 차트 구현은 TradingView가 만든 **lightweight-charts**를 기반으로 했다. lightweight-charts는 금융 차트 라이브러리 가운데 번들 크기와 퍼포먼스 면에서 가장 좋은 선택지 중 하나다. 그러나 프로젝트가 성장하면서 두 가지 구조적 마찰이 드러났다.

**라이선스 제약.** lightweight-charts v4 이후 Apache 2.0에서 독자적인 비상업용 라이선스(BSL-1.1)로 전환되었다. 상업적 사용이나 사내 도구로 배포할 경우 별도 상용 라이선스 계약이 필요하다. 스타트업 초기에는 무시하기 쉬운 조항이지만, 제품이 수익화 단계에 진입하면 소급 적용 리스크가 커진다.

**API 교체 비용.** lightweight-charts는 `IChartApi`, `ISeriesApi` 등 자체 명명 규칙의 명령형 API를 노출한다. 이 API가 컴포넌트 트리 전역에 산재하면, 라이브러리 교체 시 수십 개의 파일을 동시에 수정해야 한다. 특히 캔들스틱·이동평균·볼륨 패널이 결합된 복합 차트일수록 교체 작업의 표면적이 넓다.

이 문서는 위 두 문제를 동시에 해결하기 위해 도입된 **차트 어댑터 패턴**의 설계 의도와 구현 상세를 기술한다.

---

## 설계 목표

1. **어댑터 교체 비용 최소화.** 라이브러리를 바꾸려면 한 줄의 import와 한 줄의 변수 할당만 수정하면 된다. 상위 컴포넌트와 페이지 레이어는 변경하지 않는다.

2. **타입 안전성.** 어댑터가 준수해야 하는 계약을 TypeScript 인터페이스로 명시한다. 새 어댑터가 계약을 어기면 컴파일 타임에 탐지된다.

3. **기능 패리티.** 캔들스틱, 이동평균(MA5/MA20), 현재가 마크라인, 이벤트 마커, 거래량 패널, 줌/패닝을 모두 지원한다.

4. **번들 분리.** 차트 라이브러리는 초기 로드에 포함되지 않는다. 차트 컴포넌트가 실제로 마운트될 때만 동적으로 로드된다.

5. **테마 일관성.** 다크/라이트 모드와 사용자 정의 색 테마가 단일 진실 공급원(themeStore)에서 파생된다.

---

## 인터페이스 설계

```
apps/web/src/components/stock/chart/types.ts
```

### CandleData

```typescript
export interface CandleData {
  time: number;   // Unix epoch seconds
  open: number;
  high: number;
  low: number;
  close: number;
  volume?: number;
}
```

원시 시장 데이터의 최소 표현이다. `time`을 Unix epoch(초 단위)로 통일함으로써 TradingView 규칙과 백엔드 TimescaleDB 컬럼 타입 모두와 호환된다. `volume`은 옵셔널로, 거래량 데이터가 없는 지수나 환율 차트에서도 동일한 타입을 재사용할 수 있다.

### EventMarker

```typescript
export interface EventMarker {
  time: number;
  eventType: string;
  title: string;
  importanceScore: number;
}
```

주가 이벤트(급등, 급락, 거래량 이상, 공시 등)를 차트 위에 핀으로 표시하기 위한 타입이다. `importanceScore`는 핀 크기를 결정하는 데 사용된다. 어댑터 구현체는 이 필드를 시각화 힌트로만 소비하며, 이벤트 탐지 로직은 이 레이어에 존재하지 않는다.

### ChartTheme

```typescript
export interface ChartTheme {
  bg: string;
  text: string;
  grid: string;
  upColor: string;
  downColor: string;
}
```

어댑터가 렌더링에 사용하는 색상 계약이다. 배경색, 텍스트색, 그리드색은 다크/라이트 모드에 따라 결정되고, `upColor`와 `downColor`는 사용자가 선택한 테마 키(default, classic, mono, korean)에 따라 결정된다. 어댑터는 `ChartTheme`만 소비하며 `next-themes`나 Zustand를 직접 참조하지 않는다.

### ChartAdapterProps

```typescript
export interface ChartAdapterProps {
  candles: CandleData[];
  events?: EventMarker[];
  height?: number;
  theme: ChartTheme;
}
```

어댑터가 외부로부터 수신하는 props의 전체 집합이다. 이 인터페이스가 안정적으로 유지되는 한, 어댑터 구현체는 언제든지 교체할 수 있다.

### ChartAdapterComponent

```typescript
export interface ChartAdapterComponent {
  (props: ChartAdapterProps): React.ReactElement | null;
}
```

어댑터 구현체가 준수해야 하는 함수 컴포넌트 시그니처다. `React.FC`가 아닌 함수 타입을 직접 정의한 것은 `children` 등 불필요한 타입을 인터페이스에 포함하지 않기 위해서다.

---

## 어댑터 교체 메커니즘

```
apps/web/src/components/stock/chart/StockChart.tsx
```

`StockChart`는 어댑터를 감싸는 얇은 래퍼다. 이 파일이 하는 일은 세 가지다: 테마를 조합하고, 빈 데이터를 처리하고, 활성 어댑터에 props를 전달한다.

```typescript
import EChartsAdapter from "./EChartsAdapter";  // ← 교체 지점

const ActiveAdapter = EChartsAdapter;
```

라이브러리를 바꾸고 싶다면 위 두 줄만 수정한다.

```
                       ┌─────────────────────────┐
                       │       StockChart         │
                       │                          │
  candles, events ────>│  theme 조합              │
                       │  ActiveAdapter = ...  ───┼──> EChartsAdapter
                       │                          │    (또는 다른 어댑터)
                       └─────────────────────────┘
                             │           │
                        useTheme    useThemeStore
                       (next-themes) (Zustand)
```

`StockChart`는 `ChartAdapterComponent` 타입을 명시적으로 선언하지는 않지만, `ActiveAdapter`가 `ChartAdapterProps`를 받지 않으면 JSX 호출부에서 타입 오류가 발생하므로 컴파일 타임에 계약 위반이 탐지된다.

---

## ECharts 어댑터 구현 상세

```
apps/web/src/components/stock/chart/EChartsAdapter.tsx
```

### 동적 import와 번들 분리

Apache ECharts의 전체 번들은 약 1MB(비압축 기준)에 달한다. 차트가 없는 페이지에서도 이를 로드하면 LCP에 직접적인 영향을 준다. 어댑터는 싱글턴 Promise를 활용해 최초 마운트 시점에 한 번만 ECharts를 로드한다.

```typescript
let echartsPromise: Promise<typeof import("echarts")> | null = null;
function loadECharts() {
  if (!echartsPromise) echartsPromise = import("echarts");
  return echartsPromise;
}
```

모듈 수준에서 Promise를 캐싱하므로, 같은 페이지에 여러 차트가 렌더링되어도 ECharts 번들은 한 번만 fetch된다. Next.js의 코드 스플리팅과 결합되면 차트 라이브러리는 별도의 청크로 분리되어 필요할 때만 로드된다.

### MA5/MA20 계산 로직

이동평균은 ECharts 내장 기능 없이 직접 계산한다. ECharts의 `markLine` 기반 이동평균 기능은 API가 불안정하고 커스터마이징이 어렵기 때문이다.

```typescript
function calcMA(data: CandleData[], period: number): (number | null)[] {
  return data.map((_, i) => {
    if (i < period - 1) return null;
    const slice = data.slice(i - period + 1, i + 1);
    return slice.reduce((s, c) => s + c.close, 0) / period;
  });
}
```

초기 `period - 1`개 구간은 `null`을 반환한다. ECharts의 line 시리즈는 `null` 값을 `connect: false`(기본값) 모드에서 자동으로 건너뛰므로, 별도의 null 처리 없이 선이 끊기지 않고 올바르게 그려진다.

### 현재가 markLine

마지막 종가를 기준으로 수평 점선을 그리고, 오른쪽 끝에 가격과 등락률을 뱃지 형태로 표시한다.

```typescript
markLine: {
  silent: true,
  data: [{ yAxis: last.close }],
  lineStyle: { color: isUp ? theme.upColor : theme.downColor, type: "dashed" },
  label: {
    position: "end",
    formatter: () => `${fmtPrice(last.close)}  ${chg >= 0 ? "+" : ""}${chg.toFixed(2)}%`,
    backgroundColor: isUp ? theme.upColor : theme.downColor,
    padding: [2, 6],
    borderRadius: 3,
  },
}
```

`silent: true`로 마크라인의 이벤트 전파를 차단해 툴팁과의 충돌을 방지한다. 뱃지 색상은 전일 대비 등락 방향에 따라 `upColor` 또는 `downColor`를 사용한다.

### 이벤트 마커 markPoint

이벤트 타임스탬프와 캔들 데이터를 최대 90초 허용 오차로 매칭하여 핀 마커를 생성한다.

```typescript
const markData = events.map(e => {
  const idx = candles.findIndex(c => Math.abs(c.time - e.time) < 90);
  if (idx < 0) return null;
  return {
    coord: [dates[idx], candles[idx].high],
    symbolSize: e.importanceScore > 70 ? 14 : 9,
    itemStyle: { color: EVENT_COLORS[e.eventType] ?? EVENT_COLORS.default },
  };
}).filter(Boolean);
```

90초 오차 허용은 분봉(1분) 단위 캔들을 기준으로 한다. 이벤트가 캔들의 정확한 시각과 일치하지 않아도 해당 봉 위에 핀이 표시된다. `importanceScore > 70` 임계값으로 핀 크기를 두 단계로 구분해 중요한 이벤트를 시각적으로 강조한다.

이벤트 타입별 색상은 다음과 같다.

| eventType            | 색상      | 의미         |
|----------------------|-----------|--------------|
| PRICE_SPIKE          | `#0ecb81` | 급등 (녹색)  |
| PRICE_DROP           | `#f6465d` | 급락 (빨강)  |
| VOLUME_SURGE         | `#f1fa8c` | 거래량 이상  |
| DISCLOSURE_PUBLISHED | `#bd93f9` | 공시         |
| default              | `#6272a4` | 기타         |

### 볼륨 패널 분리 (gridIndex)

ECharts의 다중 그리드 기능으로 캔들 패널과 볼륨 패널을 독립적으로 관리한다.

```
+---------------------------------------------+  grid[0]: 캔들 + MA + markLine
|                                             |
|   캔들스틱 / MA5 / MA20 / 이벤트 핀         |
|                                             |
+---------------------------------------------+  grid[1]: 거래량 바
|   XXXX XX XXXXX XX XXXX                    |
+---------------------------------------------+
|   슬라이더 줌 (dataZoom[1])                 |
+---------------------------------------------+
```

캔들 시리즈는 `xAxisIndex: 0, yAxisIndex: 0`을 사용하고, 볼륨 바는 `xAxisIndex: 1, yAxisIndex: 1`을 사용한다. `dataZoom`의 `xAxisIndex: [0, 1]` 설정으로 두 패널의 줌과 이동이 연동된다. 볼륨 바의 색상은 해당 봉의 양/음에 따라 `upColor` 또는 `downColor`에 투명도 `99`(16진수)를 적용해 캔들보다 시각적으로 약하게 표현한다.

---

## 테마 통합

```
apps/web/src/stores/themeStore.ts
```

차트의 최종 색상은 두 개의 독립된 상태를 조합해 결정된다.

```
next-themes resolvedTheme          Zustand themeStore.chartTheme
        |                                    |
        v                                    v
  "dark" | "light"              "default" | "classic" | "mono" | "korean"
        |                                    |
        +----------------+-------------------+
                         v
                    ChartTheme
             { bg, text, grid, upColor, downColor }
```

`next-themes`는 OS 설정이나 사용자 토글에 따른 다크/라이트 모드를 담당한다. `themeStore`는 양봉/음봉 색상 팔레트 선택을 담당하며, `localStorage`에 persist되어 새로고침 후에도 선택이 유지된다.

`themeStore`는 Next.js SSR 환경에서의 하이드레이션 불일치를 방지하기 위해 두 가지 처리를 한다.

1. `skipHydration: true` — Zustand의 자동 하이드레이션을 비활성화한다. 클라이언트에서 수동으로 `useStore.persist.rehydrate()`를 호출하거나, 첫 렌더링 이후 스토어가 자연스럽게 업데이트되도록 한다.
2. SSR safe storage — 서버 환경(`typeof window === "undefined"`)에서는 `localStorage` 대신 no-op 스토리지를 사용해 `window is not defined` 오류를 방지한다.

`ChartTheme` 인터페이스는 `wickUp`과 `wickDown` 필드를 포함하지 않는다. `ChartThemeConfig`에는 이 필드가 있지만, ECharts 어댑터는 캔들 바디와 심지에 같은 색상을 적용하므로 `upColor`와 `downColor`만으로 충분하다. 다른 어댑터에서 심지 색상을 별도로 제어해야 할 경우 `ChartTheme`에 옵셔널 필드를 추가할 수 있다.

---

## 대안과 기각된 선택지

### D3.js

D3는 가장 낮은 레벨의 SVG/Canvas 추상화를 제공하며, 라이선스(ISC)와 커스터마이징 유연성 면에서 최선이다. 그러나 캔들스틱, 줌, 툴팁, 다중 패널을 모두 직접 구현해야 하므로 초기 개발 비용이 높다. 또한 React의 선언적 렌더링 모델과 D3의 명령형 DOM 조작이 충돌하며, 이를 조화시키기 위한 `useEffect` 패턴이 복잡해진다. 팀이 D3 전문성을 보유하거나 완전한 커스터마이징이 필요한 시점에 재검토할 수 있다.

### Recharts

Recharts는 React 네이티브 API와 자연스럽게 통합되며 학습 비용이 낮다. 그러나 캔들스틱 시리즈를 기본 지원하지 않아 커스텀 shape 구현이 필요하다. 대량의 데이터 포인트(1000개 이상)에서 SVG 기반 렌더링이 느려지는 것도 단점이다. 금융 차트에 필요한 markLine, markPoint, gridIndex 기반 다중 패널 같은 기능이 없어 기각했다.

### 순수 Canvas 구현

HTML5 Canvas로 직접 구현하면 종속성 없이 완전한 제어가 가능하다. 그러나 HiDPI 처리, 이벤트 히트 테스트, 줌/패닝 구현, 텍스트 렌더링까지 모두 직접 작성해야 한다. 유지보수 비용 대비 이점이 현재 시점에서는 정당화되지 않아 기각했다. 미래에 성능 병목이 생기면 WebGL 기반 구현(regl, deck.gl)과 함께 재검토할 수 있다.

---

## 한계와 향후 방향

**실시간 가격 갱신.** 현재 구현은 `candles` 배열이 바뀔 때마다 `useEffect`가 실행되어 ECharts 인스턴스를 dispose하고 재생성한다. 틱 단위 실시간 갱신이 필요한 경우 `chart.setOption` 부분 업데이트(`notMerge: false`) 방식으로 전환해야 한다. 그러나 이는 `buildOption`을 증분 diff 방식으로 재설계해야 하므로 현재 아키텍처와 충돌한다.

**다중 어댑터 공존.** 현재 `ActiveAdapter`는 전역 상수다. 사용자가 차트 타입을 런타임에 선택(예: TradingView 라이선스 구매 사용자는 TradingView 렌더러 사용)하는 기능이 필요하다면, `ActiveAdapter`를 상태로 올리고 `React.lazy`와 결합한 lazy 어댑터 로딩 패턴으로 전환해야 한다.

**이동평균 종류 확장.** MA5/MA20은 하드코딩되어 있다. MACD, 볼린저 밴드, RSI 등 추가 보조지표를 지원하려면 `ChartAdapterProps`에 `indicators` 필드를 추가하고, 어댑터가 이를 선택적으로 렌더링하는 방식으로 확장할 수 있다.

**접근성.** 현재 Canvas 렌더링은 스크린 리더에 노출되지 않는다. 중요한 가격 데이터를 `aria-label` 또는 숨겨진 테이블로 제공하는 접근성 레이어가 없다. ECharts의 `aria` 옵션을 활성화하거나, 별도의 접근성 전용 렌더링 경로를 고려해야 한다.
