/**
 * The Cloudflare Pages Function that is the Undal customer website.
 *
 * One catch-all Function (`web/functions/[[path]].ts`) receives every request
 * Pages is not answering straight from a file, and this is what it does with it:
 *
 * 1. **A snapshot, when the visitor has no state.** An anonymous GET for a public
 *    page is answered from the HTML the build wrote, if that HTML is younger than
 *    `PRERENDER_MAX_AGE_SECONDS`. No rendering, no KV read, no Firestore query —
 *    one read from the Pages CDN in the same colo.
 * 2. **A live render, otherwise.** Any basket cookie, any session, any POST, any
 *    `/api/` call goes to `worker/src/index.ts` — the same router, the same pages,
 *    the same validation, running here instead of on the Worker. Nothing about the
 *    behaviour changed by moving; what changed is which product pays for it.
 * 3. **A snapshot again, as a last resort.** If the live render fails — Firestore
 *    refusing, the menu unreadable, a 500 out of nowhere — and a snapshot exists,
 *    the customer gets the menu from five minutes ago instead of an error. Stale
 *    food is a better answer than no food, and the header says it is stale.
 *
 * The Worker keeps every route it has always had, so the Android app, the crons,
 * the release webhook and anybody with an old `workers.dev` link are unaffected:
 * this file moves *traffic*, not behaviour.
 */
import worker from "../../worker/src/index";
import type { Env } from "../../worker/src/env";
import { errorJson, securityHeaders } from "../../worker/src/util/http";
import { pageVary } from "../../worker/src/util/page-cache";
import { BASKET_COOKIE, SESSION_COOKIE } from "../../worker/src/web/forms";
import {
  PRERENDER_MANIFEST_PATH,
  canonicalRedirect,
  isSnapshotPath,
  manifestAgeSeconds,
  maxAgeSeconds,
  normalisePath,
  snapshotUrl,
  type PrerenderManifest,
} from "./prerender";

/** The one binding Pages adds: the build output, served from the CDN. */
export interface PagesAssets {
  fetch(input: Request | string): Promise<Response>;
}

export interface PagesEnv extends Env {
  ASSETS?: PagesAssets;
  /** A Pages var; see `maxAgeSeconds`. */
  PRERENDER_MAX_AGE_SECONDS?: string;
}

/**
 * The shape Pages hands `onRequest`. Declared here rather than pulled from a
 * plugin package: the deployment should not need a dependency to describe the
 * four members it uses, and a structural type means the real context satisfies it
 * without an assertion at the boundary.
 */
export interface PagesContext {
  request: Request;
  env: PagesEnv;
  next: () => Promise<Response>;
  waitUntil: (promise: Promise<unknown>) => void;
  passThroughOnException: () => void;
}

/** `hit` = a snapshot answered; `stale` = a snapshot replaced a failure. */
export const PRERENDER_HEADER = "x-undal-prerender";

interface ManifestEntry {
  manifest: PrerenderManifest | null;
  memoizedAt: number;
  expiresAt: number;
}

/** One manifest per isolate, keyed by the binding the way `util/kv.ts` keys its memo. */
const manifestMemo = new WeakMap<object, ManifestEntry>();
const MANIFEST_TTL_MS = 60_000;
/** A missing manifest is worth another look sooner: a deploy may be landing. */
const MANIFEST_RETRY_MS = 15_000;

/** Entries memoized before this instant are ignored (see `resetPrerenderMemo`). */
let invalidatedBefore = 0;

/**
 * Drops the memo. A WeakMap cannot be cleared and cannot be replaced without
 * losing the entries a live isolate is still using, so entries are invalidated by
 * timestamp instead: anything memoized before the call is read again.
 */
export function resetPrerenderMemo(): void {
  invalidatedBefore = Date.now();
}

/**
 * Reads `prerender-manifest.json` out of the build output.
 *
 * Returns null when there is no manifest — a deployment of an older build, a
 * local `wrangler pages dev` against an unbuilt directory — and null is the safe
 * answer: every request is then rendered, which is exactly what happened before
 * this file existed.
 */
async function loadManifest(assets: PagesAssets, requestUrl: URL, nowMs: number): Promise<PrerenderManifest | null> {
  const memo = manifestMemo.get(assets);
  if (memo && memo.expiresAt > nowMs && memo.memoizedAt >= invalidatedBefore) return memo.manifest;
  let manifest: PrerenderManifest | null = null;
  try {
    const response = await assets.fetch(new Request(new URL(PRERENDER_MANIFEST_PATH, requestUrl).toString()));
    if (response.status === 200) {
      const parsed = JSON.parse(await response.text()) as Partial<PrerenderManifest>;
      // A manifest that does not describe itself is worse than none: it would
      // send the Function looking for files that were never written.
      if (parsed && parsed.version === 1 && Array.isArray(parsed.origins) && parsed.pages && typeof parsed.builtAt === "string") {
        manifest = parsed as PrerenderManifest;
      }
    }
  } catch {
    manifest = null;
  }
  manifestMemo.set(assets, {
    manifest,
    memoizedAt: nowMs,
    expiresAt: nowMs + (manifest ? MANIFEST_TTL_MS : MANIFEST_RETRY_MS),
  });
  return manifest;
}

