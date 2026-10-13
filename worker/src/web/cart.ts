/**
 * Money, distance and cart arithmetic for the web store.
 *
 * These are the same rules the Android app enforces in `core/util/Money.kt`,
 * `core/util/Distance.kt` and `core/util/Validators.kt`. They are re-implemented
 * here (not copied) because the web checkout has to price an order on the
 * server: the browser sends dish ids and quantities, never prices, so a
 * tampered client cannot invent a discount. `tools/check-web-rules.mjs` runs the
 * app's own test vectors through both implementations on every push, so the two
 * cannot drift apart quietly.
 */

export const PAISA_PER_TAKA = 100;

/**
 * Fee rule: whole kilometres, rounded up. `Money.deliveryFeePaisa` in the app is
 * the same sentence, and `tools/check-web-rules.mjs` proves it with the app's
 * own vectors on every push.
 */
export function deliveryFeePaisa(distanceKm: number, perKmPaisa: number): number {
  if (!Number.isFinite(distanceKm) || distanceKm <= 0) return 0;
  if (!Number.isFinite(perKmPaisa) || perKmPaisa <= 0) return 0;
  return Math.ceil(distanceKm) * perKmPaisa;
}

/** Percent discount, floored to whole paisa, with the percent clamped to 0–100. */
export function percentDiscountPaisa(amountPaisa: number, percent: number): number {
  if (!Number.isFinite(amountPaisa) || amountPaisa <= 0) return 0;
  if (!Number.isFinite(percent)) return 0;
  const safePercent = Math.min(100, Math.max(0, Math.trunc(percent)));
  return Math.floor((amountPaisa * safePercent) / 100);
}

export function lineTotalPaisa(unitAmountPaisa: number, quantity: number): number {
  if (!Number.isFinite(unitAmountPaisa) || unitAmountPaisa <= 0) return 0;
  if (!Number.isFinite(quantity) || quantity <= 0) return 0;
  return Math.trunc(unitAmountPaisa) * Math.trunc(quantity);
}

export function grandTotalPaisa(subtotal: number, delivery: number, discount: number): number {
  return Math.max(0, subtotal + delivery - discount);
}

export interface CouponRule {
  type: "percent" | "fixed";
  value: number;
}

/**
 * The discount a coupon actually applies to a basket, never more than the
 * basket. The app's `Money.applicableDiscount` is the same `when` on the coupon
 * type, and the security rules re-check `discount <= subtotal` on write.
 */
export function applicableDiscountPaisa(subtotalPaisa: number, coupon: CouponRule): number {
  if (!Number.isFinite(subtotalPaisa) || subtotalPaisa <= 0) return 0;
  // Exactly two kinds of coupon, like the app's `when (type)`: anything else is
  // worth nothing rather than silently treated as a fixed amount.
  if (coupon.type === "percent") {
    return Math.max(0, Math.min(percentDiscountPaisa(subtotalPaisa, coupon.value), subtotalPaisa));
  }
  if (coupon.type === "fixed") {
    return Math.max(0, Math.min(Math.trunc(coupon.value), subtotalPaisa));
  }
  return 0;
}

const EARTH_RADIUS_KM = 6371.0088;

/** Great-circle distance in kilometres — identical formula to the app's. */
export function haversineKm(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const toRadians = (degrees: number): number => (degrees * Math.PI) / 180;
  const dLat = toRadians(lat2 - lat1);
  const dLng = toRadians(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) * Math.sin(dLng / 2) ** 2;
  const c = 2 * Math.asin(Math.min(1, Math.sqrt(a)));
  return EARTH_RADIUS_KM * c;
}

/**
 * Zero means "not published yet", which is not a refusal. Like the app's
 * `Distance.distanceFromStore`, a missing or zero pin on either axis returns
 * null so the caller can say so in words instead of inventing a fee.
 */
export function distanceFromStorePinned(
  storeLat: number,
  storeLng: number,
  lat: number,
  lng: number,
): number | null {
  if (!Number.isFinite(storeLat) || !Number.isFinite(storeLng)) return null;
  if (storeLat === 0 || storeLng === 0) return null;
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  return haversineKm(storeLat, storeLng, lat, lng);
}

/** The radius gate: a radius of zero means delivery is not published yet. */
export function isWithinRadius(distanceKm: number, radiusKm: number): boolean {
  if (!Number.isFinite(radiusKm) || radiusKm <= 0) return false;
  if (!Number.isFinite(distanceKm)) return false;
  return distanceKm <= radiusKm;
}

export function formatKm(distanceKm: number): string {
  const rounded = Math.round(distanceKm * 10) / 10;
  return Number.isInteger(rounded) ? `${rounded} km` : `${rounded.toFixed(1)} km`;
}

const BD_MOBILE = /^01[3-9]\d{8}$/;

export const MIN_TEXT_LENGTH = 3;
export const MAX_NAME_LENGTH = 80;

/** The app's `Validators.requiredText`: trimmed, at least `minimum` characters. */
export function usableText(raw: string, minimum = MIN_TEXT_LENGTH): boolean {
  return raw.trim().length >= minimum;
}

/**
 * The app's `Validators.name` rule, character for character: 3–80 characters
 * after trimming, and at least one letter, space, dot or dash — so `12345` is
 * refused and `Rahim 2` is not.
 */
