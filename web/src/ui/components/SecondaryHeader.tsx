import type { ReactNode } from "react";
import { ArrowLeftIcon } from "./icons";

interface SecondaryHeaderProps {
  title: string;
  subtitle?: string;
  onBack: () => void;
  /** An optional trailing action button (refresh, add...). */
  action?: ReactNode;
}

/**
 * The header for a screen pushed on top of a tab. Mirrors the Android secondary screens:
 * a back affordance, the title and subtitle, and one optional trailing action, all in the
 * same sticky, translucent bar the top-level `ScreenHeader` uses so the chrome is consistent.
 */
export function SecondaryHeader({ title, subtitle, onBack, action }: SecondaryHeaderProps) {
  return (
    <header className="safe-top sticky top-0 z-30 border-b border-card-stroke bg-background/80 backdrop-blur-xl">
      <div className="mx-auto flex w-full max-w-screen-sm items-center gap-3 px-4 pb-3 pt-3">
        <button
          type="button"
          onClick={onBack}
          aria-label="Back"
          className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-card-stroke bg-surface text-ink-secondary transition-colors hover:text-accent-ink active:scale-95"
        >
          <ArrowLeftIcon width={20} height={20} />
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-[18px] font-extrabold tracking-tight text-ink-primary">
            {title}
          </h1>
          {subtitle && <p className="truncate text-[12.5px] text-ink-secondary">{subtitle}</p>}
        </div>
        {action}
      </div>
    </header>
  );
}

/** A round icon button sized to match the back affordance, for a secondary header action. */
export function HeaderIconButton({
  label,
  onClick,
  children,
}: {
  label: string;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-card-stroke bg-surface text-ink-secondary transition-colors hover:text-accent-ink active:scale-95"
    >
      {children}
    </button>
  );
}
