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

export interface ChartAdapterProps {
  candles: CandleData[];
  events?: EventMarker[];
  height?: number;
  theme: ChartTheme;
  vwapData?: VwapPoint[];
  /** 차트 위 이벤트 마커를 클릭했을 때 — 이벤트 타임라인으로 점프하는 크로스 내비게이션용 */
  onEventClick?: (eventId: number) => void;
}

// ── 어댑터 구현체가 준수해야 할 인터페이스 ───────────────────
export interface ChartAdapterComponent {
  (props: ChartAdapterProps): React.ReactElement | null;
}
