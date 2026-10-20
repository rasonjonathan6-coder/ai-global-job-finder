import type { ReactNode } from "react";
import { cx } from "../lib/cx";

interface ChipProps {
  label: string;
  selected: boolean;
  onClick: () => void;
  icon?: ReactNode;
}

/**
 * A selectable filter chip, matching the Kotlin `FilterChip`. The selected state uses the
 * solid accent fill the rest of the app uses for a chosen option, so scope, frequency and
 * threshold rows all read the same way.
 */
export function Chip({ label, selected, onClick, icon }: ChipProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={selected}
      className={cx(
        "inline-flex shrink-0 items-center gap-1.5 rounded-full border px-3.5 py-1.5 text-[12.5px] font-semibold transition-colors active:scale-[0.97]",
        selected
          ? "border-transparent bg-accent-500 text-white"
          : "border-card-stroke bg-surface text-ink-secondary hover:bg-surface-variant",
      )}
    >
      {icon}
      {label}
    </button>
  );
}
