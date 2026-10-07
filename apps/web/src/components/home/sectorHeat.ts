// 홈 섹터 히트맵의 순수 로직(ADR-087) — 컴포넌트와 분리해 테스트한다.

/** GET /api/screener/sectors/performance 의 섹터 한 행 */
export interface SectorPerformance {
  sector: string;
  /** 섹터의 활성 종목 수 */
  stockCount: number;
  /** 그중 등락률을 계산할 수 있는 종목 수 */
  pricedCount: number;
  /** 등락률(%)의 단순 평균(동일가중). 계산할 종목이 없으면 null */
  avgChangeRate: number | null;
  advancers: number;
  decliners: number;
  unchanged: number;
  /** 오늘(KST) 그 섹터 종목의 이벤트 수 */
  eventCount: number;
}

export interface SectorPerformanceResponse {
  market: string;
  weighting: "EQUAL" | string;
  eventsSince: string;
  sectors: SectorPerformance[];
  updatedAt: string;
}

/** 이 절댓값(%)에서 색이 가장 진해진다 */
export const HEAT_SATURATION_PCT = 3;

/**
 * 등락률 → 타일 배경색. 상승/하락 색은 사용자 차트 테마(--mt-up/--mt-down)를 따른다.
 * 값이 없으면 null(호출부가 중립 배경을 쓴다) — 0%와 "데이터 없음"을 같은 색으로 칠하지 않는다.
 */
export function heatBackground(avg: number | null | undefined): string | null {
  if (avg == null || !Number.isFinite(avg)) return null;
  const strength = Math.min(Math.abs(avg) / HEAT_SATURATION_PCT, 1);
  const alpha = (0.12 + strength * 0.6).toFixed(2);
  if (avg === 0) return `rgba(164,171,207,0.12)`;
  return avg > 0 ? `rgb(var(--mt-up) / ${alpha})` : `rgb(var(--mt-down) / ${alpha})`;
}

/** 히트맵에 그릴 섹터 — 시세가 있는 섹터를 종목 수 많은 순으로, 시세가 없는 섹터는 뒤로. 최대 n개 */
export function heatmapSectors(rows: SectorPerformance[], n = 24): SectorPerformance[] {
  return [...rows]
    .sort((a, b) => {
      const pa = a.avgChangeRate == null ? 1 : 0;
      const pb = b.avgChangeRate == null ? 1 : 0;
      return pa - pb || b.stockCount - a.stockCount || a.sector.localeCompare(b.sector);
    })
    .slice(0, n);
}
