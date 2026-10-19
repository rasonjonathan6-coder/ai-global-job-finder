import { ApplicationReadiness } from "../../core/models";
import { cx } from "../lib/cx";

/** The band colour for the readiness ring, so the number and the tint always agree. */
const READINESS_TONE: Record<ApplicationReadiness, string> = {
  [ApplicationReadiness.PRIORITY]: "text-success",
  [ApplicationReadiness.READY]: "text-info",
  [ApplicationReadiness.PREPARE_FIRST]: "text-warning",
  [ApplicationReadiness.NEEDS_REVIEW]: "text-ink-secondary",
  [ApplicationReadiness.BLOCKED]: "text-danger",
};

interface ReadinessRingProps {
  score: number;
  readiness: ApplicationReadiness;
  size?: number;
}

/**
 * The shortlist's own gauge: how ready a posting is to apply for, tinted by band so the
 * number and the colour always agree. Deliberately distinct from the match ring, because
 * "can I apply?" and "does my CV fit?" are different questions.
 */
export function ReadinessRing({ score, readiness, size = 56 }: ReadinessRingProps) {
  const value = Math.max(0, Math.min(100, Math.round(score)));
  const stroke = 5;
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const dash = (value / 100) * circumference;
  const tone = READINESS_TONE[readiness];

  return (
    <div
      className="relative inline-flex shrink-0 items-center justify-center"
      style={{ width: size, height: size }}
      role="img"
      aria-label={`${value}% ready to apply`}
    >
      <svg width={size} height={size} className="-rotate-90" aria-hidden="true">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth={stroke}
          stroke="currentColor"
          className="text-surface-variant"
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth={stroke}
          strokeLinecap="round"
          stroke="currentColor"
          className={tone}
          strokeDasharray={`${dash} ${circumference}`}
          style={{ transition: "stroke-dasharray 700ms cubic-bezier(0.22,1,0.36,1)" }}
        />
      </svg>
      <span className="absolute flex flex-col items-center leading-none">
        <span className={cx("text-[15px] font-extrabold tabular-nums", tone)}>{value}</span>
        <span className="mt-0.5 text-[9px] font-semibold uppercase tracking-wide text-ink-tertiary">
          ready
        </span>
      </span>
    </div>
  );
}
