/**
 * What the Pages build writes to disk before anybody asks for it — and the rules
 * the Pages Function uses to decide whether a request may be answered from it.
 *
 * The customer website runs on Cloudflare Pages. Pages serves files from its own
 * CDN and charges nothing for them: no CPU, no KV read, no Firestore query, no
 * Worker request. The storefront is not a file, though — it is rendered from the
 * menu — so the build renders it once, per path, and writes the HTML down. A
 * request that can be answered from one of those files is answered from one; a
 * request that cannot is rendered, by exactly the code that has always rendered
 * it, in a Pages Function.
 *
 * The line between the two is drawn by one question: **does this visitor's own
 * state appear in the markup?** The basket badge, the basket bar, the signed-in
 * account header and an order's live status all do, and they are all read from
 * cookies the browser sends. So a snapshot is served only to a request with no
 * cookies at all — the first visit, a crawler, a shared link — and the moment a
 * customer adds a dish every later request is rendered live. That is also why the
 * snapshot is not a cache in the usual sense: it is a *cold-start* answer, kept
 * deliberately short-lived (`PRERENDER_MAX_AGE_SECONDS`, five minutes by default)
 * so a price change reaches an anonymous visitor within five minutes of a deploy
 * and within seconds of anybody at all asking, because the live render goes
 * through the Cache API in `util/page-cache.ts`.
 *
 * Snapshots are per origin. The canonical URL, the Open Graph tags and the
 * sitemap are all built from the request's origin, so a snapshot made for
 * `undal.pages.dev` and served on a custom domain would tell Google to index the
 * wrong host. The first origin is written at the root of the output directory —
 * which keeps `wrangler pages dev` and any plain static host working — and each
 * further origin under `_snapshots/<n>`.
 */

/** The manifest is a file in the build output, read once per isolate. */
export const PRERENDER_MANIFEST_PATH = "/prerender-manifest.json";

/** Where the second and later origins' snapshots live. */
export const SNAPSHOT_FOLDER = "_snapshots";

/**
 * How long a snapshot may answer a request before it is treated as a last resort
 * rather than a first answer. Five minutes: long enough that a burst of crawlers
 * and first-time visitors costs no rendering at all, short enough that a dish
 * the kitchen has just sold out of is not on the page for an hour.
 */
export const DEFAULT_PRERENDER_MAX_AGE_SECONDS = 300;

/** The pages whose rendering is the same for every anonymous visitor. */
export const SNAPSHOT_PAGE_PATHS = ["/", "/store", "/about", "/download"] as const;

/**
 * Files that are not pages but are still worth writing down: the two documents a
 * crawler reads first, and the artwork every page asks for. They are excluded
 * from the Function entirely in `_routes.json`, so they cost no invocation.
 */
export const SNAPSHOT_ASSET_PATHS = [
  "/robots.txt",
  "/sitemap.xml",
  "/favicon.svg",
  "/manifest.webmanifest",
  "/sw.js",
  "/apple-touch-icon.png",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
  "/icons/icon-maskable-512.png",
] as const;

/** Paths a browser may ask for that must never reach the Function. */
export const FUNCTION_EXCLUDED_PATHS = [
  "/icons/*",
  "/favicon.svg",
  "/apple-touch-icon.png",
  "/manifest.webmanifest",
  "/sw.js",
  "/prerender-manifest.json",
] as const;

/** What the build writes and the Function reads. */
export interface PrerenderManifest {
  version: 1;
  /** ISO timestamp of the build. The snapshot's age is measured from here. */
  builtAt: string;
  /**
   * `live` — the build read the kitchen's real menu. `sample` — it rendered
   * against the same fake backend the test suite uses, because no Firebase
   * credential was available; `deploy-pages` refuses to publish those, and a
   * local build says so on every page it wrote.
   */
  source: "live" | "sample";
  /** Every origin these snapshots were rendered for, in the order they were built. */
  origins: string[];
  /** Request path → snapshot file, relative to that origin's folder. */
  pages: Record<string, string>;
  /** How many dish pages are in `pages`; a build log line, and a sanity check. */
  items: number;
}

/** The router's own normalisation, so the two cannot disagree about a path. */
export function normalisePath(pathname: string): string {
  return pathname.replace(/\/{2,}/g, "/").replace(/\/+$/, "") || "/";
}

/** True when a path may be answered from a snapshot at all. */
export function isSnapshotPath(path: string): boolean {
  if ((SNAPSHOT_PAGE_PATHS as readonly string[]).indexOf(path) >= 0) return true;
  if ((SNAPSHOT_ASSET_PATHS as readonly string[]).indexOf(path) >= 0) return true;
  return path.startsWith("/item/");
}

/**
 * The file a path is written to: `/store` becomes a directory with an `index.html`
 * inside it, which is how Pages answers `/store` from a file at all, and a path
 * that already names a file keeps its name.
 */
