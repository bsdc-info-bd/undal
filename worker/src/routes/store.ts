/**
 * The web store's API (P-013).
 *
 * Two endpoints, both rate limited by the router:
 *   POST /api/store/session  — an anonymous Firebase session for the visitor
 *   POST /api/store/order    — a priced, validated, cash-on-delivery order
 *
 * The order endpoint is the important one. The browser sends dish ids and
 * quantities and nothing else: every price, the delivery fee, the coupon
 * discount and the total are computed here from live Firestore data, so a
 * tampered client cannot buy a ৳500 meal for ৳1. The order document it writes
 * has exactly the shape the Android app writes, the same rules-era fields, and
 * the kitchen is alerted through the same FCM path the app's own orders use.
 */
import type { Env } from "../env";
import { projectId } from "../env";
import { fsGet, fsPatchWithServerTimestamps } from "../firestore";
import { raiseKitchenAlert } from "../alerting";
import { rtdbGet, rtdbSet } from "../rtdb";
import { HttpError, json, readJson } from "../util/http";
import { formatPaisa } from "../util/money";
import {
  normalizeBdMobile,
  normalizeCoupon,
  deliveryFeePaisa,
  distanceFromStorePinned,
  grandTotalPaisa,
  isWithinRadius,
  usableName,
  usableText,
  MAX_NAME_LENGTH,
  lineTotalPaisa,
  validateCartLines,
  type CartLine,
} from "../web/cart";
import {
  couponClock,
  couponDays,
  couponExpiryMoment,
  couponHeadline,
  couponTypeOf,
  evaluateCoupon,
  pickBestCoupon,
  type CouponDocument,
} from "../web/coupons";
import { MENU_MEMO_TTL_MS, menuItem, toStoreConfig, webMenu } from "../web/store-data";
import { closedSentence } from "../util/store-hours";
import { memoGet, type KvBinding } from "../util/kv";

const ORDER_ALERT_TTL = "300s";
const COUNTER_START = 1000;
const MAX_DISTANCE_KM = 30;

const str = (value: unknown, fallback = ""): string => (typeof value === "string" ? value : fallback);
const num = (value: unknown, fallback = 0): number => {
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
};

// ------------------------------------------------------------------- session

/**
 * Firebase anonymous sign-in, proxied through the Worker.
 *
 * The web page never holds the project's API key or talks to identitytoolkit
 * directly: it asks this endpoint, and gets back the short-lived ID token plus
 * the refresh token it stores in memory for the tab's lifetime. If the key is
 * missing the endpoint says so plainly — the store then browses but refuses to
 * take an order, which is the honest failure mode.
 */
const EMAIL_SHAPE = /^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/;
const MIN_PASSWORD_LENGTH = 6;

/**
 * Signs a returning customer in with the account they already use in the app
 * (P-013). The Worker holds the web API key, so the browser never has to, and
 * the password is forwarded once over HTTPS to Firebase Authentication and is
 * never stored, logged or echoed anywhere.
 */
