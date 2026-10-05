import { PreviewTag, Tile } from "@/components/terminal";

// 시안의 지수 카드(KOSPI·KOSDAQ·USD/KRW). 지수·환율 시세 API가 아직 없어 값 대신 "—"를 보여준다.
const INDICES = ["KOSPI", "KOSDAQ", "USD/KRW"];

export default function IndexCards() {
  return (
    <div className="grid gap-2.5" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(220px,1fr))" }}>
      {INDICES.map((n) => (
        <Tile key={n}>
          <div className="flex items-start justify-between">
            <div className="flex flex-col gap-1">
              <span className="text-xs text-tm-muted">{n}</span>
              <span className="num text-xl font-semibold text-tm-muted">—</span>
              <span className="text-xs text-tm-muted">지수 시세 준비 중</span>
            </div>
            <PreviewTag />
          </div>
        </Tile>
      ))}
    </div>
  );
}
