import { APP_THEME_KEYS, hexToChannels } from "@/lib/themes";
import { CHART_THEMES, THEME_STORAGE_KEY } from "@/stores/themeStore";

/**
 * 첫 페인트 전에 저장된 앱 테마를 html[data-theme]에 단다 — 없으면 하이드레이션 뒤 ThemeProvider가 달 때까지
 * 드라큘라로 그렸다가 바뀌는 깜빡임이 생긴다. CSP는 script-src 'unsafe-inline'을 이미 허용한다(next.config.ts:
 * Next 하이드레이션 페이로드·next-themes 스크립트도 인라인). '글로벌'이 아닌 시세 색상도 여기서 --mt-up/down에 넣는다.
 * 실패해도(저장소 막힘·깨진 JSON) 조용히 드라큘라로 남고, ThemeProvider가 하이드레이션 뒤 다시 맞춘다.
 */
export function themeInitScript(): string {
  const market = Object.fromEntries(
    Object.entries(CHART_THEMES)
      .filter(([k]) => k !== "default")
      .map(([k, t]) => [k, [hexToChannels(t.upColor), hexToChannels(t.downColor)]]),
  );
  return `(function(){try{var s=JSON.parse(localStorage.getItem(${JSON.stringify(THEME_STORAGE_KEY)})||"null");s=s&&s.state;if(!s)return;var r=document.documentElement;if(${JSON.stringify(APP_THEME_KEYS)}.indexOf(s.appTheme)>=0)r.setAttribute("data-theme",s.appTheme);var m=${JSON.stringify(market)}[s.chartTheme];if(m){r.style.setProperty("--mt-up",m[0]);r.style.setProperty("--mt-down",m[1]);}}catch(e){}})();`;
}

export default function ThemeScript() {
  return <script dangerouslySetInnerHTML={{ __html: themeInitScript() }} />;
}