export async function handleStoreSignIn(env: Env, request: Request): Promise<Response> {
  const webApiKey = (env.FIREBASE_WEB_API_KEY ?? "").trim();
  if (!webApiKey) {
    throw new HttpError(
      503,
      "web-sign-in-not-configured",
      "Ordering on the web is switched off right now — the kitchen's app still takes orders, and so does a phone call.",
    );
  }

  const body = await readJson<{ email?: string; password?: string }>(request);
  const email = str(body.email).trim().toLowerCase();
  const password = str(body.password);
  if (!EMAIL_SHAPE.test(email) || password.length < MIN_PASSWORD_LENGTH) {
    throw new HttpError(
      400,
      "credentials-shape",
      "Enter the email address and password you use in the Undal app.",
    );
  }

  let response: Response;
  try {
    response = await fetch(
      `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${encodeURIComponent(webApiKey)}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ email, password, returnSecureToken: true }),
      },
    );
  } catch {
    throw new HttpError(
      503,
      "web-sign-in-unreachable",
      "The sign-in service could not be reached from this network. Please try again in a minute.",
    );
  }

  const payload = (await response.json().catch(() => ({}))) as {
    idToken?: string;
    refreshToken?: string;
    localId?: string;
    expiresIn?: string;
    email?: string;
    error?: { message?: string };
  };
  if (!response.ok || !payload.idToken || !payload.localId) {
    throw new HttpError(
      response.status === 400 ? 401 : 502,
      "sign-in-refused",
      signInSentence(payload.error?.message),
    );
  }

  // The refresh token is what lets the tab stay signed in past the ID token's
  // hour. It is returned to the browser that just proved the password, stored in
  // that tab's sessionStorage, and never written to a log.
  return json({
    ok: true,
    idToken: payload.idToken,
    refreshToken: payload.refreshToken ?? "",
    expiresIn: Number(payload.expiresIn) || 3600,
    uid: payload.localId,
    email: payload.email ?? email,
    profile: await readCustomerProfile(env, payload.localId),
  });
}

/** Firebase's error codes, said the way a customer can act on them. */
function signInSentence(code: string | undefined): string {
  switch (code) {
    case "EMAIL_NOT_FOUND":
      return "That email address has no Undal account. Check the spelling, or create one in the app.";
    case "INVALID_PASSWORD":
    case "INVALID_LOGIN_CREDENTIALS":
    case "INVALID_EMAIL":
      return "That email and password do not match. Try again, or reset the password from the app.";
    case "USER_DISABLED":
      return "That account has been switched off. Please call the kitchen.";
    case "TOO_MANY_ATTEMPTS_TRY_LATER":
      return "Too many attempts. Please wait a few minutes and try again.";
    default:
      return "The sign-in did not go through. Check the email and password you use in the Undal app.";
  }
}

export interface WebProfile {
  name: string;
  phone: string;
  address: { lat: number; lng: number; title: string; desc: string; note: string } | null;
}

/**
 * The name, the number and the pinned address from `users/{uid}`, so the checkout
 * form arrives filled and the map arrives with the pin already ticked.
 */
async function readCustomerProfile(env: Env, uid: string): Promise<WebProfile> {
  const empty: WebProfile = { name: "", phone: "", address: null };
  if (!/^[A-Za-z0-9_-]{6,128}$/.test(uid)) return empty;
  try {
    const record = await fsGet(env, `users/${uid}`);
    if (!record) return empty;
    const address = record.address as Record<string, unknown> | null;
    const lat = Number(address?.lat ?? 0);
    const lng = Number(address?.lng ?? 0);
    return {
      name: str(record.name).slice(0, 80),
      phone: str(record.phone).slice(0, 20),
      address: Number.isFinite(lat) && Number.isFinite(lng) && lat !== 0 && lng !== 0
        ? {
            lat,
            lng,
            title: str(address?.title).slice(0, 160),
            desc: str(address?.desc).slice(0, 300),
            note: str(address?.note).slice(0, 300),
          }
        : null,
    };
  } catch {
    return empty;
  }
}

export async function handleStoreSession(env: Env): Promise<Response> {
  const webApiKey = (env.FIREBASE_WEB_API_KEY ?? "").trim();
  if (!webApiKey) {
    return json(
      {
        ok: false,
        error: "web-sign-in-not-configured",
        message:
          "Web ordering needs the Firebase Web API key. Add FIREBASE_WEB_API_KEY to the repository secrets and re-run the deploy-worker workflow.",
      },
      503,
    );
  }

  let response: Response;
  try {
    response = await fetch(
      `https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${encodeURIComponent(webApiKey)}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ returnSecureToken: true }),
      },
    );
  } catch (error) {
    // The visitor's order is more important than our diagnostics: say what is
    // wrong in words the kitchen can act on.
    throw new HttpError(
      503,
      "web-sign-in-unreachable",
      "The web sign-in service could not be reached from this network. Please call the kitchen or try again in a minute.",
    );
  }
  const payload = (await response.json().catch(() => ({}))) as {
    idToken?: string;
    refreshToken?: string;
    expiresIn?: string;
    localId?: string;
    error?: { message?: string };
  };
  if (!response.ok || !payload.idToken) {
    throw new HttpError(502, "web-sign-in-failed", payload.error?.message ?? "Anonymous sign-in failed.");
  }

  return json(
    {
      ok: true,
      idToken: payload.idToken,
      refreshToken: payload.refreshToken ?? "",
      expiresIn: Number(payload.expiresIn ?? 3600),
      uid: payload.localId ?? "",
    },
    200,
    { "cache-control": "no-store" },
  );
}

// --------------------------------------------------------------------- order

interface OrderRequest {
  lines?: unknown;
  couponCode?: string;
  note?: string;
  customerName?: string;
  phone?: string;
  address?: { lat?: number; lng?: number; title?: string; desc?: string; note?: string };
}

interface StoredOrder {
  id: string;
  shortId: string;
  totalPaisa: number;
  status: string;
}

/**
 * Kitchen alert for a web order.
 *
 * Best-effort in the sense that a push failure must never fail the order — but
 * never silent: the outcome is logged, including the case that used to disappear
 * (`if (tokens.length === 0) return`), which is a kitchen that stopped ringing
 * for the web store and gave nobody a clue why.
 */
