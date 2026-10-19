import type { ReactNode } from "react";
import { cx } from "../lib/cx";

type Tone = "neutral" | "success" | "warning" | "danger" | "info" | "accent";

export type BadgeTone = Tone;

interface BadgeProps {
  children: ReactNode;
  tone?: Tone;
  icon?: ReactNode;
  className?: string;
}

const TONES: Record<Tone, string> = {
  neutral: "bg-surface-variant text-ink-secondary",
  success: "bg-success-container text-success",
  warning: "bg-warning-container text-warning",
  danger: "bg-danger-container text-danger",
  info: "bg-info-container text-info",
  accent: "bg-accent-500/[0.12] text-accent-ink",
};

/** Small pill label for eligibility, visa status, match tier and similar meta. */
export function Badge({ children, tone = "neutral", icon, className }: BadgeProps) {
  return (
    <span
      className={cx(
        "inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-semibold leading-none",
        TONES[tone],
        className,
      )}
    >
      {icon}
      {children}
    </span>
  );
}
