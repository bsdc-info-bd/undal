/**
 * The no-JavaScript layer: cookies, form posts and the pages that survive a
 * broken script.
 *
 * Every storefront control is a real HTML form first and a JavaScript control
 * second. With the script alive the form never leaves the page (the cart lives
 * in `localStorage` and answers instantly); with the script dead — an old
 * WebView, an in-app browser, a phone where a single unsupported token stopped
 * the whole block from parsing — the very same form posts here, the Worker keeps
 * the basket in a cookie, and the customer orders exactly as before.
 *
 * Two cookies, both `HttpOnly`, both `SameSite=Lax`, both pointless to a thief:
 *
 *   · `undal_basket` — dish ids and quantities, with a revision number. The
 *     server prices every order from live data, so editing this cookie can only
 *     change *what* somebody asks for, never what they pay.
 *   · `undal_session` — the ID token of whoever signed in through the plain
 *     form. The JavaScript path keeps its session in `sessionStorage` instead;
 *     this one exists so that signing in works when the script does not.
 *
 * Nothing here trusts a client: the cookie basket is re-priced at checkout, the
 * cookie session is verified against Firebase, and a form target is only ever
 * followed when it is a path on this site (`/…`, never `//evil.example`).
 */
import { escapeHtml } from "../util/http";

export const BASKET_COOKIE = "undal_basket";
export const SESSION_COOKIE = "undal_session";

/** A basket line, exactly the shape `validateCartLines` expects. */
export interface CookieCartLine {
  id: string;
  qty: number;
  kind: "dish" | "combo";
}

export interface CookieCart {
  rev: number;
  lines: CookieCartLine[];
}

export interface CookieSession {
  idToken: string;
  refreshToken: string;
  uid: string;
  email: string;
  /** Epoch millis at which the ID token stops being usable. */
  expiresAt: number;
}

const MAX_COOKIE_LINES = 40;
const MAX_COOKIE_QTY = 50;

// ------------------------------------------------------------------ cookies

function encode(value: unknown): string {
  const json = JSON.stringify(value);
  // base64url: cookie-safe, and it never contains a `;` or a `,`.
  const bytes = new TextEncoder().encode(json);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function decode<T>(raw: string | undefined): T | null {
  if (!raw) return null;
  try {
    const padded = raw.replace(/-/g, "+").replace(/_/g, "/");
    const binary = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4));
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
    return JSON.parse(new TextDecoder().decode(bytes)) as T;
  } catch {
    return null;
  }
}

export function cookieValue(request: Request, name: string): string | undefined {
  const header = request.headers.get("cookie") ?? "";
  for (const part of header.split(";")) {
    const trimmed = part.trim();
    if (trimmed.startsWith(`${name}=`)) return trimmed.slice(name.length + 1);
  }
  return undefined;
}

function cookieHeader(name: string, value: string, maxAgeSeconds: number): string {
  return `${name}=${value}; Path=/; Max-Age=${maxAgeSeconds}; HttpOnly; Secure; SameSite=Lax`;
}

export function clearedCookieHeader(name: string): string {
  return cookieHeader(name, "", 0);
}

/** The basket as the browser last left it, or an empty one. */
export function readCookieCart(request: Request): CookieCart {
  const parsed = decode<CookieCart>(cookieValue(request, BASKET_COOKIE));
  if (!parsed || !Array.isArray(parsed.lines)) return { rev: 0, lines: [] };
  const lines: CookieCartLine[] = [];
  for (const line of parsed.lines.slice(0, MAX_COOKIE_LINES)) {
    const id = typeof line?.id === "string" ? line.id.slice(0, 128) : "";
    const qty = Number(line?.qty);
    if (!id || !Number.isInteger(qty) || qty < 1) continue;
    lines.push({ id, qty: Math.min(MAX_COOKIE_QTY, qty), kind: line.kind === "combo" ? "combo" : "dish" });
  }
  return { rev: Number.isFinite(Number(parsed.rev)) ? Math.max(0, Math.trunc(Number(parsed.rev))) : 0, lines };
}

export function basketCookieHeader(cart: CookieCart): string {
  return cookieHeader(BASKET_COOKIE, encode(cart), 30 * 24 * 60 * 60);
}