async function alertKitchen(env: Env, orderId: string, shortId: string, itemCount: number, totalPaisa: number): Promise<void> {
  const outcome = await raiseKitchenAlert(
    env,
    {
      orderId,
      shortId,
      title: `New order ${shortId}`,
      body: `${itemCount} item(s) · ${formatPaisa(totalPaisa)} · cash on delivery (web)`,
      ttl: ORDER_ALERT_TTL,
      extra: { source: "web" },
    },
    `order-${orderId}`,
  ).catch((error) => {
    console.error("undal-web-kitchen-alert failed", error);
    return null;
  });
  if (outcome?.warning) {
    console.warn("undal-web-kitchen-alert", outcome.warning, JSON.stringify({ orderId, shortId }));
  }
}

/**
 * Reserves the next short id. The REST API has no transactions, so this uses
 * Firestore's document-update precondition: the write is rejected if the
 * counter changed since it was read, and the caller retries with the new value.
 */
async function reserveShortId(env: Env): Promise<{ shortId: string; value: number }> {
  for (let attempt = 0; attempt < 4; attempt++) {
    const counter = await fsGet(env, "counters/orders");
    const current = num(counter?.value, COUNTER_START);
    const next = current + 1;
    if (next > 999_999) {
      throw new HttpError(409, "counter-exhausted", "The order counter has run out — please tell the kitchen.");
    }
    const committed = await commitWithPreconditions(env, [
      {
        path: "counters/orders",
        fields: { value: next },
        requireExists: counter !== null,
        updateTime: str(counter?.updateTime).length > 0 ? str(counter?.updateTime) : null,
      },
    ]);
    if (committed) return { shortId: `UND-${next}`, value: next };
  }
  throw new HttpError(503, "counter-busy", "The kitchen is taking a lot of orders right now. Please tap again.");
}

interface PreconditionedWrite {
  path: string;
  fields: Record<string, unknown>;
  requireExists: boolean;
  updateTime: string | null;
}

/** Commits writes with `currentDocument` preconditions; false means "retry". */
async function commitWithPreconditions(env: Env, writes: PreconditionedWrite[]): Promise<boolean> {
  // Authorisation comes from the service account, never from the web key.
  const { accessToken } = await import("../gcp");
  const token = await accessToken(env);
  const documentName = (path: string): string =>
    `projects/${projectId(env)}/databases/(default)/documents/${path}`;

  const body = {
    writes: writes.map((write: PreconditionedWrite) => {
      const update: Record<string, unknown> = {
        name: documentName(write.path),
        fields: Object.fromEntries(
          Object.entries(write.fields).map(([key, value]) => [key, toRestValue(value)]),
        ),
      };
      const updateMask = { fieldPaths: Object.keys(write.fields) };
      const precondition = write.updateTime
        ? { updateTime: write.updateTime }
        : write.requireExists
          ? { exists: true }
          : { exists: false };
      return { update, updateMask, currentDocument: precondition };
    }),
  };

  const response = await fetch(
    `https://firestore.googleapis.com/v1/projects/${projectId(env)}/databases/(default)/documents:commit`,
    {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify(body),
    },
  );
  if (response.ok) return true;
  const text = await response.text();
  // FAILED_PRECONDITION means somebody else won the race: retry, do not fail.
  if (response.status === 400 && /FAILED_PRECONDITION|already exists|ABORTED/i.test(text)) return false;
  if (response.status === 409) return false;
  throw new HttpError(502, "firestore-commit-failed", text.slice(0, 200));
}

/** The Firestore REST value shape, for writes made through the commit API. */
function toRestValue(value: unknown): unknown {
  if (value === null || value === undefined) return { nullValue: null };
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "number") {
    return Number.isInteger(value) ? { integerValue: String(value) } : { doubleValue: value };
  }
  if (typeof value === "string") return { stringValue: value };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(toRestValue) } };
  if (typeof value === "object") {
    return {
      mapValue: {
        fields: Object.fromEntries(
          Object.entries(value as Record<string, unknown>).map(([key, nested]) => [key, toRestValue(nested)]),
        ),
      },
    };
  }
  return { nullValue: null };
}

/**
 * Everything a web order needs, checked and priced.
 *
 * The identity here is deliberately a *typed* one: a name, a phone number and a
 * pinned address. It is used by both callers — the JSON endpoint a script posts
 * to, and the plain form post that works with no script at all.
 */
export interface WebOrderIdentity {
  customerName: string;
  phone: string;
  lat: number;
  lng: number;
  addressTitle: string;
  addressDesc: string;
  addressNote: string;
}

