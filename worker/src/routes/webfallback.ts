/**
 * The storefront without JavaScript (P-018 · the "it works on my phone" repair).
 *
 * Everything a customer does on the web — add to the basket, sign in, create an
 * account, order the food — is also a plain HTML form post that lands here. The
 * scripted path is nicer (instant basket, a map pin, no page reload), and it is
 * the path almost everybody takes; this file is what happens when the script
 * does not run at all, which is a **phone**, not an exotic edge case:
 *
 *   · an Android WebView older than the syntax in the script,
 *   · an in-app browser (Facebook, Messenger, a carrier portal) that ships its
 *     own engine and its own idea of what is supported,
 *   · something as ordinary as a script blocked by a browser setting.
 *
 * A single unsupported token once stopped the whole inline block from parsing,
 * so sign-in, registration, "continue as a guest" and add-to-basket all did
 * nothing on that phone while working perfectly on a tablet. These routes make
 * the page's behaviour independent of that: the same buttons, posted.
 *
 * What arrives here is never trusted: the basket is re-priced from live
 * Firestore data, the address is validated (and geocoded if the pin never made
 * it), the session is a real Firebase ID token, and every redirect target is a
 * path on this site.
 */
import type { Env } from "../env";
import { HttpError } from "../util/http";
import { identityToolkit, passwordProblem, sessionOf } from "./account";
import { createWebOrder, readOrderIdentity } from "./store";
import {
  applyCartChange,
  basketCookieHeader,
  clearedCookieHeader,
  cookieValue,
  readCookieCart,
  readCookieSession,
  readForm,
  redirectWithCookies,
  safeNext,
  sessionCookieHeader,
  BASKET_COOKIE,
  SESSION_COOKIE,
  type CookieSession,
} from "../web/forms";

const MAX_FIELD = 200;

function field(form: URLSearchParams, name: string, max = MAX_FIELD): string {
  return (form.get(name) ?? "").trim().slice(0, max);
}

// ------------------------------------------------------------------- basket

/**
 * A basket change as a form post: `POST /basket/add` with `id` and `qty`.
 *
 * The form is on every dish; with JavaScript it never leaves the page, and
 * without it this is what keeps the basket real. The response is a redirect back
 * to where the customer was, with `?added=<id>` so the next page can say what
 * happened.
 */
export async function handleBasketForm(
  request: Request,
  action: "add" | "set" | "remove" | "clear",
): Promise<Response> {
  const form = await readForm(request);
  const cart = readCookieCart(request);
  const id = field(form, "id", 128);
  const qty = Number(form.get("qty") ?? (action === "add" ? "1" : "0"));
  const next = safeNext(form.get("next"), action === "add" ? "/store" : "/basket");
  const updated = applyCartChange(cart, {
    action,
    id,
    qty: Number.isFinite(qty) ? qty : 1,
  });
  const location = action === "add" && id
    ? `${next}${next.includes("?") ? "&" : "?"}added=${encodeURIComponent(id)}`
    : next;
  return redirectWithCookies({
    location,
    cookies: [basketCookieHeader(updated), basketCookieHeader(updated)],
  });
}

// --------------------------------------------------------------------- login

interface TokenResponse {
  idToken?: string;
  refreshToken?: string;
  expiresIn?: string;
  localId?: string;
  email?: string;
}

function sessionCookieOf(payload: TokenResponse, fallbackEmail: string): { session: CookieSession; header: string } {
  const session = sessionOf(payload);
  const record: CookieSession = {
    idToken: session.idToken,
    refreshToken: session.refreshToken,
    uid: session.uid,
    email: session.email || fallbackEmail,
    expiresAt: Date.now() + session.expiresIn * 1000,
  };
  return { session: record, header: sessionCookieHeader(record) };
}

/**
 * `POST /login` — the sign-in tab, the create-an-account tab and the
 * forgotten-password tab, as three ordinary forms.
 *
 * Every outcome is a redirect: success to the account page with the session in
 * an HttpOnly cookie, failure back to the page that was submitted with the
 * sentence explaining it. A form that answers with a rendered error is a form
 * the customer can resubmit with a fix, which is the whole point.
 */
