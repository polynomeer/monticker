"use client";

import { ThemeProvider as NextThemesProvider } from "next-themes";
import { useEffect } from "react";
import { useThemeStore } from "@/stores/themeStore";
import { useA11yStore, applyTextSize } from "@/stores/a11yStore";

function StoreHydrator() {
  useEffect(() => {
    // rehydrate zustand stores from localStorage after mount
    useThemeStore.persist.rehydrate();
    Promise.resolve(useA11yStore.persist.rehydrate()).then(() =>
      applyTextSize(useA11yStore.getState().textSize)
    );
    // 이후 변경도 html[data-text-size]에 반영
    const unsub = useA11yStore.subscribe((st) => applyTextSize(st.textSize));
    return () => unsub();
  }, []);
  return null;
}

export default function ThemeProvider({ children }: { children: React.ReactNode }) {
  return (
    <NextThemesProvider
      attribute="class"
      defaultTheme="system"
      enableSystem
      disableTransitionOnChange
    >
      <StoreHydrator />
      {children}
    </NextThemesProvider>
  );
}