/** The validated identity, or the sentence that says what is missing. */
export function readOrderIdentity(input: {
  customerName?: unknown;
  phone?: unknown;
  lat?: unknown;
  lng?: unknown;
  addressTitle?: unknown;
  addressDesc?: unknown;
  addressNote?: unknown;
}): WebOrderIdentity {
  const customerName = str(input.customerName).trim().slice(0, MAX_NAME_LENGTH);
  if (!usableName(customerName)) {
    throw new HttpError(
      400,
      "name-required",
      "Please write the name the kitchen should call out — letters, at least three of them.",
    );
  }
  const phone = normalizeBdMobile(str(input.phone));
  if (!phone) {
    throw new HttpError(400, "phone-invalid", "Write a Bangladeshi mobile number like 01712345678.");
  }
  const lat = num(input.lat, Number.NaN);
  const lng = num(input.lng, Number.NaN);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat === 0 || lng === 0) {
    throw new HttpError(400, "location-required", "Tap the locate button so the rider knows where to go.");
  }
  const addressTitle = str(input.addressTitle).trim().slice(0, 80);
  const addressDesc = str(input.addressDesc).trim().slice(0, 200);
  const addressNote = str(input.addressNote).trim().slice(0, 200);
  if (!usableText(addressTitle) || !usableText(addressDesc) || !usableText(addressNote)) {
    throw new HttpError(
      400,
      "address-incomplete",
      "Fill in the area, the house or road, and one landmark the rider can ask for.",
    );
  }
  return { customerName, phone, lat, lng, addressTitle, addressDesc, addressNote };
}

export interface WebOrderInput extends WebOrderIdentity {
  /** The Firebase uid of whoever is ordering — a signed-in account or a guest session. */
  uid: string;
  /** Dish ids and quantities. Prices are never accepted from a client. */
  lines: unknown;
  /** A code the customer typed. Empty means "let the kitchen's own coupons decide". */
  couponCode?: string;
  note?: string;
  /** "web" for the scripted checkout, "web-nojs" for the plain form. */
  source?: string;
}

export interface WebOrderResult {
  order: StoredOrder;
  lines: Array<Record<string, unknown>>;
  subtotalPaisa: number;
  deliveryPaisa: number;
  discountPaisa: number;
  /** The coupon that was applied, automatically or by hand. Empty for none. */
  couponCode: string;
  /** Human sentence for the applied coupon, for a confirmation page. */
  couponLabel: string;
  distanceKm: number;
  createdAt: string;
  /** True when the discount came from the kitchen's automatic board. */
  couponAutomatic: boolean;
}

/**
 * Creates a cash-on-delivery order the way the Android app does.
 *
 * The single source of truth for every web order, scripted or not:
 *   1. the basket is re-read from live Firestore data and re-priced,
 *   2. the delivery fee is measured from the kitchen's own pin to the customer's,
 *   3. a **coupon is applied by itself** when the kitchen has published an
 *      automatic one that helps this basket, and a typed code is honoured when
 *      it is valid — never silently replaced,
 *   4. the order document, the live mirror, the chat thread and the kitchen
 *      alert are written exactly as the app writes them.
 */
