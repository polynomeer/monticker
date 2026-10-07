// 디자인 시안(monticker 디자인 시안 캔버스)의 스트로크 아이콘을 그대로 옮긴 세트.
// viewBox 24, stroke=currentColor — 색은 부모 text-* 클래스로 준다.
import type { SVGProps } from "react";

const PATHS = {
  home: <><path d="M3 11l9-8 9 8v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"></path></>,
  candles: <><path d="M7 3v4M7 17v4M17 3v2M17 13v8"></path><rect x="5" y="7" width="4" height="10" rx="1"></rect><rect x="15" y="5" width="4" height="8" rx="1"></rect></>,
  filter: <><path d="M3 5h18l-7 8v6l-4 2v-8z"></path></>,
  star: <><path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"></path></>,
  pie: <><path d="M21 12A9 9 0 1 1 12 3v9z"></path><path d="M15 3.5A9 9 0 0 1 20.5 9H15z"></path></>,
  wallet: <><rect x="3" y="6" width="18" height="14" rx="2"></rect><path d="M3 10h18M16 15h2"></path></>,
  flask: <><path d="M9 3h6M10 3v6L4.5 18.5A1.5 1.5 0 0 0 5.8 21h12.4a1.5 1.5 0 0 0 1.3-2.5L14 9V3"></path><path d="M7 15h10"></path></>,
  store: <><path d="M4 9l1.5-5h13L20 9M4 9v11h16V9M4 9h16M9 20v-6h6v6"></path></>,
  bank: <><path d="M3 10l9-6 9 6M5 10v8M10 10v8M14 10v8M19 10v8M3 20h18"></path></>,
  shield: <><path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"></path></>,
  zap: <><path d="M13 2L4 14h7l-1 8 9-12h-7z"></path></>,
  bell: <><path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9"></path><path d="M10 21a2 2 0 0 0 4 0"></path></>,
  sliders: <><path d="M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1"></path><circle cx="15" cy="6" r="2"></circle><circle cx="9" cy="12" r="2"></circle><circle cx="17" cy="18" r="2"></circle></>,
  search: <><circle cx="11" cy="11" r="7"></circle><path d="M20 20l-3.5-3.5"></path></>,
  expand: <><path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7"></path></>,
  plus: <><path d="M12 5v14M5 12h14"></path></>,
  minus: <><path d="M5 12h14"></path></>,
  x: <><path d="M6 6l12 12M18 6L6 18"></path></>,
  grid: <><rect x="4" y="4" width="4" height="4"></rect><rect x="10" y="4" width="4" height="4"></rect><rect x="16" y="4" width="4" height="4"></rect><rect x="4" y="10" width="4" height="4"></rect><rect x="10" y="10" width="4" height="4"></rect><rect x="16" y="10" width="4" height="4"></rect><rect x="4" y="16" width="4" height="4"></rect><rect x="10" y="16" width="4" height="4"></rect><rect x="16" y="16" width="4" height="4"></rect></>,
  user: <><circle cx="12" cy="8" r="4"></circle><path d="M4 21a8 8 0 0 1 16 0"></path></>,
  layout: <><rect x="3" y="4" width="18" height="16" rx="2"></rect><path d="M9 4v16M9 11h12"></path></>,
  clock: <><circle cx="12" cy="12" r="9"></circle><path d="M12 7v5l3 2"></path></>,
  lock: <><rect x="5" y="11" width="14" height="10" rx="2"></rect><path d="M8 11V7a4 4 0 0 1 8 0v4"></path></>,
  check: <><path d="M5 12l5 5 9-10"></path></>,
  download: <><path d="M12 3v12M7 10l5 5 5-5M5 21h14"></path></>,
  trend: <><path d="M3 17l6-6 4 4 8-8M15 7h6v6"></path></>,
  calendar: <><rect x="3" y="5" width="18" height="16" rx="2"></rect><path d="M3 10h18M8 3v4M16 3v4"></path></>,
  pencil: <><path d="M4 20l4-1 11-11-3-3L5 16z"></path></>,
  cross: <><path d="M12 3v18M3 12h18"></path></>,
  line: <><path d="M4 20L20 4"></path><circle cx="4" cy="20" r="1.5"></circle><circle cx="20" cy="4" r="1.5"></circle></>,
  hlines: <><path d="M3 6h18M3 12h18M3 18h18"></path></>,
  text: <><path d="M5 5h14M12 5v15"></path></>,
  ruler: <><path d="M3 17L17 3l4 4L7 21z"></path><path d="M7 13l2 2M10 10l2 2M13 7l2 2"></path></>,
  zoom: <><circle cx="11" cy="11" r="7"></circle><path d="M20 20l-3.5-3.5M8 11h6M11 8v6"></path></>,
  magnet: <><path d="M6 3v8a6 6 0 0 0 12 0V3"></path><path d="M6 7h4M14 7h4"></path></>,
  eye: <><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"></path><circle cx="12" cy="12" r="3"></circle></>,
  trash: <><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"></path></>,
  play: <><path d="M7 5l12 7-12 7z"></path></>,
  pause: <><path d="M8 5v14M16 5v14"></path></>,
  skipb: <><path d="M18 5l-10 7 10 7zM6 5v14"></path></>,
  skipf: <><path d="M6 5l10 7-10 7zM18 5v14"></path></>,
  compare: <><path d="M7 4v16M7 4L3 8M7 4l4 4M17 20V4M17 20l-4-4M17 20l4-4"></path></>,
  doc: <><path d="M6 3h9l4 4v14H6zM14 3v5h5M9 13h6M9 17h6"></path></>,
  news: <><rect x="3" y="5" width="18" height="15" rx="2"></rect><path d="M7 9h10M7 13h6M7 17h8"></path></>,
  bars: <><path d="M5 20V10M10 20V4M15 20v-8M20 20v-5"></path></>,
  chev: <><path d="M6 9l6 6 6-6"></path></>,
  chevr: <><path d="M9 6l6 6-6 6"></path></>,
  chevl: <><path d="M15 6l-6 6 6 6"></path></>,
  key: <><circle cx="8" cy="15" r="4"></circle><path d="M11 12l9-9M16 7l3 3"></path></>,
  info: <><circle cx="12" cy="12" r="9"></circle><path d="M12 11v6M12 7.5v.5"></path></>,
  alert: <><path d="M12 3l10 18H2z"></path><path d="M12 10v5M12 18v.5"></path></>,
  refresh: <><path d="M20 11a8 8 0 1 0-2 6M20 4v7h-7"></path></>,
  mail: <><rect x="3" y="5" width="18" height="14" rx="2"></rect><path d="M3 7l9 6 9-6"></path></>,
  sun: <><circle cx="12" cy="12" r="4"></circle><path d="M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5L19 19M5 19l1.5-1.5M17.5 6.5L19 5"></path></>,
  moon: <><path d="M20 14A8 8 0 1 1 10 4a6 6 0 0 0 10 10z"></path></>,
  badge: <><path d="M12 2l2.4 2.2 3.2-.4.8 3.1 2.8 1.6-1.2 3 1.2 3-2.8 1.6-.8 3.1-3.2-.4L12 22l-2.4-2.2-3.2.4-.8-3.1L2.8 15.5l1.2-3-1.2-3 2.8-1.6.8-3.1 3.2.4z"></path><path d="M8.5 12l2.5 2.5 4.5-5"></path></>,
  users: <><circle cx="9" cy="8" r="3.5"></circle><path d="M2.5 20a6.5 6.5 0 0 1 13 0M16 4.5a3.5 3.5 0 0 1 0 7M18 14a6 6 0 0 1 3.5 6"></path></>,
  send: <><path d="M4 12l16-8-6 16-2.5-6.5z"></path></>,
  share: <><circle cx="6" cy="12" r="2.5"></circle><circle cx="18" cy="6" r="2.5"></circle><circle cx="18" cy="18" r="2.5"></circle><path d="M8.2 10.8l7.6-3.6M8.2 13.2l7.6 3.6"></path></>,
  copy: <><rect x="8" y="8" width="13" height="13" rx="2"></rect><path d="M5 16H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h11a1 1 0 0 1 1 1v1"></path></>,
  dots: <><circle cx="5" cy="12" r="1.2"></circle><circle cx="12" cy="12" r="1.2"></circle><circle cx="19" cy="12" r="1.2"></circle></>,
  globe: <><circle cx="12" cy="12" r="9"></circle><path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"></path></>,
  type: <><path d="M4 7V5h16v2M9 19h6M12 5v14"></path></>,
  card: <><rect x="3" y="5" width="18" height="14" rx="2"></rect><path d="M3 10h18M7 15h4"></path></>,
  target: <><circle cx="12" cy="12" r="9"></circle><circle cx="12" cy="12" r="5"></circle><circle cx="12" cy="12" r="1"></circle></>,
  flag: <><path d="M5 21V4h11l-2 4 2 4H5"></path></>,
} as const;

export type IconName = keyof typeof PATHS;

interface IconProps extends Omit<SVGProps<SVGSVGElement>, "name"> {
  name: IconName;
  size?: number;
  strokeWidth?: number;
}

export function Icon({ name, size = 18, strokeWidth = 1.8, ...rest }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...rest}
    >
      {PATHS[name]}
    </svg>
  );
}