export function readCookieSession(request: Request): CookieSession | null {
  const parsed = decode<CookieSession>(cookieValue(request, SESSION_COOKIE));
  if (!parsed || typeof parsed.idToken !== "string" || parsed.idToken.length < 20) return null;
  return {
    idToken: parsed.idToken,
    refreshToken: typeof parsed.refreshToken === "string" ? parsed.refreshToken : "",
    uid: typeof parsed.uid === "string" ? parsed.uid : "",
    email: typeof parsed.email === "string" ? parsed.email : "",
    expiresAt: Number.isFinite(Number(parsed.expiresAt)) ? Number(parsed.expiresAt) : 0,
  };
}

export function sessionCookieHeader(session: CookieSession): string {
  return cookieHeader(SESSION_COOKIE, encode(session), 7 * 24 * 60 * 60);
}

// -------------------------------------------------------------------- forms

/** Reads `application/x-www-form-urlencoded` (or multipart) without a framework. */
export async function readForm(request: Request): Promise<URLSearchParams> {
  const text = await request.text().catch(() => "");
  return new URLSearchParams(text);
}

/**
 * The only redirect targets this Worker will follow. `/store`, `/?section=rice`
 * and `/basket` are fine; `//evil.example` and `https://evil.example` are not,
 * because a form is a place a stranger can point a customer's browser.
 */
export function safeNext(raw: string | null | undefined, fallback = "/store"): string {
  const value = (raw ?? "").trim();
  if (!value.startsWith("/") || value.startsWith("//")) return fallback;
  if (value.includes("\\") || value.includes("\n") || value.includes("\r")) return fallback;
  return value.slice(0, 200);
}

export interface RedirectOptions {
  location: string;
  /** Cookies to set on the way out, already formatted. */
  cookies?: string[];
  status?: 303 | 307;
}

/** A redirect that carries the cookies the next page needs. */
export function redirectWithCookies(options: RedirectOptions): Response {
  const headers = new Headers({
    location: options.location,
    "cache-control": "no-store",
  });
  const cookies = options.cookies ?? [];
  if (cookies.length === 1) headers.set("set-cookie", cookies[0]);
  else if (cookies.length > 1) headers.set("set-cookie", cookies.join(", "));
  return new Response(null, { status: options.status ?? 303, headers });
}

/** Applies one basket change to the cookie cart and returns the new state. */
export function applyCartChange(
  cart: CookieCart,
  change: { action: "add" | "set" | "remove" | "clear"; id?: string; qty?: number },
): CookieCart {
  const id = (change.id ?? "").slice(0, 128);
  const qty = Math.max(0, Math.min(MAX_COOKIE_QTY, Math.trunc(Number(change.qty ?? 0))));
  const lines = cart.lines.slice();
  const at = lines.findIndex((line) => line.id === id);

  if (change.action === "clear") return { rev: cart.rev + 1, lines: [] };
  if (!id) return cart;

  if (change.action === "remove") {
    return { rev: cart.rev + 1, lines: lines.filter((line) => line.id !== id) };
  }
  if (change.action === "set") {
    if (at >= 0 && qty <= 0) lines.splice(at, 1);
    else if (at >= 0) lines[at] = { ...lines[at], qty };
    else if (qty > 0) lines.push({ id, qty, kind: "dish" });
    return { rev: cart.rev + 1, lines: lines.slice(0, MAX_COOKIE_LINES) };
  }
  // add
  const wanted = Math.min(MAX_COOKIE_QTY, (at >= 0 ? lines[at].qty : 0) + Math.max(1, qty || 1));
  if (at >= 0) lines[at] = { ...lines[at], qty: wanted };
  else if (lines.length < MAX_COOKIE_LINES) lines.push({ id, qty: wanted, kind: "dish" });
  return { rev: cart.rev + 1, lines };
}

/**
 * The name of whoever is signed in, for the header of a page they just posted
 * to. Only ever used inside an HTML escape.
 */
export function sessionGreeting(session: CookieSession | null): string {
  if (!session) return "";
  return session.email ? escapeHtml(session.email) : "your account";
}