export async function createWebOrder(env: Env, input: WebOrderInput): Promise<WebOrderResult> {
  const { customerName, phone, lat, lng, addressTitle, addressDesc, addressNote } = input;

  // ---- what is in the basket ----------------------------------------------
  const { lines, issues } = validateCartLines(input.lines);
  if (issues.length > 0 || lines.length === 0) {
    throw new HttpError(400, "cart-invalid", issues[0]?.message ?? "Your cart is empty.");
  }

  // ---- live catalogue, live store -----------------------------------------
  const menu = await webMenu(env, { force: true });
  const store = menu.store ?? toStoreConfig(await fsGet(env, "config/store").catch(() => null));

  // ---- is the kitchen taking orders at all? -------------------------------
  // The one rule every surface asks (util/store-hours.ts, StoreHours.kt): the
  // owner's switch, the watchdog's pause, and the published hours. This line is
  // the difference between a kitchen that keeps its schedule and one that takes
  // a web order at 3 a.m. because somebody pressed Open last Tuesday — the page
  // already said "Closed", and the order went through anyway.
  const closedRefusal = closedSentence(store);
  if (closedRefusal) throw new HttpError(409, "store-closed", closedRefusal);

  const priced = lines.map((line: CartLine) => {
    const live = menuItem(menu, line.id);
    if (!live) {
      throw new HttpError(409, "item-missing", "A dish in your cart left the menu. Please review the cart.");
    }
    if (!live.available) {
      throw new HttpError(409, "item-unavailable", `${live.name} is finished for today.`);
    }
    const unitAmountPaisa = live.payablePaisa;
    return {
      id: line.id,
      name: live.name,
      qty: line.qty,
      unitAmountPaisa,
      lineTotalAmountPaisa: lineTotalPaisa(unitAmountPaisa, line.qty),
      kind: live.kind,
    };
  });

  const subtotalPaisa = priced.reduce((sum, line) => sum + line.lineTotalAmountPaisa, 0);
  if (store?.minOrderAmountPaisa && subtotalPaisa < store.minOrderAmountPaisa) {
    throw new HttpError(
      409,
      "below-minimum",
      `The kitchen cooks from ${formatPaisa(store.minOrderAmountPaisa)} upwards. Add a little more to your basket.`,
    );
  }

  const distanceKm = store
    ? distanceFromStorePinned(store.storeLat, store.storeLng, lat, lng)
    : null;
  if (distanceKm === null) {
    throw new HttpError(
      409,
      "store-pin-unpublished",
      "The kitchen has not published its location yet, so the delivery fee cannot be measured. Please call them.",
    );
  }
  if (distanceKm > MAX_DISTANCE_KM) {
    throw new HttpError(409, "too-far", "That address is outside the delivery limit.");
  }
  if (store && !isWithinRadius(distanceKm, store.radiusKm)) {
    throw new HttpError(
      409,
      "outside-radius",
      `The kitchen delivers inside ${store.radiusKm} km. That address is ${distanceKm.toFixed(1)} km away.`,
    );
  }

  const deliveryPaisa = deliveryFeePaisa(distanceKm, store?.deliveryPerKmAmountPaisa ?? 0);

  // ---- coupon, re-validated live ------------------------------------------
  let discountPaisa = 0;
  let appliedCode = "";
  let couponLabel = "";
  let couponAutomatic = false;
  let couponUpdateTime: string | null = null;
  let couponUsedCount = 0;
  const typedCode = str(input.couponCode).trim();
  const requestedCode = normalizeCoupon(typedCode);
  if (typedCode && !requestedCode) {
    // A code that cannot exist used to be dropped without a word, and the
    // customer paid full price believing the discount was on the bill.
    throw new HttpError(
      409,
      "coupon-invalid",
      `"${typedCode.slice(0, 24)}" is not a coupon code — codes are 3 to 24 letters, digits, dashes or underscores, ` +
        "with no spaces. Check it against the offer, or clear the box to order without one.",
    );
  }
  if (requestedCode) {
    const coupon = await fsGet(env, `coupons/${requestedCode}`);
    if (!coupon) throw new HttpError(409, "coupon-missing", `Coupon ${requestedCode} no longer exists.`);
    const type = couponTypeOf(coupon.type);
    const verdict = evaluateCoupon(coupon as CouponDocument, {
      subtotalPaisa,
      deliveryPaisa,
      ...couponClock(),
    });
    if (!verdict.eligible) {
      throw new HttpError(409, `coupon-${verdict.reason.replace(/\s+/g, "-")}`, couponSentence(requestedCode, coupon, verdict.reason, formatPaisa));
    }
    discountPaisa = verdict.discountPaisa;
    appliedCode = requestedCode;
    couponLabel = couponHeadline(type ?? "fixed", num(coupon.value), num(coupon.maxDiscountPaisa));
    couponUpdateTime = str(coupon.updateTime).length > 0 ? str(coupon.updateTime) : null;
    couponUsedCount = num(coupon.usedCount);
  }

  // The kitchen's automatic coupons, applied with no code typed at all. This is
  // the behaviour the owner expects when they switch a coupon on: it just works,
  // on the web and in the app, without a customer having to know it exists.
  if (!appliedCode && (menu.coupons ?? []).length > 0) {
    // Only the automatic ones: a code-only coupon must never be applied by the
    // server to a customer who never typed it.
    const board = (menu.coupons ?? [])
      .filter((entry) => entry.autoApply !== false)
      .map((entry) => ({ ...entry, active: true }) as CouponDocument);
    const best = pickBestCoupon(board, {
      subtotalPaisa,
      deliveryPaisa,
      ...couponClock(),
    });
    if (best) {
      // The board is a cached snapshot; the counter and the switch are not.
      const live = await fsGet(env, `coupons/${best.code}`).catch(() => null);
      const verdict = live
        ? evaluateCoupon(live as unknown as CouponDocument, { subtotalPaisa, deliveryPaisa, ...couponClock() })
        : { eligible: false, reason: "unreadable", discountPaisa: 0 };
      if (verdict.eligible && live) {
        discountPaisa = verdict.discountPaisa;
        appliedCode = best.code;
        couponAutomatic = true;
        couponLabel = couponHeadline(
          couponTypeOf(live.type) ?? "fixed",
          num(live.value),
          num(live.maxDiscountPaisa),
        );
        couponUpdateTime = str(live.updateTime).length > 0 ? str(live.updateTime) : null;
        couponUsedCount = num(live.usedCount);
      }
    }
  }

  const totalPaisa = grandTotalPaisa(subtotalPaisa, deliveryPaisa, discountPaisa);
  if (totalPaisa <= 0) throw new HttpError(409, "total-zero", "This order would total nothing.");

  // ---- reserve the number, then write the order ---------------------------
  const { shortId } = await reserveShortId(env);
  const orderId = crypto.randomUUID().replace(/-/g, "");
  const orderPath = `orders/${orderId}`;
  const createdAt = new Date().toISOString();

  const orderFields: Record<string, unknown> = {
    shortId,
    customerId: input.uid,
    customerName,
    phone,
    channel: input.source ?? "web",
    items: priced,
    subtotalAmountPaisa: subtotalPaisa,
    deliveryAmountPaisa: deliveryPaisa,
    discountAmountPaisa: discountPaisa,
    totalAmountPaisa: totalPaisa,
    payment: "COD",
    address: {
      lat,
      lng,
      title: addressTitle,
      desc: addressDesc,
      note: addressNote,
      geohashLabel: "",
    },
    distanceKm,
    status: "placed",
    timeline: [{ status: "placed" }],
    chatId: orderId,
  };
  if (appliedCode) {
    orderFields.couponCode = appliedCode;
    orderFields.couponAuto = couponAutomatic;
  }
  const note = str(input.note).trim().slice(0, 300);
  if (note) orderFields.note = note;

  await fsPatchWithServerTimestamps(env, orderPath, orderFields, ["createdAt", "updatedAt"]);

  // Coupon accounting is a separate, conditional write: if somebody else spent
  // the last use in the meantime the counter is left alone rather than oversold.
  if (appliedCode) {
    await commitWithPreconditions(env, [
      {
        path: `coupons/${appliedCode}`,
        fields: { usedCount: couponUsedCount + 1 },
        requireExists: true,
        updateTime: couponUpdateTime,
      },
    ]).catch(() => false);
  }

  // Chat metadata, the live mirror and the kitchen alert — all best effort.
  await fsPatchWithServerTimestamps(env, `chats/${orderId}`, { customerId: input.uid, orderId }, [
    "createdAt",
  ]).catch(() => undefined);
  await rtdbSet(env, `orderLive/${orderId}`, {
    customerId: input.uid,
    status: "placed",
    updatedAt: Date.now(),
  }).catch(() => undefined);
  await rtdbSet(env, `chats/${orderId}`, { customerId: input.uid, createdAt: Date.now() }).catch(
    () => undefined,
  );
  await alertKitchen(env, orderId, shortId, priced.length, totalPaisa).catch(() => undefined);

  return {
    order: { id: orderId, shortId, totalPaisa, status: "placed" },
    lines: priced,
    subtotalPaisa,
    deliveryPaisa,
    discountPaisa,
    couponCode: appliedCode,
    couponLabel,
    distanceKm,
    createdAt,
    couponAutomatic,
  };
}

