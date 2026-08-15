interface Props { value: number; }

export default function AmountLabel({ value }: Props) {
  const fmt = (n: number) => {
    if (n >= 1_000_000_000_000) return `${(n / 1_000_000_000_000).toFixed(1)}조`;
    if (n >= 100_000_000)       return `${(n / 100_000_000).toFixed(0)}억`;
    if (n >= 10_000)            return `${(n / 10_000).toFixed(0)}만`;
    return n.toLocaleString("ko-KR");
  };
  return <span className="text-sm tabular-nums dark:text-dracula-fg">{fmt(value)}</span>;
}
