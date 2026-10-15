/**
 * The coupon engine — one rule, three places.
 *
 * A coupon created by the kitchen has to behave the same when it is applied
 * automatically at a web checkout, applied automatically in the Android cart and
 * re-checked on the server before an order is written. Three implementations of
 * one discount rule is three ways to charge the wrong price, so the *rule* lives
 * here as pure functions over plain data, and the Kotlin twin
 * (`app/.../core/util/CouponMath.kt`) is the same rule written in Kotlin.
 * `tools/check-coupon-parity.mjs` reads the vectors out of the Kotlin test and
 * pushes them through this file, so a drift fails CI before a customer sees it.
 *
 * What the kitchen can now build (V-071 … V-078):
 *
 *   · **percent** — 20 % off, optionally capped ("20 % off, up to ৳100"), which
 *     is how a real discount stops a ৳4,000 party order from giving away ৳800;
 *   · **fixed** — ৳50 off;
 *   · **freedelivery** — the delivery fee is on the kitchen, which is the single
 *     most effective coupon in a per-kilometre delivery model;
 *   · a minimum food subtotal, a usage limit, an expiry date and an optional
 *     **daily window** (18:00–22:00) or a **day of week** set ("Friday only"),
 *     evaluated in Asia/Dhaka where the kitchen actually cooks;
 *   · **autoApply** — the code is published on a public board and applied by
 *     itself to every basket it can help, with no typing at all;
 *   · **banner** — the same coupon renders as a banner on the web store and in
 *     the app, because a discount nobody knows about is a discount nobody uses.
 *
 * Nothing here trusts the client. The board is public data — code, kind, value
 * and the conditions — and the order path re-reads the `coupons/{code}` document
 * live before it writes anything.
 */

/** `coupons/{CODE}` as the kitchen writes it. Every field is optional: old codes still work. */
export interface CouponDocument {
  code?: string;
  type?: string;
  value?: number;
  active?: boolean;
  description?: string;
  minSubtotalAmountPaisa?: number;
  usageLimit?: number;
  usedCount?: number;
  /** Firestore timestamp (ISO string through the REST API) or epoch millis. */
  expiresAt?: unknown;
  /**
   * The same instant, already normalised to epoch millis — the shape a board
   * entry carries, because a cached board holds no Firestore types at all.
   *
   * Both names are read, everywhere an expiry is judged. Reading only `expiresAt`
   * silently answered "this coupon never expires" for anything built from the
   * board, which is how a dated-out promotion keeps looking live.
   */
  expiresAtMillis?: unknown;
  /** "HH:MM" Asia/Dhaka, inclusive start of the daily window. */
  startsAt?: string;
  /** "HH:MM" Asia/Dhaka, exclusive end. A window that ends before it starts crosses midnight. */
  endsAt?: string;
  /** ["fri"] — empty or absent means every day. */
  daysOfWeek?: unknown;
  /** Ceiling on a percent coupon, in paisa. Zero means "no ceiling". */
  maxDiscountPaisa?: number;
  /** Applied to every basket it helps, with no code typed. */
  autoApply?: boolean;
  /** Published in the web store's offer strip. Absent means shown. */
  showOnWeb?: boolean;
  /** Optional banner picture. */
  bannerUrl?: string;
}

/** What a customer can safely see: no usage counters, no internal notes. */
export interface CouponBoardEntry {
  code: string;
  type: CouponType;
  value: number;
  description: string;
  minSubtotalAmountPaisa: number;
  maxDiscountPaisa: number;
  expiresAtMillis: number;
  startsAt: string;
  endsAt: string;
  daysOfWeek: string[];
  bannerUrl: string;
  /**
   * True when the basket applies this by itself, with no code typed anywhere.
   *
   * The vendor's own switch decides it, and the default is deliberate: a coupon
   * whose `autoApply` field is missing is treated as automatic, because a coupon
   * the kitchen created and published is meant to be used — a promotion nobody
   * notices is a discount nobody got. A coupon explicitly marked `autoApply:
   * false` is a code-only secret and stays one.
   */
  autoApply: boolean;
  /** True when this code is shown on the web store, banner and strip included. */
  showOnWeb: boolean;
}

