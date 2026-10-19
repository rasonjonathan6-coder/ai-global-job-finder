import type { ButtonHTMLAttributes, ReactNode } from "react";
import { cx } from "../lib/cx";

type Variant = "primary" | "secondary" | "ghost" | "danger";
type Size = "sm" | "md" | "lg";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  leadingIcon?: ReactNode;
  trailingIcon?: ReactNode;
  fullWidth?: boolean;
  loading?: boolean;
}

const VARIANTS: Record<Variant, string> = {
  primary:
    "bg-gradient-to-b from-accent-500 to-accent-600 text-white shadow-card hover:shadow-card-hover active:from-accent-600 active:to-accent-700",
  secondary:
    "bg-surface text-ink-primary border border-card-stroke shadow-card hover:bg-surface-variant active:bg-surface-variant",
  ghost: "bg-transparent text-accent-ink hover:bg-accent-500/[0.08] active:bg-accent-500/[0.14]",
  danger: "bg-danger text-white shadow-card hover:brightness-105 active:brightness-95",
};

const SIZES: Record<Size, string> = {
  sm: "h-9 px-3.5 text-[13px] gap-1.5 rounded-xl",
  md: "h-11 px-5 text-[14.5px] gap-2 rounded-2xl",
  lg: "h-12 px-5 text-[15px] gap-2 rounded-2xl",
};

/**
 * The app's single button primitive. Every interactive control routes through it so press
 * feedback (scale + colour), focus rings and disabled styling stay consistent. The `md`
 * default is 44px tall to meet touch-target guidance.
 */
export function Button({
  variant = "primary",
  size = "md",
  leadingIcon,
  trailingIcon,
  fullWidth,
  loading,
  disabled,
  className,
  children,
  ...rest
}: ButtonProps) {
  return (
    <button
      type="button"
      disabled={disabled || loading}
      className={cx(
        "inline-flex select-none items-center justify-center font-semibold transition-all duration-200",
        "active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-55 disabled:active:scale-100",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent-500 focus-visible:ring-offset-2 focus-visible:ring-offset-background",
        VARIANTS[variant],
        SIZES[size],
        fullWidth && "w-full",
        className,
      )}
      {...rest}
    >
      {loading ? (
        <span
          className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"
          aria-hidden="true"
        />
      ) : (
        leadingIcon
      )}
      <span className="truncate">{children}</span>
      {!loading && trailingIcon}
    </button>
  );
}
