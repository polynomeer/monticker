interface Props { rate: number; amount: number; }

function fmt(n: number) {
  return Math.abs(n) >= 100_000_000
    ? `${(n / 100_000_000).toFixed(0)}억`
    : n.toLocaleString("ko-KR");
}

export default function ChangeRateBadge({ rate, amount }: Props) {
  const up   = rate >= 0;
  const color = up ? "#ff5050" : "#0078ff";
  const sign  = up ? "+" : "";
  return (
    <div className="flex flex-col items-end gap-0.5">
      <span
        className="text-sm font-semibold px-2 py-0.5 rounded"
        style={{ color, backgroundColor: `${color}18` }}
      >
        {sign}{rate.toFixed(2)}%
      </span>
      <span className="text-[10px]" style={{ color }}>
        {sign}{fmt(amount)}
      </span>
    </div>
  );
}
