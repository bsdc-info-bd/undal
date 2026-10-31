/**
 * The customer website on Cloudflare Pages.
 *
 * Pages is where the public site now lives, and the question this file answers is
 * the one that decides whether that was a good idea: **does a request get the
 * same answer it got on the Worker?** The router did not change, so the interesting
 * cases are the ones the move added — a snapshot standing in for a render, a
 * snapshot standing in for a *failure*, and the two shapes of URL that must not
 * become two pages in an index.
 *
 * The last test runs the real build script, end to end, against the fake backend
 * the rest of the suite uses: it renders every public page with the production
 * router and writes a Pages output directory, and this file then reads that
 * directory the way `deploy-pages.yml` does. A build script that only ever runs
 * in CI is a build script nobody has tested.
 */
import assert from "node:assert/strict";
import { test } from "node:test";
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  FUNCTION_EXCLUDED_PATHS,
  SNAPSHOT_ASSET_PATHS,
  SNAPSHOT_PAGE_PATHS,
  canonicalRedirect,
  isSnapshotPath,
  manifestAgeSeconds,
  maxAgeSeconds,
  normalisePath,
  pathsFromSitemap,
  snapshotFilePath,
  snapshotUrl,
  stampPrerendered,
  type PrerenderManifest,
} from "../../web/src/prerender";
import { PRERENDER_HEADER, resetPrerenderMemo, servePagesRequest, type PagesContext, type PagesEnv } from "../../web/src/serve";
import type { Env } from "../src/env";
import type { WebMenu } from "../src/web/store-data";

const ORIGIN = "https://undal-web.pages.dev";

const menu: WebMenu = {
  fetchedAt: Date.now(),
  store: null,
  sections: [],
  subsections: [],
  campaigns: [],
  combos: [],
  dishes: [
    {
      id: "rice", kind: "dish", name: "Kacchi biryani", description: "Home cooked",
      amountPaisa: 28000, payablePaisa: 28000, discountAmountPaisa: 0, available: true,
      veg: false, spiceLevel: 1, tags: [], orderCount: 4, categoryId: "rice",
      subcategoryId: "", imageUrl: "", prepMinutes: 40, featured: true,
    },
  ],
};

/** The HTML a build would have written: recognisably not a live render. */
const SNAPSHOT = `<!doctype html><html lang="en-GB" data-prerender-source="live"><body>a snapshot of the menu</body></html>`;

function manifest(overrides: Partial<PrerenderManifest> = {}): PrerenderManifest {
  return {
    version: 1,
    builtAt: new Date().toISOString(),
    source: "live",
    origins: [ORIGIN],
    pages: {
      "/": "index.html",
      "/store": "store/index.html",
      "/item/rice": "item/rice/index.html",
      "/robots.txt": "robots.txt",
      "/sitemap.xml": "sitemap.xml",
    },
    items: 1,
    ...overrides,
  };
}

/** A stand-in for the Pages CDN: a map of files, answered by pathname. */
function assetsFor(files: Record<string, string>): PagesEnv["ASSETS"] {
  return {
    async fetch(input: Request | string): Promise<Response> {
      const url = new URL(typeof input === "string" ? input : input.url);
      const body = files[url.pathname];
      if (body === undefined) return new Response("no such asset", { status: 404 });
      const type = url.pathname.endsWith(".json")
        ? "application/json"
        : url.pathname.endsWith(".html")
          ? "text/html; charset=utf-8"
          : "text/plain; charset=utf-8";
      return new Response(body, { status: 200, headers: { "content-type": type } });
    },
  };
}

function filesFor(manifestValue: PrerenderManifest | null): Record<string, string> {
  const files: Record<string, string> = {
    "/index.html": SNAPSHOT,
    "/store/index.html": SNAPSHOT,
    "/item/rice/index.html": SNAPSHOT,
    "/robots.txt": "User-agent: *\nAllow: /\n",
    "/sitemap.xml": "<urlset></urlset>",
  };
  if (manifestValue) files["/prerender-manifest.json"] = JSON.stringify(manifestValue);
  return files;
}

/**
 * A KV namespace with a menu in it. `broken` is the shape a real outage has: the
 * namespace refuses and the service account cannot be parsed, so the menu cannot
 * be rebuilt either and the router answers 503.
 */
