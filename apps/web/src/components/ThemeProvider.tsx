"use client";

import { ThemeProvider as NextThemesProvider } from "next-themes";
import { useEffect } from "react";
import { useThemeStore } from "@/stores/themeStore";

function StoreHydrator() {
  useEffect(() => {
    // rehydrate zustand store from localStorage after mount
    useThemeStore.persist.rehydrate();
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
