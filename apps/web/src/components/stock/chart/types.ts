// ── 공통 캔들 / 이벤트 타입 ──────────────────────────────────
export interface CandleData {
  time: number;   // Unix epoch seconds
  open: number;
  high: number;
  low: number;
  close: number;
  volume?: number;
}

export interface EventMarker {
  id: number;
  time: number;
  eventType: string;
  title: string;
  importanceScore: number;
  /** 뉴스 감성 점수(-1~1). 없으면 null — "감성" 레이어가 쓴다 */
  sentimentScore?: number | null;
}

/** "퀀트 시그널" 레이어 — 내 전략·구독 전략의 이 종목 신호 */
export interface SignalMarker {
  id: number;
  time: number;   // Unix epoch seconds
  direction: "BUY" | "SELL";
  label: string;
}

/** "감성" 레이어 — 감성 점수가 있는 이벤트 */
export interface SentimentMarker {
  id: number;
  time: number;
  score: number;
  title: string;
}

/** 캔들 봉 단위 — 거래 마커를 어느 봉에 붙일지 정할 때 쓴다(버킷 경계는 Asia/Seoul). */
export type ChartInterval = "1m" | "3m" | "15m" | "1h" | "1d";

/** 거래(체결) 마커 — 내 체결·백테스트 거래를 캔들 위 매수▲·매도▼로 표시 */
export interface TradeMarker {
  /** 체결 식별자(선택). 같은 봉 안 체결은 하나로 묶이므로 표시에는 쓰지 않는다 */
  id?: string | number;
  /** 체결 시각, Unix epoch seconds */
  time: number;
  side: "BUY" | "SELL";
  /** 체결가 */
  price: number;
  /** 체결 수량(주) */
  qty: number;
  /** 툴팁에 덧붙일 설명(예: "익절") */
  label?: string;
}

export interface ChartTheme {
  bg: string;
  text: string;
  grid: string;
  upColor: string;
  downColor: string;
  /** 라인·영역 차트 선 색(강조색). 없으면 text */
  accent?: string;
}

/** 메인 시리즈 표시 방식 */
export type ChartType = "candle" | "line" | "area" | "heikin-ashi";

export interface VwapPoint {
  time: number;
  vwap: string;
}

// ── 고급 모드: 자유 지표 / 주문선 / 드로잉 ───────────────────
export type IndicatorKey = "MA5" | "MA20" | "MA60" | "BOLL";

export interface OrderLine {
  id: number;
  price: number;
  side: "BUY" | "SELL";
  label: string;
}

/** 저장되는 드로잉 종류 */
export type DrawingKind = "TREND_LINE" | "HORIZONTAL_LINE" | "PEN" | "TEXT";

/**
 * 차트 위 상호작용 도구. 드로잉 종류 + 저장하지 않는 도구:
 * MEASURE(두 점 사이 가격·%·봉 수, 화면에만 표시), ZOOM(두 점 사이 구간으로 확대, 한 번 쓰면 해제).
 */
export type DrawingTool = DrawingKind | "MEASURE" | "ZOOM";

export interface DrawingPoint {
  /** Unix epoch seconds. 봉 사이 위치(펜)는 이웃 봉 시각을 보간한 값이다 */
  time: number;
  price: number;
}

/**
 * 드로잉 좌표는 (시각, 가격) 데이터 좌표로 저장한다 — 줌/팬해도 캔들에 고정되고,
 * 봉 간격을 바꿔도 같은 시각(Asia/Seoul 봉 버킷)에 다시 놓인다.
 */
export interface Drawing {
  id: string;
  tool: DrawingKind;
  points: DrawingPoint[];
  /** TEXT 라벨 내용 */
  text?: string;
}

