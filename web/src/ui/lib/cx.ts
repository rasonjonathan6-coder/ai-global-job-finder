/** Joins class names, dropping falsy values. Keeps conditional styling readable. */
export function cx(...values: Array<string | false | null | undefined>): string {
  return values.filter(Boolean).join(" ");
}
