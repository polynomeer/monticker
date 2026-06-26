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
}

// ── 어댑터 구현체가 준수해야 할 인터페이스 ───────────────────
export interface ChartAdapterComponent {
  (props: ChartAdapterProps): React.ReactElement | null;
}
