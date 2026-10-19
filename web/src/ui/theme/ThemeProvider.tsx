import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import {
  applyThemeMode,
  readStoredThemeMode,
  resolveIsDark,
  storeThemeMode,
  type ThemeMode,
} from "./theme-mode";

interface ThemeContextValue {
  mode: ThemeMode;
  isDark: boolean;
  setMode: (mode: ThemeMode) => void;
  toggle: () => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

/**
 * Owns the theme preference for the whole app. The resolved `.dark` class is applied to
 * <html> and kept in sync when the OS preference changes while the user is on "system".
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<ThemeMode>(() => readStoredThemeMode());
  const [isDark, setIsDark] = useState<boolean>(() => resolveIsDark(readStoredThemeMode()));

  useEffect(() => {
    applyThemeMode(mode);
    setIsDark(resolveIsDark(mode));
  }, [mode]);

  useEffect(() => {
    if (mode !== "system" || typeof window === "undefined" || !window.matchMedia) return;
    const query = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => {
      applyThemeMode("system");
      setIsDark(resolveIsDark("system"));
    };
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, [mode]);

  const setMode = useCallback((next: ThemeMode) => {
    storeThemeMode(next);
    setModeState(next);
  }, []);

  const toggle = useCallback(() => {
    setMode(resolveIsDark(mode) ? "light" : "dark");
  }, [mode, setMode]);

  const value = useMemo<ThemeContextValue>(
    () => ({ mode, isDark, setMode, toggle }),
    [mode, isDark, setMode, toggle],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext);
  if (!context) throw new Error("useTheme must be used inside a ThemeProvider");
  return context;
}
