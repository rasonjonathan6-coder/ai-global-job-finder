import { DESTINATIONS } from "../navigation/destinations";

interface BottomNavProps {
  current: string;
  onNavigate: (route: string) => void;
}

/**
 * Modern bottom navigation bar: a translucent, blurred surface pinned above the system
 * gesture area. The selected tab is shown with an accent-tinted pill behind the icon and a
 * colour change, so the active destination is obvious without relying on colour alone.
 */
export function BottomNav({ current, onNavigate }: BottomNavProps) {
  return (
    <nav
      aria-label="Primary"
      className="fixed inset-x-0 bottom-0 z-40 border-t border-card-stroke bg-surface/85 backdrop-blur-xl"
      style={{ paddingBottom: "env(safe-area-inset-bottom)" }}
    >
      <ul className="mx-auto flex w-full max-w-screen-sm items-stretch justify-between px-2 py-1.5">
        {DESTINATIONS.map((destination) => {
          const selected = destination.route === current;
          const { Icon } = destination;
          return (
            <li key={destination.route} className="flex-1">
              <button
                type="button"
                onClick={() => onNavigate(destination.route)}
                aria-label={destination.contentDescription}
                aria-current={selected ? "page" : undefined}
                data-selected={selected}
                className="group relative flex w-full flex-col items-center gap-0.5 rounded-xl px-1 py-2 transition-colors active:bg-surface-variant/70"
              >
                <span
                  className={[
                    "absolute top-1 h-8 w-14 rounded-full bg-accent-500/[0.12] transition-all duration-300 ease-out",
                    selected ? "scale-100 opacity-100" : "scale-75 opacity-0",
                  ].join(" ")}
                  aria-hidden="true"
                />
                <span
                  className={[
                    "relative transition-transform duration-300 ease-out",
                    selected
                      ? "-translate-y-0.5 text-accent-ink"
                      : "text-ink-tertiary group-active:scale-90",
                  ].join(" ")}
                >
                  <Icon width={22} height={22} strokeWidth={selected ? 2.2 : 1.8} />
                </span>
                <span
                  className={[
                    "relative text-[10.5px] font-semibold leading-none tracking-wide transition-colors",
                    selected ? "text-accent-ink" : "text-ink-tertiary",
                  ].join(" ")}
                >
                  {destination.label}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
