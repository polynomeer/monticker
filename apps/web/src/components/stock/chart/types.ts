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
}

export interface ChartTheme {
  bg: string;
  text: string;
  grid: string;
  upColor: string;
  downColor: string;
}

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

export type DrawingTool = "TREND_LINE" | "HORIZONTAL_LINE";

/** 드로잉 좌표는 (시각, 가격) 데이터 좌표로 저장한다 — 줌/팬해도 캔들에 고정되도록. */
export interface Drawing {
  id: string;
  tool: DrawingTool;
  points: Array<{ time: number; price: number }>;
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
  /** 실전투자 미체결 주문을 가격선으로 표시 */
  orderLines?: OrderLine[];
  onCancelOrderLine?: (orderId: number) => void;
  /** 현재 선택된 드로잉 도구 — null이면 그리기 비활성 (차트는 평소처럼 줌/팬만) */
  activeDrawingTool?: DrawingTool | null;
  drawings?: Drawing[];
  onDrawingsChange?: (drawings: Drawing[]) => void;
}

// ── 어댑터 구현체가 준수해야 할 인터페이스 ───────────────────
export interface ChartAdapterComponent {
  (props: ChartAdapterProps): React.ReactElement | null;
}
