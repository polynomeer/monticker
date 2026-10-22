import Svg, { Circle, Line, Path, Polygon, Rect } from "react-native-svg";

/** 시안의 선 아이콘(24 그리드, 둥근 끝). 색은 부모가 정한다 */
export type IconName =
  | "home" | "star" | "wallet" | "search" | "user" | "chevronRight" | "chevronLeft" | "sort" | "plus" | "minus"
  | "flask" | "file" | "alert" | "clock" | "mic" | "bell" | "moon" | "mail" | "shield" | "bank" | "check" | "close"
  | "share";

interface Props {
  name: IconName;
  size?: number;
  color: string;
  strokeWidth?: number;
  /** 채운 모양(관심종목에 든 별 등) */
  filled?: boolean;
}

export function Icon({ name, size = 24, color, strokeWidth = 2, filled }: Props) {
  const p = { stroke: color, strokeWidth, strokeLinecap: "round" as const, strokeLinejoin: "round" as const, fill: filled ? color : "none" };
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24">
      {SHAPES[name](p)}
    </Svg>
  );
}

type StrokeProps = { stroke: string; strokeWidth: number; strokeLinecap: "round"; strokeLinejoin: "round"; fill: string };

const SHAPES: Record<IconName, (p: StrokeProps) => React.ReactNode> = {
  home: (p) => <><Path d="M3 11 12 4l9 7" {...p} /><Path d="M5 10v10h5v-6h4v6h5V10" {...p} /></>,
  star: (p) => <Polygon points="12 3 14.8 8.8 21 9.6 16.5 14 17.6 20.3 12 17.3 6.4 20.3 7.5 14 3 9.6 9.2 8.8" {...p} />,
  wallet: (p) => <><Rect x="3" y="6" width="18" height="14" rx="3" {...p} /><Path d="M16 13h2" {...p} /><Path d="M3 10h18" {...p} /></>,
  search: (p) => <><Circle cx="11" cy="11" r="7" {...p} /><Line x1="21" y1="21" x2="16.6" y2="16.6" {...p} /></>,
  user: (p) => <><Circle cx="12" cy="8" r="4" {...p} /><Path d="M4 21a8 8 0 0 1 16 0" {...p} /></>,
  chevronRight: (p) => <Path d="m9 18 6-6-6-6" {...p} />,
  chevronLeft: (p) => <Path d="m15 18-6-6 6-6" {...p} />,
  sort: (p) => <><Path d="M7 4v16" {...p} /><Path d="m3 8 4-4 4 4" {...p} /><Path d="M17 20V4" {...p} /><Path d="m21 16-4 4-4-4" {...p} /></>,
  plus: (p) => <><Path d="M12 5v14" {...p} /><Path d="M5 12h14" {...p} /></>,
  minus: (p) => <Path d="M5 12h14" {...p} />,
  flask: (p) => <><Path d="M9 3h6" {...p} /><Path d="M10 3v6L5 18a2 2 0 0 0 1.8 3h10.4a2 2 0 0 0 1.8-3L14 9V3" {...p} /></>,
  file: (p) => <><Path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z" {...p} /><Path d="M14 3v6h6" {...p} /></>,
  alert: (p) => <><Path d="M12 9v4" {...p} /><Path d="M12 17h.01" {...p} /><Path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" {...p} /></>,
  clock: (p) => <><Circle cx="12" cy="12" r="9" {...p} /><Path d="M12 7v5l3 2" {...p} /></>,
  mic: (p) => <><Rect x="9" y="3" width="6" height="11" rx="3" {...p} /><Path d="M5 11a7 7 0 0 0 14 0" {...p} /><Path d="M12 18v3" {...p} /></>,
  bell: (p) => <><Path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" {...p} /><Path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" {...p} /></>,
  moon: (p) => <Path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" {...p} />,
  mail: (p) => <><Rect x="3" y="5" width="18" height="14" rx="2" {...p} /><Path d="m3 7 9 6 9-6" {...p} /></>,
  shield: (p) => <Path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z" {...p} />,
  bank: (p) => <><Path d="M3 21h18" {...p} /><Path d="M5 21V10l7-5 7 5v11" {...p} /><Path d="M9 21v-6h6v6" {...p} /></>,
  check: (p) => <Path d="M20 6 9 17l-5-5" {...p} />,
  close: (p) => <><Path d="M18 6 6 18" {...p} /><Path d="m6 6 12 12" {...p} /></>,
  share: (p) => <><Path d="M12 3v12" {...p} fill="none" /><Path d="m7 8 5-5 5 5" {...p} fill="none" /><Path d="M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6" {...p} fill="none" /></>,
};
