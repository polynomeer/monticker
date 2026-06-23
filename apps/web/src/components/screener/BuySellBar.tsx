interface Props { buy: number; sell: number; }

export default function BuySellBar({ buy, sell }: Props) {
  return (
    <div className="flex items-center gap-1 w-full min-w-[80px]">
      <div className="relative flex h-2 flex-1 rounded-full overflow-hidden bg-[#44475a]">
        <div
          className="h-full rounded-l-full"
          style={{ width: `${buy}%`, backgroundColor: "#ff5050", opacity: 0.8 }}
        />
        <div
          className="h-full rounded-r-full ml-auto"
          style={{ width: `${sell}%`, backgroundColor: "#0078ff", opacity: 0.8 }}
        />
      </div>
      <div className="flex gap-1 text-[10px] shrink-0">
        <span style={{ color: "#ff5050" }}>{buy}</span>
        <span className="text-[#44475a]">/</span>
        <span style={{ color: "#0078ff" }}>{sell}</span>
      </div>
    </div>
  );
}
