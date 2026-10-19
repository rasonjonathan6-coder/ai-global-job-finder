import type { ReactNode } from "react";
import { Button } from "./Button";
import { AlertIcon, SearchIcon } from "./icons";

interface EmptyStateProps {
  title: string;
  message: string;
  icon?: ReactNode;
  actionLabel?: string;
  onAction?: () => void;
  tone?: "neutral" | "error";
}

/** Friendly empty / error state: an icon, a plain-language reason and one clear action. */
export function EmptyState({
  title,
  message,
  icon,
  actionLabel,
  onAction,
  tone = "neutral",
}: EmptyStateProps) {
  const isError = tone === "error";
  return (
    <div className="card-surface flex flex-col items-center px-6 py-10 text-center animate-fade-in">
      <span
        className={[
          "flex h-14 w-14 items-center justify-center rounded-2xl",
          isError ? "bg-danger-container text-danger" : "bg-accent-500/[0.10] text-accent-ink",
        ].join(" ")}
        aria-hidden="true"
      >
        {icon ?? (isError ? <AlertIcon width={26} height={26} /> : <SearchIcon width={26} height={26} />)}
      </span>
      <h3 className="mt-4 text-[16px] font-bold text-ink-primary">{title}</h3>
      <p className="mt-1.5 max-w-[34ch] text-[13px] leading-relaxed text-ink-secondary">{message}</p>
      {actionLabel && onAction && (
        <Button variant={isError ? "secondary" : "primary"} className="mt-5" onClick={onAction}>
          {actionLabel}
        </Button>
      )}
    </div>
  );
}