export async function handleAccountForm(env: Env, request: Request): Promise<Response> {
  const form = await readForm(request);
  const mode = field(form, "mode", 16);
  const next = safeNext(form.get("next"), "/account");

  if (mode === "signin") {
    const email = field(form, "email", 160).toLowerCase();
    const password = (form.get("password") ?? "").slice(0, 200);
    if (!email.includes("@") || password.length < 6) {
      return noticeRedirect("/login", "Enter the email address and password you use in the Undal app.", "error", "signin");
    }
    try {
      const payload = await identityToolkit<TokenResponse>(env, "accounts:signInWithPassword", {
        email,
        password,
        returnSecureToken: true,
      });
      const { header } = sessionCookieOf(payload, email);
      return redirectWithCookies({ location: next, cookies: [header] });
    } catch (error) {
      return noticeRedirect("/login", sentenceFor(error), "error", "signin", { email });
    }
  }

  if (mode === "signup") {
    const name = field(form, "name", 80);
    const phone = field(form, "phone", 24);
    const email = field(form, "email", 160).toLowerCase();
    const password = (form.get("password") ?? "").slice(0, 200);
    const confirm = (form.get("confirm") ?? "").slice(0, 200);
    const values = { name, phone, email };
    if (name.length < 3) {
      return noticeRedirect("/login", "Please write your full name — letters, three or more.", "error", "signup", values);
    }
    const passwordProblemText = passwordProblem(password);
    if (passwordProblemText) {
      return noticeRedirect("/login", passwordProblemText, "error", "signup", values);
    }
    if (password !== confirm) {
      return noticeRedirect("/login", "The two passwords do not match.", "error", "signup", values);
    }
    try {
      const payload = await identityToolkit<TokenResponse>(env, "accounts:signUp", {
        email,
        password,
        returnSecureToken: true,
      });
      const { session, header } = sessionCookieOf(payload, email);
      await identityToolkit<TokenResponse>(env, "accounts:update", {
        idToken: session.idToken,
        displayName: name,
      }).catch(() => ({} as TokenResponse));
      // The name and the number are enough to fill the checkout; the profile
      // document itself is written with the first pinned address, like the app.
      const profileHeader = encodeProfileHint(name, phone);
      return redirectWithCookies({ location: "/checkout", cookies: [header, profileHeader] });
    } catch (error) {
      return noticeRedirect("/login", sentenceFor(error), "error", "signup", values);
    }
  }

  if (mode === "reset") {
    const email = field(form, "email", 160).toLowerCase();
    if (!email.includes("@")) {
      return noticeRedirect("/login", "Write the email address on your Undal account.", "error", "reset", { email });
    }
    try {
      await identityToolkit<TokenResponse>(env, "accounts:sendOobCode", {
        requestType: "PASSWORD_RESET",
        email,
      });
      return noticeRedirect(
        "/login",
        "The reset link is on its way. It comes from Firebase and lasts about an hour.",
        "ok",
        "reset",
        { email },
      );
    } catch (error) {
      return noticeRedirect("/login", sentenceFor(error), "error", "reset", { email });
    }
  }

  if (mode === "signout") {
    return redirectWithCookies({
      location: "/login",
      cookies: [clearedCookieHeader(SESSION_COOKIE)],
    });
  }

  return noticeRedirect("/login", "That form did not say what it was for. Please try again.", "error", "signin");
}

/**
 * The sentence for whatever went wrong, never "HTTP 400".
 *
 * `identityToolkit` and the account routes already turn Firebase's codes into a
 * sentence a person can act on, so the job here is only to make sure something
 * a human wrote always comes out — never a status code, and never silence.
 */
function sentenceFor(error: unknown): string {
  if (error instanceof HttpError) {
    return error.hint ? `${error.message} ${error.hint}` : error.message;
  }
  return error instanceof Error ? error.message : "That did not go through. Please try again.";
}