function kv(broken: boolean): Env["UNDAL_KV"] {
  return {
    get: async (key: string, type?: string) => {
      if (broken) throw new Error("kv unavailable");
      if (key === "web:menu:v1") return type === "json" ? menu : JSON.stringify(menu);
      return null;
    },
    put: async () => undefined,
    delete: async () => undefined,
  } as unknown as Env["UNDAL_KV"];
}

function pagesEnv(files: Record<string, string>, options: { broken?: boolean; maxAge?: string } = {}): PagesEnv {
  return {
    UNDAL_KV: kv(Boolean(options.broken)),
    FIREBASE_SA_JSON: options.broken ? "{ not json" : "",
    FIREBASE_RTDB_URL: "https://undal.example.firebaseio.com",
    FIREBASE_WEB_API_KEY: "fake-web-api-key",
    CLOUDINARY_CLOUD_NAME: "fake",
    APP_PACKAGE_NAME: "com.undal.syl.app",
    GITHUB_REPO_SLUG: "bsdc-info-bd/undal",
    PROMO_TOPIC: "promos_all",
    KITCHEN_TOPIC: "undal_kitchen",
    APP_SIGNING_SHA256: "",
    RELEASE_WEBHOOK_SECRET: "",
    ASSETS: assetsFor(files),
    PRERENDER_MAX_AGE_SECONDS: options.maxAge,
  } as unknown as PagesEnv;
}

function contextFor(request: Request, env: PagesEnv): PagesContext {
  const waited: Promise<unknown>[] = [];
  return {
    request,
    env,
    next: async () => new Response("the next middleware", { status: 404 }),
    waitUntil: (promise) => {
      waited.push(promise);
    },
    passThroughOnException: () => undefined,
  };
}

async function serve(path: string, options: { cookie?: string; env?: PagesEnv; method?: string } = {}) {
  const env = options.env ?? pagesEnv(filesFor(manifest()));
  const headers: Record<string, string> = { accept: "text/html,application/xhtml+xml" };
  if (options.cookie) headers.cookie = options.cookie;
  const request = new Request(`${ORIGIN}${path}`, { method: options.method ?? "GET", headers });
  resetPrerenderMemo();
  const response = await servePagesRequest(contextFor(request, env));
  return { response, body: await response.text() };
}

// ---------------------------------------------------------------- the snapshot

test("an anonymous visitor is answered from the file the build wrote", async () => {
  const { response, body } = await serve("/store");
  assert.equal(response.status, 200);
  assert.equal(response.headers.get(PRERENDER_HEADER), "hit");
  assert.equal(body, SNAPSHOT, "the snapshot's own bytes, not a rendering of the menu");
  assert.doesNotMatch(body, /Kacchi biryani/, "so nothing was rendered and nothing read the menu");
  // A file out of the build output carries the same protections as a page out of
  // the router: a snapshot without a CSP is a snapshot an iframe can steal.
  assert.match(response.headers.get("content-security-policy") ?? "", /default-src 'self'/);
  assert.equal(response.headers.get("x-content-type-options"), "nosniff");
  const stampedAt = Date.parse(response.headers.get("x-undal-prerendered-at") ?? "");
  assert.ok(stampedAt > Date.now() - 60_000, "the header says when the file was written");
  assert.equal(Number(response.headers.get("x-undal-prerender-age")), 0);
});

test("a basket belongs to the customer, so the page is rendered", async () => {
  const { response, body } = await serve("/store", { cookie: "undal_basket=" });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get(PRERENDER_HEADER), "bypass");
  assert.match(body, /Kacchi biryani/, "the live menu, from the router");
  assert.doesNotMatch(body, /a snapshot of the menu/);
});

test("a session is never answered from a file", async () => {
  const { response } = await serve("/store", { cookie: "undal_session=abc123" });
  assert.equal(response.headers.get(PRERENDER_HEADER), "bypass");
  assert.equal(response.status, 200);
});

