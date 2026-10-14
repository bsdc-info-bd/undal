/**
 * HTTP vocabulary for the Undal Worker: typed errors, JSON helpers, security
 * headers and the KV-backed edge rate limiter.
 */

/**
 * A failure with a repair instruction attached.
 *
 * `hint` exists because "HTTP 502" is not an answer a merchant can act on. Every
 * error this Worker raises about somebody else's infrastructure — Firestore, the
 * Realtime Database, FCM, Firebase Auth — carries the sentence that says what to
 * do next, and `errorJson` puts it in the body where a client can show it.
 */
export class HttpError extends Error {
  readonly status: number;
  readonly code: string;
  /** What the caller should do about it. Shown to the human, never swallowed. */
  readonly hint?: string;
  /** Machine-readable upstream detail (status, Google error code, index URL). */
  readonly detail?: Record<string, unknown>;

  constructor(
    status: number,
    code: string,
    message?: string,
    extra?: { hint?: string; detail?: Record<string, unknown> },
  ) {
    super(message ?? code);
    this.status = status;
    this.code = code;
    this.hint = extra?.hint;
    this.detail = extra?.detail;
  }
}

export const jsonHeaders: Record<string, string> = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

export function json(data: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...jsonHeaders, ...extraHeaders },
  });
}

export function errorJson(
  status: number,
  code: string,
  message?: string,
  extra?: { hint?: string; detail?: Record<string, unknown> },
): Response {
  const body: Record<string, unknown> = { ok: false, error: code, message: message ?? code };
  if (extra?.hint) body.hint = extra.hint;
  if (extra?.detail) body.detail = extra.detail;
  return json(body, status);
}

/** Security headers applied to every HTML page the Worker serves. */
export function securityHeaders(extra: Record<string, string> = {}): Record<string, string> {
  return {
    "x-content-type-options": "nosniff",
    "x-frame-options": "SAMEORIGIN",
    "referrer-policy": "strict-origin-when-cross-origin",
    "permissions-policy": "geolocation=(self), camera=(), microphone=()",
    "cross-origin-opener-policy": "same-origin",
    "content-security-policy":
      "default-src 'self'; img-src 'self' data: https://res.cloudinary.com https://tile.openstreetmap.org; " +
      "style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self' " +
      "https://firestore.googleapis.com https://identitytoolkit.googleapis.com " +
      "https://securetoken.googleapis.com https://nominatim.openstreetmap.org " +
      "wss://*.firebaseio.com https://*.firebaseio.com; " +
      "frame-ancestors 'self'; base-uri 'none'; form-action 'self'", 
    ...extra,
  };
}

export function html(body: string, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "public, max-age=60",
      ...securityHeaders(extra),
    },
  });
}

export function text(body: string, status = 200, contentType = "text/plain; charset=utf-8"): Response {
  return new Response(body, {
    status,
    headers: { "content-type": contentType, "cache-control": "no-store" },
  });
}

export function redirect(location: string, status = 302): Response {
  return new Response(null, { status, headers: { location, "cache-control": "no-store" } });
}

/** Escapes untrusted text before it is interpolated into HTML. */
export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** Reads a bearer token from the Authorization header. */
export function bearerToken(request: Request): string {
  const header = request.headers.get("authorization") ?? "";
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  if (!match?.[1]) throw new HttpError(401, "missing-bearer-token");
  return match[1];
}

export async function readJson<T>(request: Request): Promise<T> {
  const raw = await request.text();
  if (raw.length === 0) return {} as T;
  try {
    return JSON.parse(raw) as T;
  } catch {
    throw new HttpError(400, "invalid-json-body");
  }
}

/**
 * Fixed-window rate limiter backed by KV. Free-tier friendly: one key per
 * caller per window, expiring on its own.
 */
export async function rateLimit(
  env: { UNDAL_KV: KVNamespace },
  bucketKey: string,
  limit: number,
  windowSeconds: number,
): Promise<boolean> {
  const window = Math.floor(Date.now() / 1000 / windowSeconds);
  const key = `rl:${bucketKey}:${window}`;
  const current = Number((await env.UNDAL_KV.get(key)) ?? "0");
  if (Number.isFinite(current) && current >= limit) return false;
  await env.UNDAL_KV.put(key, String(current + 1), { expirationTtl: windowSeconds * 2 });
  return true;
}

export function clientIp(request: Request): string {
  return request.headers.get("cf-connecting-ip") ?? request.headers.get("x-forwarded-for") ?? "unknown";
}
