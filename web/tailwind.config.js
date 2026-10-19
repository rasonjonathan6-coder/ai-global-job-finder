/** @type {import('tailwindcss').Config} */
const withAlpha = (variable) => `rgb(var(${variable}) / <alpha-value>)`;

export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        // Semantic roles driven by the CSS custom properties in `src/index.css`, so a
        // single `.dark` class on <html> re-themes every surface without per-component
        // dark: variants. Values mirror the Android `ui/theme` palette.
        background: withAlpha("--background"),
        surface: withAlpha("--surface"),
        "surface-variant": withAlpha("--surface-variant"),
        outline: withAlpha("--outline"),
        // Hairline stroke. The alpha is baked into the variable (not the `<alpha-value>`
        // slot) because a card border is always a subtle 10% line, never a solid colour.
        "card-stroke": "rgb(var(--card-stroke))",
        "skeleton-base": withAlpha("--skeleton-base"),
        "skeleton-highlight": withAlpha("--skeleton-highlight"),
        // Readable accent for text/icons. This is deliberately *not* `accent-600`: the
        // 600 shade is tuned for light backgrounds and fails contrast on the dark surface,
        // so the token lightens automatically under `.dark`.
        "accent-ink": withAlpha("--accent-ink"),
        ink: {
          primary: withAlpha("--ink-primary"),
          secondary: withAlpha("--ink-secondary"),
          tertiary: withAlpha("--ink-tertiary"),
        },
        success: {
          DEFAULT: withAlpha("--success"),
          container: withAlpha("--success-container"),
        },
        warning: {
          DEFAULT: withAlpha("--warning"),
          container: withAlpha("--warning-container"),
        },
        danger: {
          DEFAULT: withAlpha("--danger"),
          container: withAlpha("--danger-container"),
        },
        info: {
          DEFAULT: withAlpha("--info"),
          container: withAlpha("--info-container"),
        },
        // Navy-led professional system, matching the Android app's identity.
        navy: {
          50: "#F3F6FB",
          100: "#E4EBF5",
          200: "#C6D4E8",
          300: "#9DB4D4",
          400: "#6B8CBB",
          500: "#46699F",
          600: "#335084",
          700: "#26406C",
          800: "#1B2F52",
          900: "#0B1F3A",
          950: "#071528",
        },
        // Royal blue — the single primary accent, matching the Android RoyalBlue.
        accent: {
          50: "#EEF3FE",
          100: "#DCE6FD",
          200: "#BCCFFA",
          300: "#93B0F5",
          400: "#6E9BFF",
          500: "#1E4FD8",
          600: "#1A44BE",
          700: "#143AA8",
          800: "#122F86",
          900: "#0F2569",
        },
        // Teal — the secondary accent used sparingly for AI / success cues.
        teal: {
          300: "#7FE7DA",
          400: "#35D6C0",
          500: "#00B39F",
          600: "#0B9484",
          700: "#0B7669",
        },
      },
      fontFamily: {
        sans: [
          "Plus Jakarta Sans",
          "Inter",
          "system-ui",
          "-apple-system",
          "Segoe UI",
          "Roboto",
          "Helvetica Neue",
          "Arial",
          "sans-serif",
        ],
      },
      borderRadius: { xl: "1rem", "2xl": "1.25rem", "3xl": "1.75rem" },
      boxShadow: {
        card: "0 1px 2px rgba(11,31,58,0.06), 0 8px 24px -12px rgba(11,31,58,0.18)",
        "card-hover": "0 2px 4px rgba(11,31,58,0.08), 0 18px 40px -16px rgba(11,31,58,0.28)",
        nav: "0 -1px 0 rgba(11,31,58,0.06), 0 -8px 24px -12px rgba(11,31,58,0.12)",
      },
      keyframes: {
        "fade-up": {
          "0%": { opacity: "0", transform: "translateY(8px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
        "fade-in": { "0%": { opacity: "0" }, "100%": { opacity: "1" } },
        shimmer: {
          "0%": { backgroundPosition: "-500px 0" },
          "100%": { backgroundPosition: "500px 0" },
        },
        "progress-indeterminate": {
          "0%": { transform: "translateX(-100%)" },
          "100%": { transform: "translateX(300%)" },
        },
        "sheet-up": {
          "0%": { opacity: "0", transform: "translateY(24px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
      },
      animation: {
        "fade-up": "fade-up 0.35s cubic-bezier(0.22,1,0.36,1) both",
        "fade-in": "fade-in 0.25s ease-out both",
        shimmer: "shimmer 1.4s linear infinite",
        "progress-indeterminate": "progress-indeterminate 1.3s ease-in-out infinite",
        "sheet-up": "sheet-up 0.32s cubic-bezier(0.22,1,0.36,1) both",
      },
    },
  },
  plugins: [],
};