export async function handleStoreOrder(env: Env, request: Request): Promise<Response> {
  const claims = await import("../authverify").then((module) => module.verifyIdToken(env, bearerFrom(request)));
  const body = await readJson<OrderRequest>(request);
  const identity = readOrderIdentity({
    customerName: body.customerName,
    phone: body.phone,
    lat: body.address?.lat,
    lng: body.address?.lng,
    addressTitle: body.address?.title,
    addressDesc: body.address?.desc,
    addressNote: body.address?.note,
  });

  const result = await createWebOrder(env, {
    ...identity,
    uid: claims.sub,
    lines: body.lines,
    couponCode: body.couponCode,
    note: body.note,
    source: "web",
  });

  return json(
    {
      ok: true,
      order: result.order,
      lines: result.lines,
      deliveryPaisa: result.deliveryPaisa,
      discountPaisa: result.discountPaisa,
      subtotalPaisa: result.subtotalPaisa,
      couponCode: result.couponCode,
      couponLabel: result.couponLabel,
      couponAutomatic: result.couponAutomatic,
      distanceKm: Math.round(result.distanceKm * 10) / 10,
      createdAt: result.createdAt,
      trackingUrl: `https://${new URL(request.url).host}/order/${result.order.id}`,
    },
    201,
  );
}