export type CouponType = "percent" | "fixed" | "freedelivery";

export interface CouponClock {
  /** Minutes since midnight, Asia/Dhaka. */
  minutes: number;
  /** "sun" … "sat", Asia/Dhaka. */
  weekday: string;
  /** Epoch millis. */
  now: number;
}

export interface CouponContext extends CouponClock {
  subtotalPaisa: number;
  /** The delivery fee the basket currently carries; only `freedelivery` reads it. */
  deliveryPaisa: number;
}

/** The Dhaka clock the coupon windows are judged against. No DST, ever. */
export function couponClock(now: Date = new Date(), offsetMinutes = 360): CouponClock {
  const shifted = new Date(now.getTime() + offsetMinutes * 60_000);
  const weekday = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"][shifted.getUTCDay()] ?? "sun";
  return {
    minutes: shifted.getUTCHours() * 60 + shifted.getUTCMinutes(),
    weekday,
    now: now.getTime(),
  };
}

export interface CouponVerdict {
  eligible: boolean;
  /** Why it is not eligible, in words a person can act on. Empty when it is. */
  reason: string;
  /** What it would take off this basket right now. */
  discountPaisa: number;
}

/** Normalises whatever Firestore hands back for a timestamp into epoch millis. */
export function couponExpiryMillis(value: unknown): number {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.length > 0) {
    const parsed = Date.parse(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  if (value && typeof value === "object") {
    // A Firestore Timestamp that reached us as `{seconds, nanos}`.
    const seconds = Number((value as { seconds?: unknown }).seconds ?? Number.NaN);
    if (Number.isFinite(seconds)) return seconds * 1000;
  }
  return 0;
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/**
 * "16 Oct 2026", in Asia/Dhaka — the label the app prints for the same instant
 * (`TimeDhaka.formatDate`, pattern `d MMM yyyy`), so a customer refused on the
 * web and a customer refused in the app read one sentence rather than two.
 *
 * Dhaka is UTC+6 all year round, so this is arithmetic and a month table rather
 * than a locale lookup: no `Intl`, no timezone database, nothing that could
 * differ between a Worker isolate, a browser and a phone.
 *
 * An empty string means "there is no date to name" — a coupon with no expiry —
 * and the caller then falls back to a sentence that does not invent one.
 */
export function couponExpiryLabel(value: unknown, offsetMinutes = 360): string {
  const millis = couponExpiryMillis(value);
  if (!(millis > 0)) return "";
  const shifted = new Date(millis + offsetMinutes * 60_000);
  const month = MONTHS[shifted.getUTCMonth()] ?? "";
  if (!month) return "";
  return `${shifted.getUTCDate()} ${month} ${shifted.getUTCFullYear()}`;
}

export function couponTypeOf(value: unknown): CouponType | null {
  const raw = typeof value === "string" ? value.trim().toLowerCase() : "";
  if (raw === "percent" || raw === "fixed" || raw === "freedelivery") return raw;
  return null;
}

const HH_MM = /^([01]?\d|2[0-3]):([0-5]\d)$/;
const WEEKDAYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];

export function parseMinutesOfDay(value: unknown): number | null {
  if (typeof value !== "string") return null;
  const match = HH_MM.exec(value.trim());
  if (!match) return null;
  return Number(match[1]) * 60 + Number(match[2]);
}

export function couponDays(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const days: string[] = [];
  for (const entry of value) {
    if (typeof entry !== "string") continue;
    const day = entry.trim().toLowerCase().slice(0, 3);
    if (WEEKDAYS.includes(day) && !days.includes(day)) days.push(day);
  }
  return days;
}

/**
 * The daily window, as minutes. A window that ends before it starts crosses
 * midnight (20:00 → 01:00), which is what a late-night kitchen means by it.
 */
export function withinDailyWindow(startsAt: unknown, endsAt: unknown, minutes: number): boolean {
  const start = parseMinutesOfDay(startsAt);
  const end = parseMinutesOfDay(endsAt);
  if (start === null && end === null) return true;
  if (start !== null && end === null) return minutes >= start;
  if (start === null && end !== null) return minutes < end;
  if (start === end) return true;
  if (end! > start!) return minutes >= start! && minutes < end!;
  return minutes >= start! || minutes < end!;
}

/**
 * The discount this coupon is worth on this basket, before conditions.
 *
 * `percent` floors to whole paisa, `fixed` never exceeds the food subtotal, and
 * `freedelivery` is worth exactly the delivery fee — so a customer three
 * kilometres away pays nothing for delivery and one twelve kilometres away is
 * not charged a fee they were never quoted.
 *
 * `maxDiscountPaisa` is the ceiling for all three, `freedelivery` included: it
 * is the owner saying "this never costs me more than X", and a coupon that could
 * take the whole fee off a far-away order would be a cap that does not cap. The
 * headline says so out loud — "Free delivery, up to ৳ 100" — so the customer is
 * told before the basket, not after.
 *
 * The app's `CouponMath.discountPaisa` and the browser's `discountOf` are the
 * same arithmetic; `tools/check-coupon-parity.mjs` replays one vector table
 * through all three.
 */
export function couponDiscountPaisa(
  subtotalPaisa: number,
  deliveryPaisa: number,
  type: CouponType,
  value: number,
  maxDiscountPaisa = 0,
): number {
  const subtotal = Number.isFinite(subtotalPaisa) ? Math.max(0, Math.trunc(subtotalPaisa)) : 0;
  const delivery = Number.isFinite(deliveryPaisa) ? Math.max(0, Math.trunc(deliveryPaisa)) : 0;
  const cap = Number.isFinite(maxDiscountPaisa) && maxDiscountPaisa > 0 ? Math.trunc(maxDiscountPaisa) : 0;
  if (type === "freedelivery") return cap > 0 ? Math.min(delivery, cap) : delivery;
  if (subtotal <= 0) return 0;
  let discount = 0;
  if (type === "percent") {
    const percent = Math.min(100, Math.max(0, Math.trunc(Number.isFinite(value) ? value : 0)));
    discount = Math.floor((subtotal * percent) / 100);
  } else if (type === "fixed") {
    discount = Math.max(0, Math.trunc(Number.isFinite(value) ? value : 0));
  }
  if (cap > 0) discount = Math.min(discount, cap);
  return Math.max(0, Math.min(discount, subtotal));
}

/**
 * Is this coupon usable right now for this basket, and what is it worth?
 *
 * The order of the checks is the order a customer experiences them: is it alive
 * at all, is it switched on, is it in season, is it the right time of day, has
 * it been used up, does the basket qualify. Each refusal carries the sentence
 * that tells the truth about *this* basket.
 */
export function evaluateCoupon(raw: CouponDocument, context: CouponContext): CouponVerdict {
  const type = couponTypeOf(raw.type);
  if (raw.active !== true) {
    return { eligible: false, reason: "switched off", discountPaisa: 0 };
  }
  if (!type) {
    return { eligible: false, reason: "not set up correctly", discountPaisa: 0 };
  }

  const expiry = couponExpiryMillis(raw.expiresAt ?? raw.expiresAtMillis);
  if (expiry > 0 && expiry <= context.now) {
    return { eligible: false, reason: "expired", discountPaisa: 0 };
  }

  const days = couponDays(raw.daysOfWeek);
  if (days.length > 0 && !days.includes(context.weekday.slice(0, 3))) {
    return { eligible: false, reason: "not today", discountPaisa: 0 };
  }
  if (!withinDailyWindow(raw.startsAt, raw.endsAt, context.minutes)) {
    return { eligible: false, reason: "outside its hours", discountPaisa: 0 };
  }

  const usageLimit = Number(raw.usageLimit ?? 0);
  const usedCount = Number(raw.usedCount ?? 0);
  if (Number.isFinite(usageLimit) && usageLimit >= 1 && usedCount >= usageLimit) {
    return { eligible: false, reason: "used up", discountPaisa: 0 };
  }

  const minimum = Number(raw.minSubtotalAmountPaisa ?? 0);
  if (Number.isFinite(minimum) && minimum > 0 && context.subtotalPaisa < minimum) {
    return { eligible: false, reason: "basket too small", discountPaisa: 0 };
  }

  const discountPaisa = couponDiscountPaisa(
    context.subtotalPaisa,
    context.deliveryPaisa,
    type,
    Number(raw.value ?? 0),
    Number(raw.maxDiscountPaisa ?? 0),
  );
  if (discountPaisa <= 0) {
    return { eligible: false, reason: "changes nothing here", discountPaisa: 0 };
  }
  return { eligible: true, reason: "", discountPaisa };
}

export interface BestCoupon extends CouponVerdict {
  code: string;
  type: CouponType;
  description: string;
}

/**
 * The coupon the checkout should apply by itself: the one that saves the most.
 *
 * A tie is broken by the soonest expiry — a code that dies tonight is worth
 * using before one that lives until December. `null` means "no coupon helps
 * this basket", and the basket is then priced without one, which is the honest
 * outcome: an automatic discount that silently failed is worse than none.
 */
export function pickBestCoupon(coupons: CouponDocument[], context: CouponContext): BestCoupon | null {
  let best: BestCoupon | null = null;
  let bestExpiry = Number.POSITIVE_INFINITY;
  for (const raw of coupons) {
    const code = typeof raw.code === "string" ? raw.code.trim().toUpperCase() : "";
    if (!code) continue;
    const verdict = evaluateCoupon(raw, context);
    if (!verdict.eligible) continue;
    const expiry = couponExpiryMillis(raw.expiresAt ?? raw.expiresAtMillis);
    const beats =
      best === null ||
      verdict.discountPaisa > best.discountPaisa ||
      (verdict.discountPaisa === best.discountPaisa && expiry > 0 && expiry < bestExpiry);
    if (!beats) continue;
    best = {
      ...verdict,
      code,
      type: couponTypeOf(raw.type) ?? "fixed",
      description: typeof raw.description === "string" ? raw.description : "",
    };
    bestExpiry = expiry > 0 ? expiry : Number.POSITIVE_INFINITY;
  }
  return best;
}

/** How a coupon reads on a banner: "20 % off, up to ৳100" — never a bare number. */
export function couponHeadline(type: CouponType, value: number, maxDiscountPaisa = 0, symbol = "৳"): string {
  const grouped = (paisa: number): string => {
    const taka = Math.max(0, Math.round(paisa / 100));
    const digits = String(taka);
    if (digits.length <= 3) return digits;
    const head = digits.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ",") ;
    return `${head},${digits.slice(-3)}`;
  };
  if (type === "freedelivery") {
    return maxDiscountPaisa > 0
      ? `Free delivery, up to ${symbol} ${grouped(maxDiscountPaisa)}`
      : "Free delivery";
  }
  if (type === "percent") {
    const percent = Math.min(100, Math.max(0, Math.trunc(value)));
    return maxDiscountPaisa > 0
      ? `${percent}% off, up to ${symbol} ${grouped(maxDiscountPaisa)}`
      : `${percent}% off`;
  }
  return `${symbol} ${grouped(value)} off`;
}

