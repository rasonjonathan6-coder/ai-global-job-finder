import { Badge } from "../components/Badge";
import { ScreenHeader } from "../components/ScreenHeader";
import { useTheme } from "../theme/ThemeProvider";
import type { ThemeMode } from "../theme/theme-mode";
import { cx } from "../lib/cx";
import {
  CheckIcon,
  ChevronRightIcon,
  FileTextIcon,
  GlobeIcon,
  MailIcon,
  MoonIcon,
  PhoneIcon,
  PinIcon,
  ShieldIcon,
  SunIcon,
} from "../components/icons";

const THEME_OPTIONS: { mode: ThemeMode; label: string; icon: React.ReactNode }[] = [
  { mode: "light", label: "Light", icon: <SunIcon width={16} height={16} /> },
  { mode: "dark", label: "Dark", icon: <MoonIcon width={16} height={16} /> },
  { mode: "system", label: "System", icon: <GlobeIcon width={16} height={16} /> },
];

/**
 * Profile and preferences. Stage 1 shows the CV summary shell, the theme control (which
 * exercises the full light/dark system) and the safety settings entry point.
 */
export function ProfileScreen() {
  const { mode, setMode } = useTheme();

  const rows = [
    { icon: <MailIcon width={17} height={17} />, label: "Email", value: "jonathan@example.mg" },
    { icon: <PhoneIcon width={17} height={17} />, label: "Phone", value: "+261 34 00 000 00" },
    { icon: <PinIcon width={17} height={17} />, label: "Location", value: "Antananarivo, Madagascar" },
  ];

  return (
    <>
      <ScreenHeader title="Profile" subtitle="Your CV and preferences" />
      <div className="app-shell space-y-5">
        <section className="card-surface flex items-center gap-4 p-4">
          <span className="flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-accent-500 to-teal-600 text-[18px] font-extrabold text-white">
            JA
          </span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-[16px] font-bold text-ink-primary">Jonathan Andria</p>
            <p className="truncate text-[12.5px] text-ink-secondary">Maintenance Technician · 6 years</p>
            <div className="mt-2 flex flex-wrap gap-1.5">
              <Badge tone="success" icon={<CheckIcon width={12} height={12} />}>
                CV analysed
              </Badge>
              <Badge tone="accent">Open to relocation</Badge>
            </div>
          </div>
        </section>

        <section aria-label="Contact details" className="card-surface divide-y divide-card-stroke p-0">
          {rows.map((row) => (
            <div key={row.label} className="flex items-center gap-3 px-4 py-3.5">
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-surface-variant text-ink-secondary">
                {row.icon}
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-[11.5px] font-medium text-ink-tertiary">{row.label}</p>
                <p className="truncate text-[13.5px] font-semibold text-ink-primary">{row.value}</p>
              </div>
            </div>
          ))}
        </section>

        <section aria-label="Appearance" className="card-surface p-4">
          <p className="text-[14px] font-bold text-ink-primary">Appearance</p>
          <p className="mt-0.5 text-[12.5px] text-ink-secondary">
            The dark theme is designed, not inverted — cards, text and icons stay polished.
          </p>
          <div className="mt-3 grid grid-cols-3 gap-2">
            {THEME_OPTIONS.map((option) => {
              const active = option.mode === mode;
              return (
                <button
                  key={option.mode}
                  type="button"
                  onClick={() => setMode(option.mode)}
                  aria-pressed={active}
                  className={cx(
                    "flex flex-col items-center gap-1.5 rounded-2xl border py-3 text-[12.5px] font-semibold transition-all active:scale-[0.97]",
                    active
                      ? "border-accent-500 bg-accent-500/[0.10] text-accent-ink"
                      : "border-card-stroke bg-surface text-ink-secondary hover:bg-surface-variant",
                  )}
                >
                  {option.icon}
                  {option.label}
                </button>
              );
            })}
          </div>
        </section>

        <section aria-label="Settings" className="card-surface divide-y divide-card-stroke p-0">
          {[
            { icon: <FileTextIcon width={17} height={17} />, label: "Import or update CV" },
            { icon: <GlobeIcon width={17} height={17} />, label: "Preferred countries" },
            { icon: <ShieldIcon width={17} height={17} />, label: "Scam warning settings" },
          ].map((item) => (
            <button
              key={item.label}
              type="button"
              className="flex w-full items-center gap-3 px-4 py-3.5 text-left transition-colors hover:bg-surface-variant active:bg-surface-variant"
            >
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-surface-variant text-ink-secondary">
                {item.icon}
              </span>
              <span className="flex-1 text-[13.5px] font-semibold text-ink-primary">{item.label}</span>
              <ChevronRightIcon width={17} height={17} className="text-ink-tertiary" />
            </button>
          ))}
        </section>

        <p className="pb-2 text-center text-[11.5px] text-ink-tertiary">
          AI Global Job Finder · Web edition (Stage 1)
        </p>
      </div>
    </>
  );
}