/**
 * The sentence a refused coupon gets: which code, and what is true about it.
 *
 * Exported for `worker/test/coupons.test.ts`: this is the sentence a customer
 * reads when a promotion does not apply, and "CODE has expired" with no date is
 * what made an owner insist the coupon was active while the checkout refused it.
 *
 * These are the same sentences the app's `CouponRepo.describe` writes, word for
 * word where a fact is being stated — the date it ended, the days it runs, the
 * hours, the shortfall, the count. A customer who is refused on the web and then
 * tries the app must not get two different explanations of one rule, because the
 * second one reads as the first one lying.
 *
 * Every fact comes from the live document, never from the refusal word alone:
 * "has expired" with no date is a dead end, and a dead end at the checkout is
 * where a customer gives up.
 */
export function couponSentence(code: string, coupon: Record<string, unknown>, reason: string, format: (paisa: number) => string): string {
  const minimum = num(coupon.minSubtotalAmountPaisa);
  switch (reason) {
    case "expired": {
      // The date *and* the minute, in Dhaka: "expired" with only a day leaves an
      // owner who set tomorrow's date unable to see what the database holds.
      const ended = couponExpiryMoment(coupon.expiresAt ?? coupon.expiresAtMillis);
      return ended ? `${code} expired on ${ended}.` : `${code} has expired.`;
    }
    case "used up": {
      const limit = num(coupon.usageLimit);
      return limit >= 1
        ? `${code} has been used its full ${limit} times.`
        : `${code} has been used its last time.`;
    }
    case "switched off":
      return `${code} is switched off right now.`;
    case "basket too small":
      return `${code} needs a food subtotal of ${format(minimum)}.`;
    case "not set up correctly":
      return `${code} is not set up correctly — please tell the kitchen.`;
    case "outside its hours": {
      const from = str(coupon.startsAt);
      const to = str(coupon.endsAt);
      return from && to ? `${code} works between ${from} and ${to}.` : `${code} is only live between its set hours.`;
    }
    case "not today": {
      const days = couponDays(coupon.daysOfWeek);
      return days.length > 0 ? `${code} only works on ${days.join(", ")}.` : `${code} is not running today.`;
    }
    default:
      return `${code} would not change this order's total.`;
  }
}

