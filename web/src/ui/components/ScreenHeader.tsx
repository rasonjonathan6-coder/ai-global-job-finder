import type { ReactNode } from "react";
import { useTheme } from "../theme/ThemeProvider";
import { MoonIcon, SunIcon } from "./icons";

interface ScreenHeaderProps {
  title: string;
  subtitle?: string;
  /** Optional element rendered under the title (greeting, search field, chips...). */
  children?: ReactNode;
  /** Show the light/dark toggle. On by default for the top-level screens. */
  showThemeToggle?: boolean;
}

/**
 * A sticky, blurred top app bar. Keeping it translucent lets content scroll underneath
 * without the bar ever feeling like a heavy opaque block.
 */
export function ScreenHeader({ title, subtitle, children, showThemeToggle = true }: ScreenHeaderProps) {
  const { isDark, toggle } = useTheme();

  return (
    <header className="safe-top sticky top-0 z-30 border-b border-card-stroke bg-background/80 backdrop-blur-xl">
      <div className="mx-auto w-full max-w-screen-sm px-4 pb-3 pt-3">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h1 className="truncate text-[20px] font-extrabold tracking-tight text-ink-primary">
              {title}
            </h1>
            {subtitle && (
              <p className="mt-0.5 truncate text-[12.5px] text-ink-secondary">{subtitle}</p>
            )}
          </div>
          {showThemeToggle && (
            <button
              type="button"
              onClick={toggle}
              aria-label={isDark ? "Switch to light theme" : "Switch to dark theme"}
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-card-stroke bg-surface text-ink-secondary transition-colors hover:text-accent-ink active:scale-95"
            >
              {isDark ? <SunIcon width={20} height={20} /> : <MoonIcon width={20} height={20} />}
            </button>
          )}
        </div>
        {children && <div className="mt-3">{children}</div>}
      </div>
    </header>
  );
}
