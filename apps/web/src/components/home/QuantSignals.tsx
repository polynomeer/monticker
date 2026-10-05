import Link from "next/link";
import { Icon, Panel } from "@/components/terminal";

/**
 * 시안의 "퀀트 시그널" 피드. 내 전략·구독 전략의 시그널을 한데 모아 주는 API가 아직 없다
 * (전략별 포워드 테스트 시그널만 /api/quant/rulesets/{id}/forward-test 로 존재) — 준비 중 상태로 둔다.
 */
export default function QuantSignals() {
  return (
    <Panel tabs={["퀀트 시그널"]} actions={["expand"]} preview bodyClassName="px-3.5 pb-3 pt-1.5">
      <div className="flex gap-2.5 py-3">
        <span className="grid pt-0.5 text-tm-muted"><Icon name="zap" size={15} /></span>
        <div className="flex flex-1 flex-col gap-0.5">
          <span className="text-13 font-semibold text-tm-soft">아직 표시할 시그널이 없습니다</span>
          <span className="text-xs text-tm-muted">내 전략·구독 전략의 진입 신호가 여기에 모입니다.</span>
        </div>
      </div>
      <Link href="/quant-lab" className="text-xs text-dracula-purple hover:underline">퀀트랩에서 전략 보기 →</Link>
    </Panel>
  );
}
