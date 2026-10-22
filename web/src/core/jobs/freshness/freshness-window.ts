/**
 * How far back the Jobs screen looks when it narrows by publication date. Mirrors the Kotlin
 * `FreshnessWindow`. The three windows the product offers are the last week, fortnight and
 * month; `THIRTY_DAYS` is the default.
 *
 * The window is a *view*, not a claim: a posting kept because it falls inside it is never
 * asserted to be open — only that its stated publication date is recent enough to be worth
 * showing. A posting with no date, or a date the source clock placed in the future, has no
 * place on this axis and is not counted as recent by any window (see `JobFreshnessEngine`).
 */
export enum FreshnessWindow {
  SEVEN_DAYS = "SEVEN_DAYS",
  FOURTEEN_DAYS = "FOURTEEN_DAYS",
  THIRTY_DAYS = "THIRTY_DAYS",
}

export const DEFAULT_FRESHNESS_WINDOW = FreshnessWindow.THIRTY_DAYS;

const DAY_MS = 86_400_000;

const WINDOW_DAYS: Record<FreshnessWindow, number> = {
  [FreshnessWindow.SEVEN_DAYS]: 7,
  [FreshnessWindow.FOURTEEN_DAYS]: 14,
  [FreshnessWindow.THIRTY_DAYS]: 30,
};

/** The number of days a window spans, for labels and the empty-state message. */
export function freshnessWindowDays(window: FreshnessWindow): number {
  return WINDOW_DAYS[window];
}

/**
 * True when `postedAtEpochMillis` states a real publication date inside `window`.
 *
 * The same rules the `JobFreshnessEngine` applies hold here: a missing date, a zero date or a
 * date the source placed in the future is *not* recent, so it never passes. Nothing is invented
 * to fill the gap — an undated posting is left for the caller to route to a clearly-signalled
 * separate group rather than counted as fresh.
 */
export function withinFreshnessWindow(
  postedAtEpochMillis: number | null | undefined,
  now: number,
  window: FreshnessWindow,
): boolean {
  if (postedAtEpochMillis == null) return false;
  if (postedAtEpochMillis < 1 || postedAtEpochMillis > now) return false;
  return Math.trunc((now - postedAtEpochMillis) / DAY_MS) <= WINDOW_DAYS[window];
}