/** One line under the headline: what the customer has to do to get it. */
export function couponConditionLine(entry: {
  minSubtotalAmountPaisa?: number;
  endsAt?: string;
  startsAt?: string;
  daysOfWeek?: string[];
  expiresAtMillis?: number;
}): string {
  const parts: string[] = [];
  const minimum = Number(entry.minSubtotalAmountPaisa ?? 0);
  if (Number.isFinite(minimum) && minimum > 0) {
    parts.push(`on orders from ৳ ${Math.round(minimum / 100)}`);
  }
  const days = couponDays(entry.daysOfWeek ?? []);
  if (days.length > 0 && days.length < 7) parts.push(`on ${days.join(", ")}`);
  const start = typeof entry.startsAt === "string" ? entry.startsAt : "";
  const end = typeof entry.endsAt === "string" ? entry.endsAt : "";
  if (start && end) parts.push(`between ${start} and ${end}`);
  else if (end) parts.push(`before ${end}`);
  const expiry = Number(entry.expiresAtMillis ?? 0);
  if (Number.isFinite(expiry) && expiry > 0) {
    parts.push(`until ${new Date(expiry).toISOString().slice(0, 10)}`);
  }
  return parts.join(" · ");
}

/** The public board entry for one coupon — what the storefront and the app see. */
export function toBoardEntry(raw: CouponDocument): CouponBoardEntry | null {
  const code = typeof raw.code === "string" ? raw.code.trim().toUpperCase() : "";
  const type = couponTypeOf(raw.type);
  if (!code || !type) return null;
  const autoApply = raw.autoApply !== false;
  return {
    autoApply,
    // Two independent switches, and the kitchen owns both. `autoApply` decides
    // whether a basket applies it with no code typed; `showOnWeb` decides whether
    // it appears in the offer strip. A code-only promotion the kitchen chose to
    // advertise — "type EID20" — is a real thing a partner app must support, so
    // the entry reports the switch rather than a rule of its own. Absent means
    // shown, exactly as it does in the vendor console and in the app.
    showOnWeb: raw.showOnWeb !== false,
    code,
    type,
    value: Number.isFinite(Number(raw.value)) ? Number(raw.value) : 0,
    description: typeof raw.description === "string" ? raw.description.slice(0, 160) : "",
    minSubtotalAmountPaisa: Number.isFinite(Number(raw.minSubtotalAmountPaisa))
      ? Math.max(0, Math.trunc(Number(raw.minSubtotalAmountPaisa)))
      : 0,
    maxDiscountPaisa: Number.isFinite(Number(raw.maxDiscountPaisa)) ? Math.max(0, Math.trunc(Number(raw.maxDiscountPaisa))) : 0,
    expiresAtMillis: couponExpiryMillis(raw.expiresAt ?? raw.expiresAtMillis),
    startsAt: typeof raw.startsAt === "string" ? raw.startsAt : "",
    endsAt: typeof raw.endsAt === "string" ? raw.endsAt : "",
    daysOfWeek: couponDays(raw.daysOfWeek),
    bannerUrl: typeof raw.bannerUrl === "string" ? raw.bannerUrl : "",
  };
}

