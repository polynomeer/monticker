// 효율적 프론티어 차트 — 시안 analytics()의 산점도 SVG를 실제 /frontier 응답으로 그린다.
// 시안의 회색 무작위 점(무작위 포트폴리오 표본)은 백엔드가 주지 않으므로 그리지 않는다.

export interface FrontierPt { risk: number; ret: number }

export function FrontierChart({ points, optimal, current, held }: {
  /** 연 변동성·연 수익률, % 단위 */
  points: FrontierPt[];
  optimal?: FrontierPt;
  /** 동일가중 */
  current?: FrontierPt;
  /** 사용자의 현재(모의투자) 보유 비중 */
  held?: FrontierPt;
}) {
  const W = 560;
  const H = 300;
  const all = [...points, ...(optimal ? [optimal] : []), ...(current ? [current] : []), ...(held ? [held] : [])];
  if (all.length === 0) {
    return (
      <div className="grid h-[300px] place-items-center rounded-lg border border-dashed border-tm-line2 text-center text-13 text-tm-muted">
        종목을 2개 이상 고르고 &lsquo;최적 비중 계산&rsquo;을 누르면<br />위험-수익 곡선을 그립니다.
      </div>
    );
  }
  const pad = (lo: number, hi: number) => {
    const d = (hi - lo || Math.abs(hi) || 1) * 0.12;
    return [lo - d, hi + d] as const;
  };
  const [x0, x1] = pad(Math.min(...all.map((p) => p.risk)), Math.max(...all.map((p) => p.risk)));
  const [y0, y1] = pad(Math.min(...all.map((p) => p.ret)), Math.max(...all.map((p) => p.ret)));
  const x = (v: number) => 44 + ((v - x0) / (x1 - x0)) * (W - 60);
  const y = (v: number) => 10 + ((y1 - v) / (y1 - y0)) * (H - 40);
  const yt = [0, 1, 2, 3, 4].map((k) => y0 + ((y1 - y0) * k) / 4);
  const xt = [0, 1, 2, 3, 4].map((k) => x0 + ((x1 - x0) * k) / 4);
  const sorted = [...points].sort((a, b) => a.risk - b.risk);

  return (
    <svg viewBox={`0 0 ${W + 10} ${H + 16}`} className="block h-auto w-full" role="img" aria-label={`효율적 프론티어 — 최적화 포트폴리오와 동일가중${held ? "·현재 보유" : ""} 포트폴리오 표시`}>
      {yt.map((v) => (
        <g key={`y${v}`}>
          <line x1={44} x2={W} y1={y(v)} y2={y(v)} stroke="#34364a" strokeDasharray="2 4" />
          <text x={4} y={y(v) + 4} fill="#a4abcf" fontSize={11} className="num">{v.toFixed(1)}%</text>
        </g>
      ))}
      {xt.map((v) => (
        <text key={`x${v}`} x={x(v)} y={H - 8} fill="#a4abcf" fontSize={11} textAnchor="middle" className="num">{v.toFixed(1)}%</text>
      ))}
      {sorted.map((p, i) => (
        <circle key={i} cx={x(p.risk)} cy={y(p.ret)} r={2.6} fill="#c3c8e2" fillOpacity={0.45} />
      ))}
      {sorted.length > 1 && (
        <polyline points={sorted.map((p) => `${x(p.risk).toFixed(1)},${y(p.ret).toFixed(1)}`).join(" ")} fill="none" stroke="#bd93f9" strokeWidth={2.4} />
      )}
      {optimal && (
        <g>
          <circle cx={x(optimal.risk)} cy={y(optimal.ret)} r={7} fill="#bd93f9" stroke="#1b1c24" strokeWidth={2} />
          <text x={x(optimal.risk) + 12} y={y(optimal.ret) - 8} fill="#f8f8f2" fontSize={12} fontWeight={600}>최적화 포트폴리오</text>
        </g>
      )}
      {current && (
        <g>
          <circle cx={x(current.risk)} cy={y(current.ret)} r={6} fill="none" stroke="#ffb86c" strokeWidth={2} />
          <text x={x(current.risk) + 10} y={y(current.ret) + 16} fill="#ffb86c" fontSize={12}>동일가중 포트폴리오</text>
        </g>
      )}
      {held && (
        <g>
          <rect x={x(held.risk) - 6} y={y(held.ret) - 6} width={12} height={12} fill="#8be9fd" stroke="#1b1c24" strokeWidth={2} transform={`rotate(45 ${x(held.risk)} ${y(held.ret)})`} />
          <text x={x(held.risk) + 12} y={y(held.ret) + 4} fill="#8be9fd" fontSize={12}>현재 보유 비중</text>
        </g>
      )}
      <text x={W / 2} y={H + 10} fill="#a4abcf" fontSize={11} textAnchor="middle">예상 위험 (연 변동성) →</text>
    </svg>
  );
}
