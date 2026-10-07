/** 라우트 전환 중 골격 — 터미널 패널 모양의 자리표시자 */
export default function Loading() {
  return (
    <div role="status" aria-label="불러오는 중" className="flex min-h-screen flex-col gap-2 bg-tm-page p-2">
      <div className="h-14 animate-pulse rounded-[10px] bg-tm-panel/60" />
      <div className="flex flex-1 flex-wrap gap-2">
        <div className="min-h-[420px] flex-[999_1_620px] animate-pulse rounded-[10px] bg-tm-panel" />
        <div className="flex min-h-[420px] flex-[1_1_310px] flex-col gap-2">
          <div className="flex-1 animate-pulse rounded-[10px] bg-tm-panel" />
          <div className="flex-1 animate-pulse rounded-[10px] bg-tm-panel" />
        </div>
      </div>
      <span className="sr-only">불러오는 중...</span>
    </div>
  );
}
