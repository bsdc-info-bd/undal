/**
 * HTTP vocabulary for the Undal Worker: typed errors, JSON helpers, security
 * headers and the memory-first edge rate limiter.
 */
import { memoGet, memoPut, type KvBinding } from "./kv";

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
  headers: Record<string, string> = {},
): Response {
  const body: Record<string, unknown> = { ok: false, error: code, message: message ?? code };
  if (extra?.hint) body.hint = extra.hint;
  if (extra?.detail) body.detail = extra.detail;
  return json(body, status, headers);
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
      // `img-src` is the one directive that has to name the places a dish photo can
      // come from, because a picture is the one thing on the menu that is not
      // served by this Worker. Cloudinary is where the kitchen's own uploads land;
      // the published catalog host is where the seeded dishes' photos live. When
      // neither answered, the CSP refused the image and the storefront showed an
      // empty box — which is what "the seed menu has no pictures" was.
      "default-src 'self'; img-src 'self' data: https://res.cloudinary.com https://*.cloudinary.com " +
      "https://tile.openstreetmap.org https://sylhetbites.pages.dev https://undal-menu.pages.dev; " +
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
 * Fixed-window rate limiter, memory-first with KV escalation.
 *
 * The version this replaced did one KV read **and one KV write on every request**
 * the Worker served — pages, pictures, health checks, all of it. On the free tier
 * that is 1,000 writes a day, so the site's whole daily budget was spent by the
 * thing whose job is to protect the site: about 500 requests later every KV write
 * fails, and the limiter starts answering from an empty counter forever.
 *
 * The fix keeps the guarantee and drops the cost:
 *
 *   · Counting happens in isolate memory. A caller below the escalation point
 *     costs **zero** KV operations, which is every honest customer, every
 *     crawler and every page view.
 *   · When one bucket reaches {@link ESCALATE_AT} of its limit, that bucket —
 *     and only that bucket — becomes fleet-wide: its count is published to KV and
 *     every later request takes the maximum of KV and its own memory, so a caller
 *     spread across fifty isolates is stopped by the total, not by fifty small
 *     numbers. The KV counter is re-read at most every two seconds and
 *     re-published every ten requests, which is close enough to stop a flood and
 *     a hundred times cheaper than counting it exactly.
 *   · KV writes for escalated buckets go through the gateway's per-minute brake,
 *     so an attack that makes a thousand buckets hot cannot spend the day's
 *     write allowance in an hour. A refused write leaves enforcement in memory:
 *     weaker by a factor of isolates, never absent, and never an error.
 *   · A KV outage degrades to memory counting. The limiter has never been the
 *     reason a customer could not order, and it does not become one now.
 */
const ESCALATE_AT = 0.6;
/**
 * While a bucket is escalated, its KV counter is re-read at most this often.
 * Reads are the cheap side of the ledger (100,000 a day against 1,000 writes),
 * and a caller making sixty requests a second does not need sixty answers to the
 * question "what does the rest of the fleet think?" — two seconds of agreement is
 * close enough to stop them and a thousand times cheaper.
 */
const ESCALATED_READ_TTL_MS = 2_000;
/** How often an escalated bucket publishes its count back to KV. */
const ESCALATED_WRITE_EVERY = 10;
/** Bound on tracked buckets, so a flood of unique keys cannot grow memory. */
const MAX_BUCKETS = 4_000;

interface WindowBucket {
  /** The window index this count belongs to. */
  window: number;
  count: number;
  /** This bucket has met KV, and is being counted there from now on. */
  escalated: boolean;
  /** Requests since this bucket last published its count. */
  sinceSync: number;
  /** Insertion order, for eviction. */
  seenAt: number;
}

const buckets = new Map<string, WindowBucket>();

function evictStaleBuckets(now: number, currentWindow: number): void {
  if (buckets.size <= MAX_BUCKETS) return;
  for (const [key, bucket] of buckets) {
    if (bucket.window !== currentWindow || now - bucket.seenAt > 120_000) buckets.delete(key);
    if (buckets.size <= MAX_BUCKETS) return;
  }
  // Everything tracked is current and busy: drop the oldest until it fits.
  const ordered = [...buckets.entries()].sort((left, right) => left[1].seenAt - right[1].seenAt);
  for (const [key] of ordered.slice(0, Math.max(1, ordered.length - MAX_BUCKETS))) buckets.delete(key);
}

export interface RateLimitDecision {
  allowed: boolean;
  /** Seconds until the current window closes — the honest `Retry-After`. */
  retryAfterSeconds: number;
  /** `memory` for a quiet caller, `kv` once the bucket is globally counted. */
  source: "memory" | "kv";
  /** Requests this window has already been allowed, as far as this Worker knows. */
  remaining: number;
}

/** The full decision, for callers that want to answer with a real `Retry-After`. */
export async function rateLimitDecision(
  env: { UNDAL_KV: KVNamespace },
  bucketKey: string,
  limit: number,
  windowSeconds: number,
): Promise<RateLimitDecision> {
  const now = Date.now();
  const window = Math.floor(now / 1000 / windowSeconds);
  const retryAfterSeconds = Math.max(1, windowSeconds - Math.floor((now / 1000) % windowSeconds));
  const key = `rl:${bucketKey}:${window}`;

  evictStaleBuckets(now, window);
  let bucket = buckets.get(key);
  if (!bucket || bucket.window !== window) {
    bucket = { window, count: 0, escalated: false, sinceSync: 0, seenAt: now };
    buckets.set(key, bucket);
  }
  bucket.seenAt = now;

  const escalateAt = Math.max(1, Math.floor(limit * ESCALATE_AT));
  const binding = env && env.UNDAL_KV ? (env.UNDAL_KV as KvBinding) : null;

  // The quiet path: an ordinary customer, a crawler, a page of icons. No KV at all.
  if (!bucket.escalated && (!binding || bucket.count < escalateAt)) {
    bucket.count += 1;
    return {
      allowed: bucket.count <= limit,
      retryAfterSeconds,
      source: "memory",
      remaining: Math.max(0, limit - bucket.count),
    };
  }

  // A busy caller: from here the count is the account-wide one, so a caller spread
  // across fifty isolates is stopped by the total and not by fifty small ones.
  const justEscalated = !bucket.escalated;
  bucket.escalated = true;
  bucket.sinceSync += 1;

  const stored = Number((await memoGet<string>(binding, key, { ttlMs: ESCALATED_READ_TTL_MS })) ?? "0");
  const known = Number.isFinite(stored) ? stored : 0;
  // The first escalated request carries this isolate's own count across, so the
  // global total does not restart from zero and let the caller through again.
  const total = Math.max(known, bucket.count) + 1;
  bucket.count = total;

  const publish = justEscalated || bucket.sinceSync >= ESCALATED_WRITE_EVERY || total > limit;
  if (publish) {
    bucket.sinceSync = 0;
    await memoPut(binding, key, String(total), { expirationTtl: windowSeconds * 2, memoTtlMs: 0 });
  }
  return {
    allowed: total <= limit,
    retryAfterSeconds,
    source: "kv",
    remaining: Math.max(0, limit - total),
  };
}

/** Fixed-window rate limiting. `true` when the request may proceed. */
export async function rateLimit(
  env: { UNDAL_KV: KVNamespace },
  bucketKey: string,
  limit: number,
  windowSeconds: number,
): Promise<boolean> {
  return (await rateLimitDecision(env, bucketKey, limit, windowSeconds)).allowed;
}

/** Tests: drop every in-memory window. Production never needs this. */
export function resetRateLimitMemory(): void {
  buckets.clear();
}

export function clientIp(request: Request): string {
  return request.headers.get("cf-connecting-ip") ?? request.headers.get("x-forwarded-for") ?? "unknown";
}
