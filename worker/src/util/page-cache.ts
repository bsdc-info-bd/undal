/**
 * The edge page cache — Cloudflare's own Cache API, in front of the rendered HTML.
 *
 * A storefront page is expensive in a way that is easy to miss: even with the menu
 * snapshot memoised, serving it means a KV or Firestore read, a JSON parse, a
 * thousand string concatenations and a response nobody else can reuse. On the free
 * plan that is CPU time and operations spent rendering the *same* menu for the
 * two hundred people who opened the site in the same minute.
 *
 * Cloudflare's CDN does not cache HTML on a free plan, but `caches.default` — the
 * Cache API, the same storage a service worker uses — is available inside the
 * Worker and inside a Pages Function, is per-datacentre, costs nothing per hit and
 * serves a page without running the renderer at all. This module puts it in front
 * of the pages that are safe to share:
 *
 *   · **Public pages only.** The caller decides; the basket, the checkout, the
 *     account, the order status and every `/api/` route are never passed here.
 *   · **Identity is part of the key or a reason to bypass.** A signed-in session
 *     bypasses the cache outright. The no-script basket cookie is rendered into
 *     the page, so it becomes part of the cache key: two customers with the same
 *     basket share an entry, and nobody ever sees anybody else's.
 *   · **Fresh, then stale-while-revalidate.** A hit inside its lifetime is served
 *     as it is. A hit past it but inside the stale window is served immediately
 *     *and* refreshed in the background, so the customer who arrives at the
 *     second boundary waits for nothing and the next one gets the new page.
 *   · **Nothing with a `Set-Cookie`, a non-200 status or `no-store` is stored.**
 *   · **No Cache API, no problem.** Outside a Worker — in the test suite, in the
 *     local preview — this renders directly and says so in `x-undal-edge`.
 *
 * Every response carries `x-undal-edge`: `hit`, `stale`, `miss`, `bypass` or
 * `unavailable`. A cache whose behaviour cannot be observed from the outside is a
 * cache nobody dares turn on.
 */

export const EDGE_HEADER = "x-undal-edge";
/** The moment an entry was stored, so its age can be judged on the way back out. */
const CACHED_AT_HEADER = "x-undal-cached-at";

export interface EdgePageOptions {
  request: Request;
  /** How long an entry is served as fresh. */
  ttlSeconds?: number;
  /** How long past that it may still be served while a refresh runs. */
  staleSeconds?: number;
  /**
   * Material the page depends on that the URL does not carry — the basket cookie,
   * a preview flag. Two requests whose `vary` differs never share an entry.
   */
  vary?: string;
  /** Skips the cache entirely when true: a signed-in caller, a POST, a bot test. */
  bypass?: boolean;
  /** Where a background refresh is allowed to run. Without it, stale is a miss. */
  ctx?: { waitUntil(promise: Promise<unknown>): void };
  render: () => Promise<Response>;
}

/** A small, dependency-free fingerprint. Not a security boundary — a cache key. */
export function fingerprint(value: string): string {
  let hash = 0x811c9dc5;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = (hash * 0x01000193) >>> 0;
  }
  return hash.toString(36);
}

function defaultCache(): Cache | null {
  const holder = (globalThis as { caches?: { default?: Cache } }).caches;
  return holder && holder.default ? holder.default : null;
}

function keyed(request: Request, vary: string): Request {
  if (!vary) return request;
  const url = new URL(request.url);
  url.searchParams.set("_uk", fingerprint(vary));
  return new Request(url.toString(), { method: "GET", headers: { accept: "text/html" } });
}

function withHeader(response: Response, name: string, value: string): Response {
  const headers = new Headers(response.headers);
  headers.set(name, value);
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

function storable(response: Response): boolean {
  if (response.status !== 200) return false;
  if (response.headers.get("set-cookie")) return false;
  const control = response.headers.get("cache-control") ?? "";
  if (/no-store|private/i.test(control)) return false;
  return true;
}

function ageSeconds(response: Response, now: number): number {
  const stored = Number(response.headers.get(CACHED_AT_HEADER) ?? "0");
  if (!Number.isFinite(stored) || stored <= 0) return 0;
  return Math.max(0, Math.round((now - stored) / 1000));
}

/** Strips the bookkeeping header before a cached page is shown to a customer. */
function present(response: Response, state: string): Response {
  const headers = new Headers(response.headers);
  headers.delete(CACHED_AT_HEADER);
  headers.set(EDGE_HEADER, state);
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

/**
 * Serves `render()` through the edge cache.
 *
 * A cache failure is never a page failure: if `match` or `put` throws, the page is
 * rendered and served, and the header says `bypass`.
 */
export async function edgePage(options: EdgePageOptions): Promise<Response> {
  const ttl = options.ttlSeconds ?? 60;
  const stale = options.staleSeconds ?? 600;
  const request = options.request;

  if (options.bypass || request.method !== "GET") {
    return withHeader(await options.render(), EDGE_HEADER, "bypass");
  }
  const cache = defaultCache();
  if (!cache) {
    return withHeader(await options.render(), EDGE_HEADER, "unavailable");
  }

  const key = keyed(request, options.vary ?? "");
  const now = Date.now();

  let entry: Response | null = null;
  try {
    entry = (await cache.match(key)) ?? null;
  } catch {
    entry = null;
  }

  if (entry) {
    const age = ageSeconds(entry, now);
    if (age <= ttl) return present(entry, "hit");
    if (age <= ttl + stale) {
      // Serve the page that exists, and fix it for the next caller in parallel.
      if (options.ctx) {
        options.ctx.waitUntil(refresh(cache, key, options.render()).catch(() => undefined));
      }
      return present(entry, "stale");
    }
  }

  const rendered = await options.render();
  if (storable(rendered)) {
    // Clone first: a Response body can only be consumed once, and the customer's
    // copy must not wait on the cache write.
    const copy = rendered.clone();
    try {
      await cache.put(key, withHeader(copy, CACHED_AT_HEADER, String(now)));
    } catch {
      // A full or refused cache is a slower site, not a broken one.
    }
  }
  return withHeader(rendered, EDGE_HEADER, "miss");
}

async function refresh(cache: Cache, key: Request, rendering: Promise<Response>): Promise<void> {
  const rendered = await rendering;
  if (!storable(rendered)) return;
  await cache.put(key, withHeader(rendered, CACHED_AT_HEADER, String(Date.now())));
}

/**
 * The material a public page's rendering depends on beyond its URL.
 *
 * Returns `null` when the request carries something that makes it unshareable —
 * a session cookie, an Authorization header — and the caller must bypass.
 */
export function pageVary(request: Request, cookieNames: string[]): string | null {
  if (request.headers.get("authorization")) return null;
  const header = request.headers.get("cookie") ?? "";
  if (!header) return "";
  const parts = header.split(";");
  const used: string[] = [];
  for (const name of cookieNames) {
    for (const part of parts) {
      const trimmed = part.trim();
      if (trimmed.indexOf(`${name}=`) === 0) used.push(trimmed);
    }
  }
  // Anything else on the cookie header could be a session we do not know about,
  // so a request that carries more than the listed cookies is never shared.
  const others = parts.filter((part) => {
    const trimmed = part.trim();
    if (!trimmed) return false;
    return !cookieNames.some((name) => trimmed.indexOf(`${name}=`) === 0);
  });
  if (others.length > 0) return null;
  return used.join("|");
}