/**
 * The board itself: the live offers, the ones with the least time left first.
 *
 * Two switches feed it, and both default to *on*, because a coupon the kitchen
 * created has to reach somebody:
 *
 *  · `autoApply` — the basket applies it with no code typed. A voucher the owner
 *    means to be found only by people who know its code sets this to false.
 *  · `showOnWeb` — the code appears in the storefront's offer strip.
 *
 * A coupon that is neither automatic nor shown is not published at all: keeping
 * it on the board would only publish a secret nobody can use.
 *
 * Everything that cannot be used tonight is dropped here rather than misapplied
 * at the checkout: switched-off coupons, expired ones, and codes whose usage
 * limit is spent.
 */
export function buildCouponBoard(coupons: CouponDocument[], now = Date.now()): CouponBoardEntry[] {
  const entries: CouponBoardEntry[] = [];
  for (const raw of coupons) {
    if (raw.active !== true) continue;
    if (raw.autoApply === false && raw.showOnWeb === false) continue;
    const expiry = couponExpiryMillis(raw.expiresAt ?? raw.expiresAtMillis);
    if (expiry > 0 && expiry <= now) continue;
    const usageLimit = Number(raw.usageLimit ?? 0);
    const usedCount = Number(raw.usedCount ?? 0);
    if (Number.isFinite(usageLimit) && usageLimit >= 1 && usedCount >= usageLimit) continue;
    const entry = toBoardEntry(raw);
    if (entry) entries.push(entry);
  }
  // Soonest to expire first: the banner the kitchen most needs people to use is
  // the one that has the least time left.
  return entries.sort((left, right) => {
    const leftExpiry = left.expiresAtMillis > 0 ? left.expiresAtMillis : Number.POSITIVE_INFINITY;
    const rightExpiry = right.expiresAtMillis > 0 ? right.expiresAtMillis : Number.POSITIVE_INFINITY;
    if (leftExpiry !== rightExpiry) return leftExpiry - rightExpiry;
    return left.code.localeCompare(right.code);
  });
}