test("Cloudflare's own cookies do not make a visitor unshareable", async () => {
  // Bot Management sets __cf_bm on the first request from any browser with
  // JavaScript. Treating it as identity meant the snapshots and the Cache API
  // answered every human being with a fresh render, and only curl got the fast
  // path — which is the opposite of what either is for.
  const { response, body } = await serve("/store", { cookie: "__cf_bm=Qm90.; _cfuvid=xyz-1" });
  assert.equal(response.headers.get(PRERENDER_HEADER), "hit");
  assert.equal(body, SNAPSHOT);
});

test("an authorization header is identity, whatever the path", async () => {
  const env = pagesEnv(filesFor(manifest()));
  const request = new Request(`${ORIGIN}/store`, { headers: { accept: "text/html", authorization: "Bearer token" } });
  resetPrerenderMemo();
  const response = await servePagesRequest(contextFor(request, env));
  assert.equal(response.headers.get(PRERENDER_HEADER), "bypass");
});

test("an old snapshot is rendered instead of served", async () => {
  const builtAt = new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString();
  const env = pagesEnv(filesFor(manifest({ builtAt })));
  const { response, body } = await serve("/store", { env });
  assert.equal(response.headers.get(PRERENDER_HEADER), "miss", "the file exists but it is two hours old");
  assert.match(body, /Kacchi biryani/, "so the customer gets the menu as it is now");
  assert.equal(response.headers.get("x-undal-prerendered-at"), null);
});

test("PRERENDER_MAX_AGE_SECONDS is the kitchen's dial, not a constant", async () => {
  const builtAt = new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString();
  const env = pagesEnv(filesFor(manifest({ builtAt })), { maxAge: "86400" });
  const { response, body } = await serve("/store", { env });
  assert.equal(response.headers.get(PRERENDER_HEADER), "hit");
  assert.equal(body, SNAPSHOT);
  assert.equal(Number(response.headers.get("x-undal-prerender-age")), 7200);
});

test("when the render fails, an old snapshot beats an error page", async () => {
  // Firestore refusing is not hypothetical: it is the failure the whole alert
  // ladder exists around. A customer who cannot see the menu cannot order, and a
  // menu from five minutes ago still lets them look.
  const builtAt = new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString();
  const env = pagesEnv(filesFor(manifest({ builtAt })), { broken: true });
  const { response, body } = await serve("/store", { env });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get(PRERENDER_HEADER), "stale");
  assert.equal(body, SNAPSHOT);
  // Served, but not for long: the failure may already be over.
  assert.equal(response.headers.get("cache-control"), "public, max-age=30, stale-if-error=300");
});

