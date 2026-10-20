import { cx } from "../lib/cx";

interface SwitchProps {
  checked: boolean;
  onChange: (next: boolean) => void;
  label: string;
  className?: string;
}

/** A compact iOS-style switch matching the Android `Switch`, with a proper `switch` role. */
export function Switch({ checked, onChange, label, className }: SwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={() => onChange(!checked)}
      className={cx(
        "relative h-6 w-11 shrink-0 rounded-full transition-colors",
        checked ? "bg-accent-500" : "bg-surface-variant",
        className,
      )}
    >
      <span
        className={cx(
          "absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-all",
          checked ? "left-[22px]" : "left-0.5",
        )}
      />
    </button>
  );
}
