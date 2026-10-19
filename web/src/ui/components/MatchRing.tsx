import { cx } from "../lib/cx";

interface MatchRingProps {
  /** 0..100 */
  score: number;
  size?: number;
  label?: string;
  className?: string;
}

function toneFor(score: number): { stroke: string; text: string } {
  if (score >= 85) return { stroke: "text-success", text: "text-success" };
  if (score >= 70) return { stroke: "text-accent-500", text: "text-accent-ink" };
  if (score >= 50) return { stroke: "text-warning", text: "text-warning" };
  return { stroke: "text-danger", text: "text-danger" };
}

/**
 * Circular match indicator drawn with an SVG stroke. It is the visual anchor of every job
 * card: colour plus the number together, so the score is never colour-only.
 */
export function MatchRing({ score, size = 52, label = "Match", className }: MatchRingProps) {
  const value = Math.max(0, Math.min(100, Math.round(score)));
  const stroke = 4;
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const dash = (value / 100) * circumference;
  const tone = toneFor(value);

  return (
    <div
      className={cx("relative inline-flex shrink-0 items-center justify-center", className)}
      style={{ width: size, height: size }}
      role="img"
      aria-label={`${value}% ${label.toLowerCase()}`}
    >
      <svg width={size} height={size} className="-rotate-90" aria-hidden="true">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth={stroke}
          className="text-surface-variant"
          stroke="currentColor"
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth={stroke}
          strokeLinecap="round"
          className={tone.stroke}
          stroke="currentColor"
          strokeDasharray={`${dash} ${circumference}`}
          style={{ transition: "stroke-dasharray 700ms cubic-bezier(0.22,1,0.36,1)" }}
        />
      </svg>
      <span
        className={cx("absolute text-[13px] font-extrabold tabular-nums", tone.text)}
        style={{ fontSize: size <= 44 ? 11 : 13 }}
      >
        {value}
        <span className="text-[8px]">%</span>
      </span>
    </div>
  );
}
