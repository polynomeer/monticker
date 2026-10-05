import { AutoGrid, Bar, PreviewTag, Tile } from "@/components/terminal";
import { cn } from "@/lib/utils";

export interface Gauge {
  name: string;
  value: string;
  limit: string;
  /** 한도 대비 사용률 0~1+ — null이면 한도가 없어 막대를 비운다 */
  ratio: number | null;
  sub: string;
  preview?: boolean;
}

export const LEVELS = [
  { name: "안전", text: "text-dracula-green", bg: "bg-dracula-green" },
  { name: "보통", text: "text-dracula-yellow", bg: "bg-dracula-yellow" },
  { name: "주의", text: "text-dracula-orange", bg: "bg-dracula-orange" },
  { name: "위험", text: "text-[#ff5555]", bg: "bg-[#ff5555]" },
] as const;

/** 한도 사용률 → 단계: 50% 미만 안전, 80% 미만 보통, 한도 미만 주의, 넘으면 위험 */
export function levelOf(ratio: number) {
  return ratio >= 1 ? 3 : ratio >= 0.8 ? 2 : ratio >= 0.5 ? 1 : 0;
}

export function RiskLevel({ level }: { level: number }) {
  const l = LEVELS[level];
  return (
    <div className="flex flex-wrap items-center gap-4">
      <div className="flex flex-col gap-1">
        <span className="text-xs text-tm-muted">현재 리스크 수준</span>
        <span className={cn("text-[1.625rem] font-bold", l.text)}>{l.name}</span>
      </div>
      <div className="flex min-w-[220px] flex-1 flex-col gap-1.5">
        <div className="flex h-2.5 gap-0.5 rounded-full" role="img" aria-label={`리스크 수준 ${l.name} (안전·보통·주의·위험 4단계 중 ${level + 1}단계)`}>
          {LEVELS.map((x, i) => (
            <div
              key={x.name}
              className={cn("flex-1", x.bg, i === 0 && "rounded-l-full", i === LEVELS.length - 1 && "rounded-r-full", i === level && "outline outline-2 outline-offset-1 outline-dracula-fg")}
            />
          ))}
        </div>
        <div className="flex justify-between text-2xs text-tm-muted">
          {LEVELS.map((x) => <span key={x.name}>{x.name}</span>)}
        </div>
      </div>
    </div>
  );
}

export function RiskGauges({ gauges }: { gauges: Gauge[] }) {
  return (
    <AutoGrid min={220}>
      {gauges.map((g) => {
        const l = g.ratio == null ? null : LEVELS[levelOf(g.ratio)];
        return (
          <Tile key={g.name}>
            <div className="flex justify-between gap-2 text-xs">
              <span className="flex items-center gap-1.5 text-tm-soft">{g.name}{g.preview && <PreviewTag />}</span>
              <span className="num text-tm-muted">한도 {g.limit}</span>
            </div>
            <span className={cn("num text-2xl font-semibold", l?.text ?? "text-dracula-fg")}>{g.value}</span>
            <Bar pct={(g.ratio ?? 0) * 100} color={l?.bg ?? "bg-tm-line2"} h={8} track="bg-tm-panel" />
            <span className="text-2xs text-tm-muted">{g.sub}</span>
          </Tile>
        );
      })}
    </AutoGrid>
  );
}