test("a failure with no snapshot is a page a person can read", async () => {
  // The storefront has its own answer for an unreachable menu, and it is the one
  // a customer gets: a 503 page that says the basket survived and offers the two
  // things worth doing next. The generic failure page is for a route that has no
  // opinion of its own.
  const env = pagesEnv({ "/prerender-manifest.json": "{}" }, { broken: true });
  const { response, body } = await serve("/store", { env });
  assert.equal(response.status, 503, "the menu could not be read, which is a 503 and not a 500");
  assert.match(body, /reach the kitchen/);
  assert.match(body, /Your saved basket is still on this device/);
  assert.match(body, /href="\/track"/, "and there is somewhere to go from here");
  assert.doesNotMatch(body, /^\{/, "it is not the JSON the app parses");
});

test("a route with no opinion of its own gets the shared failure page", async () => {
  // /about has no page of its own for an unreachable menu, so the router's
  // handler turns the 503 into the one page every route can share.
  const env = pagesEnv(filesFor(null), { broken: true });
  const { response, body } = await serve("/about", { env });
  assert.ok(response.status >= 500, `a broken menu answers ${response.status}`);
  assert.match(body, /The kitchen dropped a plate|reach the kitchen/);
  assert.match(body, /<html/, "a person reads a page, not a JSON body");
});

test("a build with no manifest renders everything, which is what it did before", async () => {
  const env = pagesEnv(filesFor(null));
  const { response, body } = await serve("/store", { env });
  assert.equal(response.headers.get(PRERENDER_HEADER), "unavailable");
  assert.match(body, /Kacchi biryani/);
  assert.equal(response.status, 200);
});

test("an origin the build did not render for is rendered, not guessed", async () => {
  // Snapshots carry canonical URLs and Open Graph tags built from their origin.
  // Serving a pages.dev snapshot on a custom domain would tell Google to index
  // the wrong host, so a domain the build did not see is rendered instead.
  const env = pagesEnv(filesFor(manifest({ origins: ["https://shop.example"] })));
  const { response, body } = await serve("/store", { env });
  assert.equal(response.headers.get(PRERENDER_HEADER), "unavailable");
  assert.match(body, /Kacchi biryani/);
});

test("a page nobody prerendered is rendered", async () => {
  const { response } = await serve("/basket");
  assert.equal(response.headers.get(PRERENDER_HEADER), null, "the basket is not a snapshot path at all");
  assert.equal(response.status, 200);
});

test("the app's own routes are untouched by the website's optimisations", async () => {
  const { response, body } = await serve("/api/store/menu");
  assert.equal(response.headers.get(PRERENDER_HEADER), null);
  assert.equal(response.status, 200);
  assert.equal(JSON.parse(body).ok, true, "the same JSON the Android app parses");
});

// ------------------------------------------------------------------- canonical

test("one menu, one address", async () => {
  for (const [asked, expected] of [
    ["/store/", "/store"],
    ["//store", "/store"],
    ["/store//", "/store"],
    ["/store/index.html", "/store"],
    ["/item/rice/", "/item/rice"],
    ["/item/rice/index.html", "/item/rice"],
  ] as Array<[string, string]>) {
    const { response } = await serve(asked);
    assert.equal(response.status, 301, `${asked} should point at ${expected}`);
    assert.equal(response.headers.get("location"), expected);
    assert.equal(response.headers.get(PRERENDER_HEADER), null, "a redirect is not a snapshot");
  }
});

test("a query string survives the redirect", async () => {
  const { response } = await serve("/store/index.html?utm_source=whatsapp");
  assert.equal(response.status, 301);
  assert.equal(response.headers.get("location"), "/store?utm_source=whatsapp");
});

test("a POST is never redirected, because a 301 may drop its body", async () => {
  // Undal's no-JavaScript ordering path posts to /basket/add. A customer's
  // basket is not something to gamble on a status code's redirect semantics, so
  // a trailing slash on a POST reaches the router (which collapses it) instead of
  // being answered with a 301 the browser is allowed to turn into a GET.
  const { response } = await serve("/basket/add/", { method: "POST" });
  assert.notEqual(response.status, 301);
  assert.notEqual(response.headers.get("location"), "/basket/add");
});

// ----------------------------------------------------------------- the helpers

test("the snapshot rules are the rules the build and the Function share", () => {
  assert.equal(snapshotFilePath("/"), "index.html");
  assert.equal(snapshotFilePath("/store"), "store/index.html");
  assert.equal(snapshotFilePath("/item/rice"), "item/rice/index.html");
  assert.equal(snapshotFilePath("/robots.txt"), "robots.txt");
  assert.equal(snapshotFilePath("/basket"), null, "a page with a customer's state in it is not a file");
  assert.equal(snapshotFilePath("/api/store/menu"), null);

  assert.equal(isSnapshotPath("/about"), true);
  assert.equal(isSnapshotPath("/checkout"), false);
  assert.equal(isSnapshotPath("/account"), false);
  assert.equal(isSnapshotPath("/track"), false);
  assert.equal(normalisePath("//store//"), "/store");

  assert.equal(canonicalRedirect(new URL(`${ORIGIN}/store`)), null);
  assert.equal(canonicalRedirect(new URL(`${ORIGIN}/store/`)), "/store");
  assert.equal(canonicalRedirect(new URL(`${ORIGIN}/basket/`)), "/basket", "every trailing slash is a duplicate, not only a snapshot's");
  assert.equal(canonicalRedirect(new URL(`${ORIGIN}/a/index.html`)), null, "an index.html that is not a snapshot is the router's business");

  assert.equal(maxAgeSeconds({}), 300);
  assert.equal(maxAgeSeconds({ PRERENDER_MAX_AGE_SECONDS: "0" }), 0);
  assert.equal(maxAgeSeconds({ PRERENDER_MAX_AGE_SECONDS: "nonsense" }), 300);
  assert.equal(maxAgeSeconds({ PRERENDER_MAX_AGE_SECONDS: "-5" }), 300);

  const age = manifestAgeSeconds(manifest({ builtAt: new Date(Date.now() - 90_000).toISOString() }), Date.now());
  assert.ok(age >= 89 && age <= 91, `90 seconds old reads as ${age}`);
  assert.equal(
    manifestAgeSeconds(manifest({ builtAt: "not a date" }), Date.now()),
    Number.POSITIVE_INFINITY,
    "a manifest with no readable clock is treated as ancient rather than as new",
  );

  assert.equal(
    stampPrerendered('<!doctype html><html lang="en-GB"><head>', "2026-01-01T00:00:00.000Z", "live"),
    '<!doctype html><html lang="en-GB" data-prerendered-at="2026-01-01T00:00:00.000Z" data-prerender-source="live"><head>',
  );

  // Every path excluded from the Function must exist as a file, or excluding it
  // turns a served page into a 404 from the CDN.
  for (const excluded of FUNCTION_EXCLUDED_PATHS) {
    if (excluded.includes("*")) continue;
    if (excluded === "/prerender-manifest.json") continue;
    assert.ok(
      (SNAPSHOT_ASSET_PATHS as readonly string[]).includes(excluded),
      `${excluded} is excluded from the Function but the build never writes it`,
    );
  }
  for (const page of SNAPSHOT_PAGE_PATHS) assert.ok(isSnapshotPath(page));

  const value = manifest();
  assert.equal(snapshotUrl(value, ORIGIN, "/store"), "/store/index.html");
  assert.equal(snapshotUrl(value, ORIGIN, "/"), "/index.html");
  assert.equal(snapshotUrl(value, "https://shop.example", "/store"), null);
  assert.equal(snapshotUrl({ ...value, origins: [ORIGIN, "https://shop.example"] }, "https://shop.example", "/store"), "/_snapshots/1/store/index.html");
  assert.equal(snapshotUrl(value, ORIGIN, "/basket"), null);
});

test("the sitemap is where the build gets its route list", () => {
  const xml = `<urlset>
    <url><loc>${ORIGIN}/</loc></url>
    <url><loc>${ORIGIN}/store</loc></url>
    <url><loc>${ORIGIN}/item/rice</loc></url>
    <url><loc>${ORIGIN}/basket</loc></url>
    <url><loc>https://legacy.example/store</loc></url>
    <url><loc>/about</loc></url>
    <url><loc>${ORIGIN}/store</loc></url>
  </urlset>`;
  assert.deepEqual(pathsFromSitemap(xml, ORIGIN), ["/", "/store", "/item/rice", "/basket", "/about"]);
  assert.deepEqual(pathsFromSitemap("<urlset></urlset>", ORIGIN), []);
});

// ----------------------------------------------------------------- the build

test("the build writes a Pages directory the deploy can publish", () => {
  const output = mkdtempSync(join(tmpdir(), "undal-pages-"));
  // The real script, the real router, the fake backend: `--source sample` is the
  // mode a laptop without Firebase credentials builds in, and it is stamped on
  // every page it writes so a deploy can refuse it.
  execFileSync(
    process.execPath,
    // tsx lives in the worker's own node_modules: the Pages project has no
    // toolchain of its own, because it is the same code as the Worker.
    [join(import.meta.dirname, "..", "node_modules", "tsx", "dist", "cli.mjs"), join(import.meta.dirname, "..", "..", "web", "scripts", "build.mts"), "--source", "sample", "--output", output],
    { encoding: "utf8", timeout: 240_000, stdio: ["ignore", "pipe", "pipe"] },
  );

  const built = JSON.parse(readFileSync(join(output, "prerender-manifest.json"), "utf8")) as PrerenderManifest;
  assert.equal(built.version, 1);
  assert.equal(built.source, "sample");
  assert.deepEqual(built.origins, ["https://undal-web.pages.dev"]);
  assert.ok(built.items >= 60, `the published catalogue has 67 dishes; the build wrote ${built.items}`);
  assert.ok(Date.parse(built.builtAt) > Date.now() - 10 * 60 * 1000);

  for (const [path, file] of Object.entries(built.pages)) {
    assert.ok(existsSync(join(output, file)), `${path} is in the manifest but ${file} is not on disk`);
    assert.equal(snapshotFilePath(path), file, `${path} was written to the wrong place`);
  }
  for (const page of SNAPSHOT_PAGE_PATHS) {
    assert.ok(built.pages[page], `${page} is a core page and the build did not write it`);
  }

  const storefront = readFileSync(join(output, "store", "index.html"), "utf8");
  assert.match(storefront, /data-prerendered-at="/, "a snapshot says when it was written");
  assert.match(storefront, /data-prerender-source="sample"/);
  assert.match(storefront, /Crispy Fried Chicken/, "the sample build renders the published catalogue");
  assert.match(storefront, /content-security-policy|<style>/i);
  assert.ok(storefront.length > 40_000, "a storefront is a page, not a stub");

  // Every page a crawler is told about, and that may be a file, is a file.
  const sitemap = readFileSync(join(output, "sitemap.xml"), "utf8");
  const advertised = pathsFromSitemap(sitemap, "https://undal-web.pages.dev");
  assert.ok(advertised.length > 60, `the sitemap advertises ${advertised.length} URLs`);
  for (const path of advertised) {
    if (!isSnapshotPath(path) || !(SNAPSHOT_ASSET_PATHS as readonly string[]).includes(path)) continue;
    assert.ok(built.pages[path], `${path} is in the sitemap, may be a snapshot, and is not one`);
  }
  for (const path of advertised.filter((entry) => entry.startsWith("/item/"))) {
    assert.ok(built.pages[path], `${path} is advertised and was not prerendered`);
  }

  for (const file of ["robots.txt", "sitemap.xml", "favicon.svg", "manifest.webmanifest", "sw.js", "apple-touch-icon.png", "_headers", "_redirects", "_routes.json", "404.html", "500.html"]) {
    assert.ok(existsSync(join(output, file)), `${file} is missing from the build output`);
    assert.ok(readFileSync(join(output, file)).length > 0, `${file} is empty`);
  }
  for (const icon of ["icons/icon-192.png", "icons/icon-512.png", "icons/icon-maskable-512.png"]) {
    const bytes = readFileSync(join(output, icon));
    assert.ok(bytes.length > 500, `${icon} is ${bytes.length} bytes`);
    assert.equal(bytes.subarray(1, 4).toString("ascii"), "PNG", `${icon} is not a PNG`);
  }

  assert.match(readFileSync(join(output, "404.html"), "utf8"), /This table is not laid/);
  assert.match(readFileSync(join(output, "500.html"), "utf8"), /The kitchen dropped a plate/);

  const routes = JSON.parse(readFileSync(join(output, "_routes.json"), "utf8")) as { version: number; include: string[]; exclude: string[] };
  assert.equal(routes.version, 1);
  assert.deepEqual(routes.include, ["/*"]);
  assert.deepEqual(routes.exclude, [...FUNCTION_EXCLUDED_PATHS]);

  const redirects = readFileSync(join(output, "_redirects"), "utf8");
  assert.match(redirects, /^\/store\/index\.html \/store 301$/m, "one canonical address per snapshot");
  assert.match(redirects, /^\/index\.html\s+\/\s+301$/m);
});

test("the deploy publishes the real menu and nothing else", () => {
  // The workflow is configuration, and configuration is where a build that works
  // on a laptop quietly publishes sample data. These are the promises it makes.
  const workflow = readFileSync(join(import.meta.dirname, "..", "..", ".github", "workflows", "deploy-pages.yml"), "utf8");
  assert.match(workflow, /--source live/, "the deploy renders the kitchen's real menu");
  assert.match(workflow, /"source": "live"|source.*live/, "and refuses to publish a sample build");
  assert.match(workflow, /wrangler pages deploy/, "it deploys to Pages");
  assert.match(workflow, /web\/public/, "the directory it deploys is the one the build writes");
  assert.match(workflow, /npm test/, "the tests run before the site is published");
  assert.match(workflow, /check-web-compat\.mjs/, "and so do the storefront gates");
  assert.match(workflow, /pages secret put FIREBASE_SA_JSON/, "the site reads the same Firebase project as the app");
  assert.doesNotMatch(workflow, /--source sample/, "no deploy builds a sample");
});