export interface ChartAdapterProps {
  candles: CandleData[];
  events?: EventMarker[];
  height?: number;
  theme: ChartTheme;
  vwapData?: VwapPoint[];
  /** 차트 위 이벤트 마커를 클릭했을 때 — 이벤트 타임라인으로 점프하는 크로스 내비게이션용 */
  onEventClick?: (eventId: number) => void;
  /** 메인 차트에 겹쳐 그릴 지표. 미지정 시 MA5/MA20만(기존 기본값과 동일). */
  enabledIndicators?: IndicatorKey[];
  /** 퀀트 시그널(매수▲·매도▼) 마커 */
  signalMarkers?: SignalMarker[];
  /** 감성 점수 마커(양수 초록·음수 빨강) */
  sentimentMarkers?: SentimentMarker[];
  /** 실전투자 미체결 주문을 가격선으로 표시 */
  orderLines?: OrderLine[];
  /** 거래 마커 — 같은 봉·같은 방향 체결은 건수와 함께 하나로 묶는다 */
  trades?: TradeMarker[];
  /** candles의 봉 단위. 마커·드로잉을 봉에 맞출 때 쓴다(없으면 봉 간격으로 추정) */
  interval?: ChartInterval;
  /** 메인 시리즈 표시 방식(기본 캔들) */
  chartType?: ChartType;
  /** 자석 — 클릭한 점의 가격을 그 봉의 시·고·저·종 중 가장 가까운 값에 붙인다 */
  magnet?: boolean;
  /** 잠금 — 기존 드로잉을 옮기거나 지울 수 없다 */
  drawingsLocked?: boolean;
  /** 한 번 쓰고 끝나는 도구(ZOOM)가 끝났을 때 — 화면이 도구를 해제한다 */
  onDrawingToolDone?: () => void;
  onCancelOrderLine?: (orderId: number) => void;
  /** 현재 선택된 드로잉 도구 — null이면 그리기 비활성 (차트는 평소처럼 줌/팬만) */
  activeDrawingTool?: DrawingTool | null;
  drawings?: Drawing[];
  onDrawingsChange?: (drawings: Drawing[]) => void;
  /** 움직임 줄이기(접근성 설정·OS prefers-reduced-motion) — 툴팁·십자선 이동 등 모든 차트 애니메이션을 끈다 */
  reduceMotion?: boolean;
}

// ── 호가 깊이(누적) 차트 ─────────────────────────────────────
/** 호가 한 단계 — 가격과 잔량 */
export interface DepthLevel {
  price: number;
  quantity: number;
}

/** 누적 깊이 계열. 두 계열 모두 [가격, 누적잔량]을 가격 오름차순으로 담는다 */
export interface DepthSeries {
  bids: [number, number][];
  asks: [number, number][];
  bidTotal: number;
  askTotal: number;
  bestBid: number | null;
  bestAsk: number | null;
}

export interface DepthAdapterProps {
  depth: DepthSeries;
  height?: number;
  theme: ChartTheme;
  /** 가격 축 소수 자릿수(국내 0, 해외 2) */
  priceDigits?: number;
}

export interface DepthAdapterComponent {
  (props: DepthAdapterProps): React.ReactElement | null;
}

// ── 위험-수익 산점도(효율적 프론티어) ────────────────────────
/** 연 변동성(risk)·연 수익률(ret), 둘 다 % 단위 */
export interface RiskReturnPoint {
  risk: number;
  ret: number;
}

export interface FrontierChartData {
  /** 무작위 롱 온리 포트폴리오 표본 — 샤프가 정의되지 않으면 null */
  samples: Array<RiskReturnPoint & { sharpe: number | null }>;
  /** 효율적 프론티어(목표수익별 최소분산) 점 */
  frontier: RiskReturnPoint[];
  /** 샤프 비율 최대 지점(과거 데이터 기준) */
  maxSharpe?: RiskReturnPoint & { sharpe: number };
  /** 목표수익 최소분산 분석 결과 비중 */
  optimal?: RiskReturnPoint;
  /** 동일가중 */
  equalWeight?: RiskReturnPoint;
  /** 사용자의 현재(모의투자) 보유 비중 */
  held?: RiskReturnPoint;
}

export interface FrontierChartTheme {
  bg: string;
  text: string;
  grid: string;
  sample: string;
  frontier: string;
  maxSharpe: string;
  optimal: string;
  equalWeight: string;
  held: string;
}

export interface FrontierAdapterProps {
  data: FrontierChartData;
  height?: number;
  theme: FrontierChartTheme;
  reduceMotion?: boolean;
}

export interface FrontierAdapterComponent {
  (props: FrontierAdapterProps): React.ReactElement | null;
}

// ── 어댑터 구현체가 준수해야 할 인터페이스 ───────────────────
export interface ChartAdapterComponent {
  (props: ChartAdapterProps): React.ReactElement | null;
}
