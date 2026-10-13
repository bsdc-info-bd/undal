/**
 * Money is never a float anywhere in Undal: every amount is an integer number of
 * paisa (100 paisa = 1 taka). Only the presentation layer divides by 100.
 */
const TAKA_SIGN = "\u09F3"; // ৳

export function formatPaisa(amountPaisa: number): string {
  const taka = amountPaisa / 100;
  const hasFraction = amountPaisa % 100 !== 0;
  const formatted = taka.toLocaleString("en-US", {
    minimumFractionDigits: hasFraction ? 2 : 0,
    maximumFractionDigits: 2,
  });
  return `${TAKA_SIGN} ${formatted}`;
}

/** Delivery fee rule: ceil(km) × per-km rate, in paisa. */
export function deliveryFeePaisa(distanceKm: number, perKmPaisa: number): number {
  if (!Number.isFinite(distanceKm) || distanceKm <= 0) return 0;
  const billedKm = Math.ceil(distanceKm);
  return billedKm * perKmPaisa;
}
