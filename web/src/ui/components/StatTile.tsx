import type { ReactNode } from "react";
import { cx } from "../lib/cx";

interface StatTileProps {
  label: string;
  value: number | string;
  icon: ReactNode;
  tone?: "accent" | "success" | "warning" | "info";
  index?: number;
  onClick?: () => void;
}

const TONES: Record<NonNullable<StatTileProps["tone"]>, string> = {
  accent: "bg-accent-500/[0.10] text-accent-ink",
  success: "bg-success-container text-success",
  warning: "bg-warning-container text-warning",
  info: "bg-info-container text-info",
};

/** A quick-stat tile for the dashboard: icon, big number, quiet label. */
export function StatTile({ label, value, icon, tone = "accent", index = 0, onClick }: StatTileProps) {
  const interactive = Boolean(onClick);
  const Tag = interactive ? "button" : "div";
  return (
    <Tag
      {...(interactive ? { type: "button" as const, onClick } : {})}
      className={cx(
        "card-surface animate-fade-up flex flex-col gap-2 p-3.5 text-left",
        interactive && "transition-all hover:-translate-y-0.5 hover:shadow-card-hover active:scale-[0.98]",
      )}
      style={{ animationDelay: `${index * 45}ms` }}
    >
      <span className={cx("flex h-9 w-9 items-center justify-center rounded-xl", TONES[tone])} aria-hidden="true">
        {icon}
      </span>
      <span className="text-[22px] font-extrabold leading-none tabular-nums text-ink-primary">
        {value}
      </span>
      <span className="text-[11.5px] font-medium leading-tight text-ink-secondary">{label}</span>
    </Tag>
  );
}