function bearerFrom(request: Request): string {
  const header = request.headers.get("authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!token) throw new HttpError(401, "missing-id-token");
  return token;
}

/** `GET /api/store/menu` — the same snapshot the pages render, for the client. */
export async function handleStoreMenuApi(env: Env): Promise<Response> {
  const menu = await webMenu(env);
  return json(
    {
      ok: true,
      fetchedAt: menu.fetchedAt,
      store: menu.store,
      sections: menu.sections,
      dishes: menu.dishes,
      combos: menu.combos,
      campaigns: menu.campaigns,
      // The kitchen's live automatic offers. They travel with the menu because
      // that is what they belong to: a coupon with no menu is a coupon nobody
      // can satisfy, and the two must never disagree about the subtotal.
      coupons: menu.coupons ?? [],
    },
    200,
    { "cache-control": "public, max-age=60" },
  );
}

/**
 * `GET /api/store/coupons` — the offers, on their own.
 *
 * The menu response carries them too; this exists for the one caller that needs
 * them without the menu — a price check, a re-order screen, the app's own
 * "what is running tonight?" glance. Same source, same board, so the two can
 * never disagree.
 */
export async function handleStoreCouponsApi(env: Env): Promise<Response> {
  const menu = await webMenu(env);
  const board = menu.coupons ?? [];
  const automatic = board.filter((entry) => entry.autoApply);
  return json(
    {
      ok: true,
      fetchedAt: menu.fetchedAt,
      coupons: board,
      // The subset that needs no code typed anywhere: this is the list the
      // checkout applies by itself.
      automatic,
      storeOpen: menu.store?.open === true && menu.store?.autoClosed !== true,
    },
    200,
    { "cache-control": "public, max-age=60" },
  );
}

/** `GET /api/store/order/{id}` — status polling for the tracking page. */
/**
 * The further of two copies of one order's status.
 *
 * A cancellation is terminal and can be reached from either writer, so it always
 * wins; otherwise the status later in the journey wins, because the only way a
 * status is "behind" is a mirror or a document that has not caught up. This is the
 * same rule the Android tracking screen applies (`OrderStateMachine.newest`), so
 * the phone in the customer's hand and the tab on their desk cannot disagree.
 */
function newerOrderStatus(documentStatus: string, mirrorStatus: string): string {
  const sequence = ["placed", "accepted", "ready", "in_delivery", "delivered"];
  const known = (value: string): boolean => value === "cancelled" || sequence.includes(value);
  if (!known(mirrorStatus)) return documentStatus;
  if (documentStatus === "cancelled" || mirrorStatus === "cancelled") return "cancelled";
  if (!known(documentStatus)) return documentStatus;
  return sequence.indexOf(mirrorStatus) > sequence.indexOf(documentStatus) ? mirrorStatus : documentStatus;
}

export async function handleStoreOrderStatus(env: Env, orderId: string): Promise<Response> {
  const order = await fsGet(env, `orders/${orderId}`).catch(() => null);
  if (!order) throw new HttpError(404, "order-not-found");
  // The Realtime Database mirror is written the instant the kitchen taps, by the
  // kitchen's own phone as well as by the relay below. Firestore is the record and
  // stays the answer wherever it has one — but a cancellation is written to the
  // mirror a beat earlier, and a browser tab left open on the tracking page is
  // exactly the place that must not miss it. Reading it is best effort: no mirror,
  // no problem.
  const mirror = await rtdbGet<Record<string, unknown>>(env, `orderLive/${orderId}`).catch(() => null);
  const raw = newerOrderStatus(str(order.status, "placed"), str(mirror?.status, ""));
  const mirrorNote = str(mirror?.vendorNote);
  const receipt = receiptLines(order.items);
  return json(
    {
      ok: true,
      order: {
        id: orderId,
        shortId: str(order.shortId),
        status: raw,
        note: str(order.note),
        cancelNote: str(order.cancelNote) || (raw === "cancelled" ? mirrorNote : ""),
        prepEndsAt: str(order.prepEndsAt),
        // The bill, row by row. A customer who ordered in this browser has no
        // app to open, so the tracking page prints the same receipt the app
        // prints: what was ordered, the ride, the coupon, the total. Nothing
        // identifying is added — no name, no phone, no address — so the page's
        // own promise ("the link shows the order, never your number or your
        // street") still holds, word for word.
        items: receipt.lines,
        // An order with more lines than a receipt shows says so, instead of
        // quietly dropping rows the customer is paying for.
        itemsOmitted: receipt.omitted,
        subtotalPaisa: num(order.subtotalAmountPaisa),
        deliveryPaisa: num(order.deliveryAmountPaisa),
        discountPaisa: num(order.discountAmountPaisa),
        couponCode: str(order.couponCode),
        distanceKm: num(order.distanceKm),
        createdAt: str(order.createdAt),
        totalPaisa: num(order.totalAmountPaisa),
        updatedAt: str(order.updatedAt),
      },
    },
    200,
    { "cache-control": "no-store" },
  );
}

/**
 * The item lines of an order document, reduced to what a receipt shows.
 *
 * The document is written by two callers — this Worker and the Android app — so
 * nothing here is trusted to be the right shape. A missing name or a negative
 * quantity produces a line that is simply left out, because a bill that is one
 * row short is a smaller problem than a page that throws.
 *
 * The cap is a page limit, not a truth limit: `omitted` carries how many real
 * rows were past it, and the receipt prints that count. A bill that stops at row
 * 40 without saying so is a bill the customer cannot check against what they are
 * being asked to pay.
 */
function receiptLines(value: unknown): {
  lines: Array<{ name: string; qty: number; unitPaisa: number; lineTotalPaisa: number }>;
  omitted: number;
} {
  if (!Array.isArray(value)) return { lines: [], omitted: 0 };
  const rows = value
    .map((entry) => {
      const line = (entry ?? {}) as Record<string, unknown>;
      const qty = Math.max(1, Math.trunc(num(line.qty, 1)));
      return {
        name: str(line.name).trim().slice(0, 120),
        qty,
        unitPaisa: Math.max(0, Math.trunc(num(line.unitAmountPaisa))),
        lineTotalPaisa: Math.max(0, Math.trunc(num(line.lineTotalAmountPaisa))),
      };
    })
    .filter((line) => line.name.length > 0);
  return {
    lines: rows.slice(0, MAX_RECEIPT_LINES),
    omitted: Math.max(0, rows.length - MAX_RECEIPT_LINES),
  };
}

/** A basket bigger than this is not a receipt any more. */
const MAX_RECEIPT_LINES = 40;

/** Helper for the pages: is this order id plausible before we spend a read? */
export function looksLikeOrderId(value: string): boolean {
  return /^[A-Za-z0-9]{12,64}$/.test(value);
}

/** The menu a worker isolate saw last, exposed for the diagnostics page. */
export async function menuAgeSeconds(env: Env): Promise<number> {
  // The same memo the storefront reads, so a diagnostics page costs no operation.
  const cached = (await memoGet<{ fetchedAt?: number }>(env.UNDAL_KV as KvBinding, "web:menu:v1", {
    ttlMs: MENU_MEMO_TTL_MS,
    json: true,
  })) as { fetchedAt?: number } | null;
  if (!cached?.fetchedAt) return -1;
  return Math.round((Date.now() - cached.fetchedAt) / 1000);
}