function noticeRedirect(
  path: string,
  notice: string,
  kind: "error" | "ok",
  tab: string,
  values: Record<string, string> = {},
): Response {
  const query = new URLSearchParams({ notice: notice.slice(0, 240), kind, tab });
  for (const [key, value] of Object.entries(values)) {
    if (value) query.set(`v_${key}`, value.slice(0, 80));
  }
  return redirectWithCookies({ location: `${path}?${query.toString()}` });
}

/** A one-cookie hint so the checkout can prefill what the account already said. */
function encodeProfileHint(name: string, phone: string): string {
  const payload = JSON.stringify({ name, phone }).replace(/[^\x20-\x7E]/g, "");
  return `undal_profile_hint=${encodeURIComponent(payload)}; Path=/; Max-Age=3600; SameSite=Lax`;
}

// ----------------------------------------------------------------- checkout

/**
 * `POST /checkout/place` — the order, placed by a form.
 *
 * The fields are the ones on the page; the pin comes from the map when the
 * script is alive (`lat`/`lng`), and when it is not, the address text is
 * geocoded here with Nominatim so a customer on a phone with a broken script
 * still gets a rodeable delivery. The basket is the cookie basket — the only
 * basket that exists without a script — and the order is written by exactly the
 * same code that writes a scripted one.
 */
export async function handleCheckoutPlace(env: Env, request: Request): Promise<Response> {
  const form = await readForm(request);
  const cookieCart = readCookieCart(request);
  const lines = linesFrom(form, cookieCart.lines);

  if (lines.length === 0) {
    return noticeRedirect("/checkout", "Your basket is empty. Add something from the menu first.", "error", "checkout");
  }

  const session = await sessionFor(env, request).catch(() => null);
  if (!session) {
    return noticeRedirect(
      "/checkout",
      "Ordering on the web is switched off right now — the Undal app still takes orders, and so does a phone call.",
      "error",
      "checkout",
    );
  }

  let lat = Number(form.get("lat") ?? 0);
  let lng = Number(form.get("lng") ?? 0);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat === 0 || lng === 0) {
    const geocoded = await geocodeAddress([
      field(form, "title", 80),
      field(form, "desc", 200),
      field(form, "note", 200),
    ]);
    if (!geocoded) {
      return noticeRedirect(
        "/checkout",
        "We could not find that address on the map. Write the area and one well-known landmark, or open the page in a browser and drop the pin by hand.",
        "error",
        "checkout",
      );
    }
    lat = geocoded.lat;
    lng = geocoded.lng;
  }

  try {
    const identity = readOrderIdentity({
      customerName: form.get("name"),
      phone: form.get("phone"),
      lat,
      lng,
      addressTitle: form.get("title"),
      addressDesc: form.get("desc"),
      addressNote: form.get("note"),
    });
    const result = await createWebOrder(env, {
      ...identity,
      uid: session.uid,
      lines: lines.map((line) => ({ id: line.id, qty: line.qty, kind: line.kind })),
      couponCode: field(form, "coupon", 24),
      note: field(form, "cartnote", 300),
      source: "web-nojs",
    });
    return redirectWithCookies({
      location: `/order/${result.order.id}?new=1`,
      cookies: [clearedCookieHeader(BASKET_COOKIE), session.header],
    });
  } catch (error) {
    const message = error instanceof HttpError
      ? `${error.message}${error.hint ? ` ${error.hint}` : ""}`
      : error instanceof Error
        ? error.message
        : "The order did not go through.";
    return noticeRedirect("/checkout", message, "error", "checkout");
  }
}

/** The basket as posted by the scripted checkout, or the cookie basket without it. */
function linesFrom(
  form: URLSearchParams,
  fallback: Array<{ id: string; qty: number; kind: "dish" | "combo" }>,
): Array<{ id: string; qty: number; kind: "dish" | "combo" }> {
  const raw = form.get("lines");
  if (raw && raw.length <= 4000) {
    try {
      const parsed = JSON.parse(raw) as unknown;
      if (Array.isArray(parsed)) {
        const lines = parsed
          .map((entry) => entry as { id?: unknown; qty?: unknown; kind?: unknown })
          .filter((entry) => typeof entry.id === "string" && Number(entry.qty) >= 1)
          .map((entry) => ({
            id: String(entry.id).slice(0, 128),
            qty: Math.min(50, Math.trunc(Number(entry.qty))),
            kind: entry.kind === "combo" ? ("combo" as const) : ("dish" as const),
          }));
        if (lines.length > 0) return lines;
      }
    } catch {
      // A malformed `lines` field is not a reason to lose the order: the cookie
      // basket is the truth this route was built around.
    }
  }
  return fallback.map((line) => ({ id: line.id, qty: line.qty, kind: line.kind }));
}