/** The security headers a rendered page carries, so a file carries them too. */
function withPrerenderHeaders(
  response: Response,
  state: "hit" | "stale",
  manifest: PrerenderManifest,
  ageSeconds: number,
): Response {
  const headers = new Headers(response.headers);
  for (const [name, value] of Object.entries(securityHeaders())) headers.set(name, value);
  headers.set(PRERENDER_HEADER, state);
  headers.set("x-undal-prerendered-at", manifest.builtAt);
  headers.set("x-undal-prerender-age", String(Math.round(ageSeconds)));
  if (state === "stale") {
    // A snapshot standing in for a failure is served, but not for long: the
    // failure may already be over, and the CDN should come back and ask.
    headers.set("cache-control", "public, max-age=30, stale-if-error=300");
  }
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

/** The Function's answer to one request. */
export async function servePagesRequest(context: PagesContext): Promise<Response> {
  const { request, env } = context;
  const assets = env.ASSETS;
  const url = new URL(request.url);
  const path = normalisePath(url.pathname);
  // One menu, one URL: `/store/`, `//store` and `/store/index.html` are all the
  // page at `/store`, and a crawler that finds three of them splits what it knows
  // about the menu across three addresses.
  if (request.method === "GET" || request.method === "HEAD") {
    const canonical = canonicalRedirect(url);
    if (canonical) {
      return new Response(null, {
        status: 301,
        headers: { location: canonical, "cache-control": "public, max-age=86400", ...securityHeaders() },
      });
    }
  }

  const snapshotPath = isSnapshotPath(path);
  // A snapshot is one rendering of one URL, and the query string is part of the
  // URL. `/store?q=bhaji` is a different page from `/store`: answering it from the
  // unfiltered file is a search that silently does nothing, which is worse than a
  // slow search, because the customer asks for one dish, is handed the whole menu,
  // and has no way to know they were ignored. The same is true of a category chip
  // and of a sort order. So a file answers only a request with no query at all, and
  // a queried page is rendered by the router that knows what the query means.
  const snapshotEligible = snapshotPath && url.search === "";
  // No cookies at all: `pageVary` returns null for a session, an Authorization
  // header or any cookie it does not recognise, and the basket cookie's value for
  // a basket — every one of which means the markup is this visitor's own.
  const anonymous = pageVary(request, [BASKET_COOKIE, SESSION_COOKIE]) === "";

  let snapshot: string | null = null;
  let manifest: PrerenderManifest | null = null;
  let ageSeconds = 0;

  if (assets && snapshotEligible && request.method === "GET" && anonymous) {
    // If this Function throws instead of answering, Pages serves the matching file
    // from the build output. For a prerendered path there is one, so the last line
    // of defence is the same HTML the first line would have served.
    try {
      context.passThroughOnException();
    } catch {
      // Not every runtime that runs this code offers it; the render below is the
      // fallback in that case, and it always has been.
    }
    const nowMs = Date.now();
    manifest = await loadManifest(assets, url, nowMs);
    if (manifest) {
      ageSeconds = manifestAgeSeconds(manifest, nowMs);
      snapshot = snapshotUrl(manifest, url.origin, path);
      if (snapshot && ageSeconds <= maxAgeSeconds(env)) {
        const asset = await fetchSnapshot(assets, url, snapshot);
        if (asset) return withPrerenderHeaders(asset, "hit", manifest, ageSeconds);
      }
    }
  }

  let response: Response;
  try {
    response = await worker.fetch(request, env, executionContext(context));
  } catch (error) {
    // `worker.fetch` catches its own failures and answers JSON; this is the case
    // where a binding itself is missing or the runtime refused the request.
    console.error("undal-pages render failure", error);
    response = errorJson(500, "internal-error", "The Undal web could not answer this request.");
  }

  if (response.status >= 500 && assets && snapshot && manifest) {
    const asset = await fetchSnapshot(assets, url, snapshot);
    if (asset) return withPrerenderHeaders(asset, "stale", manifest, manifestAgeSeconds(manifest, Date.now()));
  }

  if (snapshotPath) {
    const headers = new Headers(response.headers);
    // "bypass" covers both reasons a file was not used: this visitor is not
    // anonymous, and this URL was never a file to begin with. Either way the page
    // in front of them was rendered for them alone.
    const state = snapshotEligible && anonymous ? (snapshot ? "miss" : "unavailable") : "bypass";
    headers.set(PRERENDER_HEADER, state);
    return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
  }
  return response;
}

/** Reads one file out of the build output, or null when it is not there. */
async function fetchSnapshot(assets: PagesAssets, url: URL, snapshot: string): Promise<Response | null> {
  try {
    const asset = await assets.fetch(new Request(new URL(snapshot, url).toString()));
    return asset.status === 200 ? asset : null;
  } catch {
    return null;
  }
}

/**
 * The worker's `fetch` wants an `ExecutionContext`; Pages gives a context with the
 * same two members plus routing. This is the adapter, rather than an assertion, so
 * a future member of either type is a compile error here instead of a runtime
 * surprise in production.
 */
function executionContext(context: PagesContext): ExecutionContext {
  return {
    waitUntil: (promise: Promise<unknown>) => context.waitUntil(promise),
    passThroughOnException: () => context.passThroughOnException(),
  } as ExecutionContext;
}
