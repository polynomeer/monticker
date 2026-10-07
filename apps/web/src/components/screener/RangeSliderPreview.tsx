/**
 * 시안의 범위 슬라이더(등락률·거래량 배수·시가총액) 모양만 그린다.
 * 스크리너 API(/api/screener)에 범위 필터 파라미터가 없어 조작할 수 없다 — 준비 중 표식과 함께 비활성.
 */
export default function RangeSliderPreview({ label, from, to, pa, pb }: { label: string; from: string; to: string; pa: number; pb: number }) {
  return (
    <div className="flex flex-col gap-2 opacity-60" aria-disabled="true" title="준비 중 — 아직 동작하지 않습니다">
      <div className="flex justify-between text-xs">
        <span className="text-tm-soft">{label}</span>
        <span className="num text-tm-muted">{from} ~ {to}</span>
      </div>
      <div className="relative h-1 rounded-full bg-tm-inner" role="presentation">
        <div className="absolute h-full rounded-full bg-dracula-purple" style={{ left: `${pa}%`, right: `${100 - pb}%` }} />
        <span className="absolute -top-[5px] -ml-[7px] h-3.5 w-3.5 rounded-full bg-dracula-fg" style={{ left: `${pa}%` }} />
        <span className="absolute -top-[5px] -ml-[7px] h-3.5 w-3.5 rounded-full bg-dracula-fg" style={{ left: `${pb}%` }} />
      </div>
    </div>
  );
}