interface SessionWithHeader {
  uid: string;
  header: string;
}

/**
 * The session a form post runs under: the cookie if there is one, otherwise a
 * fresh anonymous Firebase account for this browser, so that an order placed
 * with no JavaScript still belongs to *somebody* and appears in a tracking page
 * the customer can bookmark.
 */
async function sessionFor(env: Env, request: Request): Promise<SessionWithHeader | null> {
  const existing = readCookieSession(request);
  if (existing && existing.uid && existing.expiresAt > Date.now() + 60_000) {
    return { uid: existing.uid, header: sessionCookieHeader(existing) };
  }
  const payload = await identityToolkit<TokenResponse>(env, "accounts:signUp", { returnSecureToken: true });
  const { session, header } = sessionCookieOf(payload, "");
  return { uid: session.uid, header };
}

/**
 * A typed address to coordinates, through OpenStreetMap's Nominatim.
 *
 * The Worker identifies itself — `nominatim` refuses traffic from anonymous
 * scripts, and rightly so — and the search is bounded to Sylhet so that
 * "Zindabazar" does not resolve to a street in another country. A failure is not
 * fatal: the customer is asked for a better landmark instead of being told
 * something broke.
 */
async function geocodeAddress(
  parts: string[],
): Promise<{ lat: number; lng: number } | null> {
  const query = `${parts.filter((part) => part.length >= 2).join(", ")}, Sylhet, Bangladesh`;
  if (query.replace(/[^A-Za-z\u0980-\u09FF]/g, "").length < 4) return null;
  const url = `https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=bd&q=${encodeURIComponent(query)}`;
  try {
    const response = await fetch(url, {
      headers: {
        "user-agent": "UndalHomeKitchen/1.0 (web store; contact: support)",
        accept: "application/json",
      },
    });
    if (!response.ok) return null;
    const payload = (await response.json().catch(() => [])) as Array<{ lat?: string; lon?: string }>;
    const first = payload[0];
    const lat = Number(first?.lat);
    const lng = Number(first?.lon);
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat === 0 || lng === 0) return null;
    return { lat, lng };
  } catch {
    return null;
  }
}

/**
 * `GET /api/account/session` — the session a form post signed in as.
 *
 * The scripted pages keep their session in `sessionStorage`; a sign-in that
 * happened through a plain form keeps it in an HttpOnly cookie instead. This
 * endpoint is the bridge: when the scripted page finds no session of its own, it
 * asks here, and a customer who signed in on a phone with no JavaScript is still
 * signed in on a phone that has it.
 */
export async function handleCookieSession(request: Request): Promise<Response> {
  const session = readCookieSession(request);
  if (!session || session.expiresAt <= Date.now()) {
    return new Response(JSON.stringify({ ok: false, error: "no-session" }), {
      status: 401,
      headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
    });
  }
  return new Response(
    JSON.stringify({
      ok: true,
      idToken: session.idToken,
      refreshToken: session.refreshToken,
      expiresIn: Math.max(60, Math.round((session.expiresAt - Date.now()) / 1000)),
      uid: session.uid,
      email: session.email,
    }),
    { headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } },
  );
}

/**
 * The cookie names, exported so the pages and the tests can name the same
 * things without repeating a string literal.
 */
export const FORM_COOKIES = { basket: BASKET_COOKIE, session: SESSION_COOKIE };

/** Reads one cookie the pages may have set. Kept here for the page renderers. */
export function pageCookie(request: Request, name: string): string | undefined {
  return cookieValue(request, name);
}
