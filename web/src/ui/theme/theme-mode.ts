/**
 * Light / dark / system theme, mirroring the Android `ThemeMode` preference. The choice is
 * persisted in `localStorage` and reflected as a `.dark` class on <html>, which is the hook
 * every token in `index.css` and `tailwind.config.js` keys off.
 */
export type ThemeMode = "system" | "light" | "dark";

const STORAGE_KEY = "aigjf.theme";

export function readStoredThemeMode(): ThemeMode {
  if (typeof localStorage === "undefined") return "system";
  const raw = localStorage.getItem(STORAGE_KEY);
  return raw === "light" || raw === "dark" || raw === "system" ? raw : "system";
}

export function storeThemeMode(mode: ThemeMode): void {
  try {
    localStorage.setItem(STORAGE_KEY, mode);
  } catch {
    // Private-mode or quota failures are non-fatal: the theme still applies for this session.
  }
}

export function prefersDark(): boolean {
  return (
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-color-scheme: dark)").matches
  );
}

/** Whether the resolved palette is dark for a given preference. */
export function resolveIsDark(mode: ThemeMode): boolean {
  return mode === "dark" || (mode === "system" && prefersDark());
}

/** Applies the resolved theme to the document root. */
export function applyThemeMode(mode: ThemeMode): void {
  if (typeof document === "undefined") return;
  document.documentElement.classList.toggle("dark", resolveIsDark(mode));
}