export function snapshotFilePath(path: string): string | null {
  if (!isSnapshotPath(path)) return null;
  const clean = normalisePath(path).replace(/^\//, "");
  if (!clean) return "index.html";
  if (clean.indexOf(".") >= 0) return clean;
  return `${clean}/index.html`;
}

/** The folder one origin's snapshots live in, or null for an origin we did not build. */
export function snapshotFolder(manifest: PrerenderManifest, origin: string): string | null {
  const index = manifest.origins.indexOf(origin);
  if (index < 0) return null;
  return index === 0 ? "" : `${SNAPSHOT_FOLDER}/${index}`;
}

/** The absolute URL of a path's snapshot for one origin, or null. */
export function snapshotUrl(manifest: PrerenderManifest, origin: string, path: string): string | null {
  const folder = snapshotFolder(manifest, origin);
  if (folder === null) return null;
  const file = manifest.pages[normalisePath(path)];
  if (!file) return null;
  return `/${folder ? `${folder}/` : ""}${file}`;
}

/** Seconds since the build. A clock behind the build reads as zero, not negative. */
export function manifestAgeSeconds(manifest: PrerenderManifest, nowMs: number): number {
  const builtAt = Date.parse(manifest.builtAt);
  if (!Number.isFinite(builtAt)) return Number.POSITIVE_INFINITY;
  return Math.max(0, (nowMs - builtAt) / 1000);
}

/** `PRERENDER_MAX_AGE_SECONDS` from the Pages vars, or the default, never nonsense. */
export function maxAgeSeconds(env: { PRERENDER_MAX_AGE_SECONDS?: string }): number {
  const configured = Number.parseInt(env.PRERENDER_MAX_AGE_SECONDS ?? "", 10);
  if (!Number.isFinite(configured) || configured < 0) return DEFAULT_PRERENDER_MAX_AGE_SECONDS;
  return configured;
}

/**
 * Every URL the sitemap advertises, as paths.
 *
 * The build takes its route list from the sitemap rather than from a second list
 * kept beside it: a page Google is told about and a page that is prerendered are
 * then the same set by construction, and a new dish appears in both without
 * anybody remembering to edit the build.
 */
export function pathsFromSitemap(sitemapXml: string, origin: string): string[] {
  const paths: string[] = [];
  const seen = new Set<string>();
  const pattern = /<loc>\s*([^<\s]+)\s*<\/loc>/g;
  for (let match = pattern.exec(sitemapXml); match !== null; match = pattern.exec(sitemapXml)) {
    const raw = match[1] ?? "";
    let path: string;
    if (raw.startsWith("/")) {
      path = raw;
    } else {
      let url: URL;
      try {
        url = new URL(raw);
      } catch {
        continue;
      }
      // A URL from another host — a legacy domain in the sitemap — is not ours to write.
      if (url.origin !== origin) continue;
      path = url.pathname;
    }
    const clean = normalisePath(path.split("?")[0] ?? "");
    if (seen.has(clean)) continue;
    seen.add(clean);
    paths.push(clean);
  }
  return paths;
}

/**
 * Records on the page itself when it was written and from what.
 *
 * Support asks "is this customer looking at a snapshot?" about once a month and
 * the answer should not need a deployment log: view source, and the `<html>`
 * element says. Crawlers ignore an attribute they do not know.
 */
export function stampPrerendered(html: string, builtAt: string, source: "live" | "sample"): string {
  const stamp = ` data-prerendered-at="${builtAt}" data-prerender-source="${source}"`;
  return html.replace(/<html([^>]*)>/, (_match, attributes: string) => `<html${attributes}${stamp}>`);
}

/**
 * The one true URL for a request, or null when it is already canonical.
 *
 * Two families of duplicate reach a Pages site: `/store/` and `/store`, which the
 * router has always treated as the same page, and `/store/index.html`, which is
 * the file the build wrote to answer `/store` and which is the same page again.
 * Duplicate content is not a catastrophe, but it splits the signals Google
 * collects across three URLs for one menu, and the fix is one line at the edge:
 * everything else is a 301 to the canonical path, query string intact.
 *
 * Only a GET or a HEAD is redirected. A 301 on a POST is allowed to drop the body
 * by every browser that implements it, and Undal's no-JavaScript ordering path
 * posts to `/basket/add` — a customer's basket is not something to gamble on a
 * status code.
 */
export function canonicalRedirect(url: URL): string | null {
  const path = url.pathname;
  const search = url.search;
  // Trailing slashes and doubled slashes both collapse here: `normalisePath` is
  // the router's own expression, so the edge and the router agree on what a path
  // is, and only the edge answers with a redirect.
  const clean = normalisePath(path);
  if (clean !== path) return `${clean}${search}`;
  if (path.endsWith("/index.html")) {
    const base = normalisePath(path.slice(0, -"/index.html".length));
    if (isSnapshotPath(base)) return `${base}${search}`;
  }
  return null;
}
