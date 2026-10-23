import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { useColorScheme } from "react-native";
import AsyncStorage from "@react-native-async-storage/async-storage";
import {
  palettes, resolveMarketColors, resolveScheme,
  type ColorScheme, type MarketColors, type Palette, type SchemePreference, type UpDownPreference,
} from "./palette";

/**
 * ADR-113 — 화면 모드(기본 드라큘라 다크)와 상승·하락 색. 기기에만 저장한다(웹도 브라우저 저장).
 * 저장값을 읽기 전에도 기본값(다크·모드에 맞춤)으로 바로 그린다 — 대부분은 기본값이라 깜빡임이 없다.
 */

const SCHEME_KEY = "monticker.theme.scheme";
const UPDOWN_KEY = "monticker.theme.upDown";

const SCHEMES: SchemePreference[] = ["dark", "light", "system"];
const UPDOWNS: UpDownPreference[] = ["auto", "redUp", "greenUp"];

export interface Theme {
  scheme: ColorScheme;
  c: Palette;
  market: MarketColors;
  schemePreference: SchemePreference;
  upDownPreference: UpDownPreference;
  setSchemePreference: (p: SchemePreference) => void;
  setUpDownPreference: (p: UpDownPreference) => void;
}

const ThemeContext = createContext<Theme | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const system = useColorScheme();
  const [schemePreference, setScheme] = useState<SchemePreference>("dark");
  const [upDownPreference, setUpDown] = useState<UpDownPreference>("auto");

  useEffect(() => {
    AsyncStorage.multiGet([SCHEME_KEY, UPDOWN_KEY])
      .then(([[, scheme], [, upDown]]) => {
        if (SCHEMES.includes(scheme as SchemePreference)) setScheme(scheme as SchemePreference);
        if (UPDOWNS.includes(upDown as UpDownPreference)) setUpDown(upDown as UpDownPreference);
      })
      .catch(() => undefined);
  }, []);

  const setSchemePreference = useCallback((p: SchemePreference) => {
    setScheme(p);
    AsyncStorage.setItem(SCHEME_KEY, p).catch(() => undefined);
  }, []);
  const setUpDownPreference = useCallback((p: UpDownPreference) => {
    setUpDown(p);
    AsyncStorage.setItem(UPDOWN_KEY, p).catch(() => undefined);
  }, []);

  const value = useMemo<Theme>(() => {
    const scheme = resolveScheme(schemePreference, system);
    return {
      scheme,
      c: palettes[scheme],
      market: resolveMarketColors(scheme, upDownPreference),
      schemePreference,
      upDownPreference,
      setSchemePreference,
      setUpDownPreference,
    };
  }, [schemePreference, upDownPreference, system, setSchemePreference, setUpDownPreference]);

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): Theme {
  const t = useContext(ThemeContext);
  if (!t) throw new Error("useTheme은 ThemeProvider 안에서만 쓸 수 있습니다");
  return t;
}
