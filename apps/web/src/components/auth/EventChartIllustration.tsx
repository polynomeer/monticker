// 로그인·회원가입 브랜드 패널의 장식용 차트. 실제 시세가 아니라 고정된 예시 데이터다 —
// "이벤트가 표시된 차트 예시"로 라벨을 붙여 실제 데이터로 오인되지 않게 한다.

// [open, high, low, close, volume(상대값)]
const DATA: [number, number, number, number, number][] = [
  [70250,70272,70233,70242,1.08],[70242,70252,70219,70230,1.10],[70230,70342,70213,70331,1.62],[70331,70371,70312,70361,0.94],
  [70361,70435,70195,70232,2.64],[70232,70312,70183,70207,0.98],[70207,70264,70179,70247,0.96],[70247,70458,70217,70406,1.91],
  [70406,70410,70364,70391,1.27],[70391,70459,70375,70426,1.33],[70426,70436,70354,70372,1.10],[70372,70418,70237,70260,2.33],
  [70260,70300,70239,70265,0.97],[70265,70275,70241,70274,1.05],[70274,70425,70269,70409,2.55],[70409,70435,70254,70314,1.81],
  [70314,70336,70192,70246,1.45],[70246,70254,70076,70089,2.46],[70089,70195,69928,69943,1.85],[69943,69948,69888,69895,1.30],
  [69895,69948,69829,69930,0.88],[69930,69997,69888,69996,1.91],[69996,70112,69913,70090,2.10],[70090,70112,70025,70051,1.19],
  [70051,70119,69931,69955,1.25],[69955,69959,69924,69956,1.00],[69956,69973,69871,69914,1.16],[69914,70268,69872,70173,3.28],
  [70173,71302,70167,71296,11.88],[71296,71669,71210,71652,4.33],[71652,71680,71551,71599,0.89],[71599,71714,71587,71687,1.18],
  [71687,71699,71662,71686,0.69],[71686,71707,71679,71701,1.43],[71701,71717,71678,71678,1.03],[71678,71832,71620,71772,2.31],
  [71772,71783,71673,71690,1.38],[71690,71732,71609,71725,0.91],[71725,71963,71701,71948,2.65],[71948,72006,71939,71954,1.44],
  [71954,72106,71951,72055,1.25],[72055,72160,72002,72152,2.05],[72152,72179,72090,72137,1.42],[72137,72165,71844,71919,2.99],
  [71919,72000,71913,71949,0.75],[71949,71986,71916,71980,1.40],[71980,72097,71861,72085,2.36],[72085,72105,71977,72000,1.24],
];

// 이벤트 핀: [캔들 인덱스, 글자, 색, y]
const PINS: [number, string, string, number][] = [
  [14, "D", "#ffb86c", 18],
  [27, "N", "#8be9fd", 18],
  [28, "V", "#bd93f9", 42],
  [29, "Q", "#50fa7b", 18],
];
const BAND: [number, number] = [27, 31];

const W = 520;
const H = 260;
const VH = 60;
const TOP = 16;
const PH = H - VH - 14 - 24 - TOP;
const UP = "rgb(var(--mt-up))";
const DOWN = "rgb(var(--mt-down))";
const LINE = "#34364a";
const MUTED = "#a4abcf";
const PAGE = "#1b1c24";

export function EventChartIllustration() {
  const lo = Math.min(...DATA.map((d) => d[2])) * 0.998;
  const hi = Math.max(...DATA.map((d) => d[1])) * 1.002;
  const cw = W / DATA.length;
  const y = (v: number) => TOP + ((hi - v) / (hi - lo)) * PH;
  const vmax = Math.max(...DATA.map((d) => d[4]));
  const grid = [0, 1, 2, 3, 4].map((k) => lo + ((hi - lo) * k) / 4);

  return (
    <svg viewBox={`0 0 ${W + 68} ${H}`} className="block h-auto w-full" role="img" aria-label="이벤트가 표시된 차트 예시">
      {grid.map((gv) => (
        <g key={gv}>
          <line x1={0} x2={W} y1={y(gv)} y2={y(gv)} stroke={LINE} strokeDasharray="2 4" />
          <text x={W + 8} y={y(gv) + 4} fill={MUTED} fontSize={11} className="num">
            {Math.round(gv).toLocaleString("ko-KR")}
          </text>
        </g>
      ))}
      <rect x={BAND[0] * cw} y={TOP - 8} width={(BAND[1] - BAND[0]) * cw} height={H - TOP - 16} fill="#bd93f9" fillOpacity={0.09} />
      {DATA.map(([o, h, l, c, v], i) => {
        const cx = i * cw + cw / 2;
        const col = c >= o ? UP : DOWN;
        const vh = (v / vmax) * VH;
        const inBand = i >= BAND[0] && i < BAND[1];
        return (
          <g key={i}>
            <line x1={cx} x2={cx} y1={y(h)} y2={y(l)} stroke={col} strokeWidth={1.1} />
            <rect x={cx - cw * 0.32} y={Math.min(y(o), y(c))} width={Math.max(cw * 0.64, 1)} height={Math.max(Math.abs(y(o) - y(c)), 1.1)} rx={0.8} fill={col} />
            <rect x={cx - cw * 0.32} y={H - 24 - vh} width={Math.max(cw * 0.64, 1)} height={vh} fill={col} fillOpacity={inBand ? 0.85 : 0.32} />
          </g>
        );
      })}
      {PINS.map(([i, ch, col, py]) => {
        const cx = i * cw + cw / 2;
        return (
          <g key={ch}>
            <line x1={cx} x2={cx} y1={py + 10} y2={TOP + PH} stroke={col} strokeOpacity={0.5} strokeDasharray="2 3" />
            <circle cx={cx} cy={py} r={10} fill={PAGE} stroke={col} strokeWidth={2} />
            <text x={cx} y={py + 4} textAnchor="middle" fill={col} fontSize={11} fontWeight={700} className="num">
              {ch}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
