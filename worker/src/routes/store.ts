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
import { rtdbSet } from "../rtdb";
import { HttpError, json, readJson } from "../util/http";
import { formatPaisa } from "../util/money";
import {
  normalizeBdMobile,
  normalizeCoupon,
  applicableDiscountPaisa,
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
  type CouponRule,
} from "../web/cart";
import { menuItem, toStoreConfig, webMenu } from "../web/store-data";

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

export async function handleStoreOrder(env: Env, request: Request): Promise<Response> {
  const claims = await import("../authverify").then((module) => module.verifyIdToken(env, bearerFrom(request)));
  const body = await readJson<OrderRequest>(request);

  // ---- who is ordering -----------------------------------------------------
  const customerName = str(body.customerName).trim().slice(0, MAX_NAME_LENGTH);
  if (!usableName(customerName)) {
    throw new HttpError(
      400,
      "name-required",
      "Please write the name the kitchen should call out — letters, at least three of them.",
    );
  }
  const phone = normalizeBdMobile(str(body.phone));
  if (!phone) {
    throw new HttpError(400, "phone-invalid", "Write a Bangladeshi mobile number like 01712345678.");
  }
  const lat = num(body.address?.lat, Number.NaN);
  const lng = num(body.address?.lng, Number.NaN);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat === 0 || lng === 0) {
    throw new HttpError(400, "location-required", "Tap the locate button so the rider knows where to go.");
  }
  const addressTitle = str(body.address?.title).trim().slice(0, 80);
  const addressDesc = str(body.address?.desc).trim().slice(0, 200);
  const addressNote = str(body.address?.note).trim().slice(0, 200);
  if (!usableText(addressTitle) || !usableText(addressDesc) || !usableText(addressNote)) {
    throw new HttpError(
      400,
      "address-incomplete",
      "Fill in the area, the house or road, and one landmark the rider can ask for.",
    );
  }

  // ---- what is in the basket ----------------------------------------------
  const { lines, issues } = validateCartLines(body.lines);
  if (issues.length > 0 || lines.length === 0) {
    throw new HttpError(400, "cart-invalid", issues[0]?.message ?? "Your cart is empty.");
  }

  // ---- live catalogue, live store -----------------------------------------
  const menu = await webMenu(env, { force: true });
  const store = menu.store ?? toStoreConfig(await fsGet(env, "config/store").catch(() => null));

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
  let couponUpdateTime: string | null = null;
  let couponUsedCount = 0;
  const requestedCode = normalizeCoupon(str(body.couponCode));
  if (requestedCode) {
    const coupon = await fsGet(env, `coupons/${requestedCode}`);
    if (!coupon) throw new HttpError(409, "coupon-missing", `Coupon ${requestedCode} no longer exists.`);
    const active = coupon.active === true;
    const type = str(coupon.type, "percent");
    const value = num(coupon.value);
    const usageLimit = num(coupon.usageLimit);
    const usedCount = num(coupon.usedCount);
    const minimum = num(coupon.minSubtotalAmountPaisa);
    const expiresAt = str(coupon.expiresAt);
    if (!active) throw new HttpError(409, "coupon-inactive", `Coupon ${requestedCode} is switched off.`);
    if (expiresAt && Date.parse(expiresAt) <= Date.now()) {
      throw new HttpError(409, "coupon-expired", `Coupon ${requestedCode} has expired.`);
    }
    if (usageLimit >= 1 && usedCount >= usageLimit) {
      throw new HttpError(409, "coupon-exhausted", `Coupon ${requestedCode} has been used its last time.`);
    }
    if (subtotalPaisa < minimum) {
      throw new HttpError(
        409,
        "coupon-minimum",
        `Coupon ${requestedCode} needs ${formatPaisa(minimum)} of food.`,
      );
    }
    const rule: CouponRule = { type: type === "fixed" ? "fixed" : "percent", value };
    discountPaisa = applicableDiscountPaisa(subtotalPaisa, rule);
    if (discountPaisa <= 0) {
      throw new HttpError(409, "coupon-no-effect", `Coupon ${requestedCode} does not change this total.`);
    }
    appliedCode = requestedCode;
    couponUpdateTime = str(coupon.updateTime).length > 0 ? str(coupon.updateTime) : null;
    couponUsedCount = usedCount;
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
    customerId: claims.sub,
    customerName,
    phone,
    channel: "web",
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
  if (appliedCode) orderFields.couponCode = appliedCode;
  const note = str(body.note).trim().slice(0, 300);
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
  await fsPatchWithServerTimestamps(env, `chats/${orderId}`, { customerId: claims.sub, orderId }, [
    "createdAt",
  ]).catch(() => undefined);
  await rtdbSet(env, `orderLive/${orderId}`, {
    customerId: claims.sub,
    status: "placed",
    updatedAt: Date.now(),
  }).catch(() => undefined);
  await rtdbSet(env, `chats/${orderId}`, { customerId: claims.sub, createdAt: Date.now() }).catch(
    () => undefined,
  );
  await alertKitchen(env, orderId, shortId, priced.length, totalPaisa).catch(() => undefined);

  const stored: StoredOrder = { id: orderId, shortId, totalPaisa, status: "placed" };
  return json(
    {
      ok: true,
      order: stored,
      lines: priced,
      deliveryPaisa,
      discountPaisa,
      subtotalPaisa,
      distanceKm: Math.round(distanceKm * 10) / 10,
      createdAt,
      trackingUrl: `https://${new URL(request.url).host}/order/${orderId}`,
    },
    201,
  );
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
    },
    200,
    { "cache-control": "public, max-age=60" },
  );
}

/** `GET /api/store/order/{id}` — status polling for the tracking page. */
export async function handleStoreOrderStatus(env: Env, orderId: string): Promise<Response> {
  const order = await fsGet(env, `orders/${orderId}`).catch(() => null);
  if (!order) throw new HttpError(404, "order-not-found");
  const raw = str(order.status, "placed");
  return json(
    {
      ok: true,
      order: {
        id: orderId,
        shortId: str(order.shortId),
        status: raw,
        note: str(order.note),
        cancelNote: str(order.cancelNote),
        prepEndsAt: str(order.prepEndsAt),
        totalPaisa: num(order.totalAmountPaisa),
        updatedAt: str(order.updatedAt),
      },
    },
    200,
    { "cache-control": "no-store" },
  );
}

/** Helper for the pages: is this order id plausible before we spend a read? */
export function looksLikeOrderId(value: string): boolean {
  return /^[A-Za-z0-9]{12,64}$/.test(value);
}

/** The menu a worker isolate saw last, exposed for the diagnostics page. */
export async function menuAgeSeconds(env: Env): Promise<number> {
  const cached = (await env.UNDAL_KV.get("web:menu:v1", "json").catch(() => null)) as { fetchedAt?: number } | null;
  if (!cached?.fetchedAt) return -1;
  return Math.round((Date.now() - cached.fetchedAt) / 1000);
}
