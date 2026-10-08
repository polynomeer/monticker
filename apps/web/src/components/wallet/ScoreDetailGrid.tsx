import { AutoGrid, Stat } from "@/components/terminal";
import { PLANNED_DEFINITION } from "@/components/wallet/origin";
import {
  STOP_LOSS_DEFINITION, WEEK_DEFINITION, deltaPpText, ratioText, scoreDeltaText, type ScoreDetails, type WeeklyRatio,
} from "@/components/wallet/insights";

function ratioSub(r: WeeklyRatio, unit: string) {
  const delta = deltaPpText(r.deltaPp);
  if (r.thisWeek.denominator === 0) return `이번 주 ${unit} 없음`;
  return delta ? `지난주 대비 ${delta}` : `${r.thisWeek.numerator}/${r.thisWeek.denominator}건`;
}

/**
 * ADR-091 — /wallet 점수 카드 세부 지표(이번 주, KST 월~일). 분모가 0이면 "—".
 * 수치는 기록을 돌아보기 위한 행동 피드백이며 투자 판단의 기준이 아니다.
 */
export function ScoreDetailGrid({ details }: { details: ScoreDetails | null | undefined }) {
  if (!details) return null;
  const { planAdherence: plan, stopLossAdherence: stop, behaviorScore: score } = details;
  const scoreDelta = scoreDeltaText(score.delta);
  return (
    <AutoGrid min={130}>
      <span title={`${PLANNED_DEFINITION}\n${WEEK_DEFINITION}`}>
        <Stat
          label="계획 준수율 · 이번 주"
          value={ratioText(plan.thisWeek)}
          valueClassName={plan.thisWeek.denominator === 0 ? "text-tm-muted" : undefined}
          sub={ratioSub(plan, "판정할 주문")}
        />
      </span>
      <span title={`${STOP_LOSS_DEFINITION}\n${WEEK_DEFINITION}`}>
        <Stat
          label="손절 준수율 · 이번 주"
          value={ratioText(stop.thisWeek)}
          valueClassName={stop.thisWeek.denominator === 0 ? "text-tm-muted" : undefined}
          sub={ratioSub(stop, "손절을 정한 손실 매도")}
        />
      </span>
      <span title={`행동 점수의 이번 주 평균 − 지난주 평균(그 주에 계산된 날의 평균). ${WEEK_DEFINITION}`}>
        <Stat
          label="행동 점수 · 지난주 대비"
          value={scoreDelta ?? "—"}
          valueClassName={scoreDelta == null ? "text-tm-muted" : undefined}
          sub={
            score.lastWeekAvg == null
              ? "지난주 기록 없음"
              : `지난주 평균 ${score.lastWeekAvg.toFixed(0)}`
          }
        />
      </span>
    </AutoGrid>
  );
}
