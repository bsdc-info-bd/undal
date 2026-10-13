/**
 * Money is never a float anywhere in Undal: every amount is an integer number of
 * paisa (100 paisa = 1 taka). Only the presentation layer divides by 100.
 */
const TAKA_SIGN = "\u09F3"; // ৳
export const PAISA_PER_TAKA = 100;

/**
 * 1234567 → "12,34,567". Bangladesh groups money the lakh way (last three
 * digits, then pairs), and this is written out by hand instead of asked of
 * `toLocaleString("en-IN")` so the Worker, the browser script and the app's
 * `NumberFormat(Locale("en","IN"))` cannot disagree about a runtime's ICU data.
 */
function groupIndian(digits: string): string {
  if (digits.length <= 3) return digits;
  const head = digits.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ",");
  return `${head},${digits.slice(-3)}`;
}

/**
 * The app's `Money.format`, character for character: `৳ 250`, `৳ 1,250`,
 * `৳ 12,500`, `৳ 1,00,000`, `৳ 250.5`, `৳ 250.05`. Trailing zeros in the paisa
 * are dropped, exactly as the Kotlin does, and `tools/check-web-rules.mjs`
 * runs the app's own vectors through both.
 */
export function formatPaisa(amountPaisa: number): string {
  const rounded = Math.round(amountPaisa);
  const negative = rounded < 0;
  const value = Math.abs(rounded);
  const whole = groupIndian(String(Math.floor(value / PAISA_PER_TAKA)));
  const paisa = value % PAISA_PER_TAKA;
  const fraction = paisa === 0 ? "" : `.${String(paisa).padStart(2, "0").replace(/0+$/, "")}`;
  return `${TAKA_SIGN} ${negative ? "-" : ""}${whole}${fraction}`;
}

/** Delivery fee rule: ceil(km) × per-km rate, in paisa. */
export function deliveryFeePaisa(distanceKm: number, perKmPaisa: number): number {
  if (!Number.isFinite(distanceKm) || distanceKm <= 0) return 0;
  const billedKm = Math.ceil(distanceKm);
  return billedKm * perKmPaisa;
}
