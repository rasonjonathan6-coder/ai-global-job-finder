import type { HTMLAttributes, ReactNode } from "react";
import { cx } from "../lib/cx";

interface CardProps extends HTMLAttributes<HTMLDivElement> {
  children: ReactNode;
  /** Adds a hover lift. Use on tappable cards only. */
  interactive?: boolean;
}

/** The shared premium surface: soft radius, hairline stroke, layered shadow. */
export function Card({ children, interactive, className, ...rest }: CardProps) {
  return (
    <div
      className={cx(
        "card-surface p-4",
        interactive &&
          "cursor-pointer transition-all duration-200 hover:-translate-y-0.5 hover:shadow-card-hover active:translate-y-0 active:scale-[0.995]",
        className,
      )}
      {...rest}
    >
      {children}
    </div>
  );
}