export function usableName(raw: string): boolean {
  const value = raw.trim();
  if (value.length < MIN_TEXT_LENGTH || value.length > MAX_NAME_LENGTH) return false;
  return [...value].some((character) => /[\p{L} .-]/u.test(character));
}

/** Accepts 01XXXXXXXXX with spaces, dashes or a +880 prefix. */
export function normalizeBdMobile(raw: string): string | null {
  const digits = raw.replace(/[^\d]/g, "");
  const local = digits.startsWith("880") ? `0${digits.slice(3)}` : digits;
  return BD_MOBILE.test(local) ? local : null;
}

export const COUPON_PATTERN = /^[A-Z0-9_-]{3,24}$/;

/**
 * The app's `Validators.couponCode`: trim, upper-case, then the one pattern.
 * A space is not silently turned into a dash — a code the app refuses must not
 * become a different, valid-looking code on the web.
 */
export function normalizeCoupon(raw: string): string | null {
  const code = raw.trim().toUpperCase();
  return COUPON_PATTERN.test(code) ? code : null;
}

// ------------------------------------------------------------------ the cart

export interface CartLine {
  /** menuItems/{id} or combos/{id}. */
  id: string;
  qty: number;
  /** "dish" or "combo" — decided by the server when it prices the cart. */
  kind: "dish" | "combo";
}

export const MAX_CART_LINES = 40;
export const MAX_LINE_QTY = 50;

export interface CartIssue {
  code: string;
  message: string;
}

/** Structural validation before any Firestore read: cheap, and it stops abuse. */
export function validateCartLines(lines: unknown): { lines: CartLine[]; issues: CartIssue[] } {
  const issues: CartIssue[] = [];
  if (!Array.isArray(lines) || lines.length === 0) {
    return { lines: [], issues: [{ code: "cart-empty", message: "Your cart is empty." }] };
  }
  if (lines.length > MAX_CART_LINES) {
    return {
      lines: [],
      issues: [
        {
          code: "cart-too-long",
          message: `An order can hold at most ${MAX_CART_LINES} different dishes.`,
        },
      ],
    };
  }
  const seen = new Map<string, CartLine>();
  for (const entry of lines) {
    const candidate = entry as Partial<CartLine> | null;
    const id = typeof candidate?.id === "string" ? candidate.id.trim() : "";
    const qty = Number(candidate?.qty);
    const kind = candidate?.kind === "combo" ? "combo" : "dish";
    if (!id || id.length > 128 || !Number.isInteger(qty) || qty < 1 || qty > MAX_LINE_QTY) {
      issues.push({ code: "cart-line-invalid", message: "One of the lines in your cart is not valid." });
      continue;
    }
    const existing = seen.get(id);
    if (existing) {
      const merged = Math.min(MAX_LINE_QTY, existing.qty + qty);
      seen.set(id, { ...existing, qty: merged });
    } else {
      seen.set(id, { id, qty, kind });
    }
  }
  return { lines: [...seen.values()], issues };
}

export interface PricedLine {
  id: string;
  name: string;
  qty: number;
  unitAmountPaisa: number;
  lineTotalAmountPaisa: number;
  kind: "dish" | "combo";
}

export interface PricedCart {
  lines: PricedLine[];
  subtotalPaisa: number;
  deliveryPaisa: number;
  discountPaisa: number;
  totalPaisa: number;
}

/**
 * Prices a cart from live catalogue data. `lookup` returns the current price of
 * one id, or null when the dish has gone or is switched off — the same live
 * re-read the app performs inside its transaction.
 */
export function priceCart(
  lines: CartLine[],
  lookup: (line: CartLine) => { name: string; unitAmountPaisa: number; available: boolean } | null,
  options: { distanceKm: number; perKmPaisa: number; coupon?: CouponRule | null },
): { cart: PricedCart | null; issue: CartIssue | null } {
  const priced: PricedLine[] = [];
  for (const line of lines) {
    const live = lookup(line);
    if (!live) {
      return {
        cart: null,
        issue: {
          code: "item-missing",
          message: "One of the dishes in your cart is no longer on the menu. Please review the cart.",
        },
      };
    }
    if (!live.available) {
      return {
        cart: null,
        issue: { code: "item-unavailable", message: `${live.name} is finished for today.` },
      };
    }
    if (live.unitAmountPaisa <= 0) {
      return { cart: null, issue: { code: "item-unpriced", message: `${live.name} has no price yet.` } };
    }
    priced.push({
      id: line.id,
      name: live.name,
      qty: line.qty,
      unitAmountPaisa: live.unitAmountPaisa,
      lineTotalAmountPaisa: lineTotalPaisa(live.unitAmountPaisa, line.qty),
      kind: line.kind,
    });
  }

  const subtotalPaisa = priced.reduce((sum, line) => sum + line.lineTotalAmountPaisa, 0);
  const deliveryPaisa = deliveryFeePaisa(options.distanceKm, options.perKmPaisa);
  const discountPaisa = options.coupon ? applicableDiscountPaisa(subtotalPaisa, options.coupon) : 0;
  const totalPaisa = grandTotalPaisa(subtotalPaisa, deliveryPaisa, discountPaisa);

  if (totalPaisa <= 0) {
    return { cart: null, issue: { code: "total-zero", message: "This order would total nothing." } };
  }

  return {
    cart: { lines: priced, subtotalPaisa, deliveryPaisa, discountPaisa, totalPaisa },
    issue: null,
  };
}
