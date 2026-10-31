# The customer website on Cloudflare Pages

The website a customer reads runs on **Cloudflare Pages** (project `undal-web`).
The Worker (project `undal`) keeps the Android app, the crons and the alert
ladder. This is the document for the person who has to deploy it, debug it or
explain it.

---

## 1 · What moved, and what deliberately did not

| | Pages `undal-web` | Worker `undal` |
|---|---|---|
| Storefront, dish pages, about, download | **yes** — snapshots and renders | yes — unchanged, as a fallback |
| Basket, checkout, login, account, tracking | **yes** — rendered, never snapshotted | yes — unchanged |
| `/api/store/*`, `/api/account/*` called by the site's own JavaScript | **yes** | yes |
| `/api/*` called by the **Android app** | reachable, but not used | **yes — this is what the app calls** |
| Crons: presence `*/5`, promo `0 12`, re-alert `* * * * *` | **impossible** (Pages has no crons) | **yes** |
| FCM relay `/notify/*`, release webhook `/admin/release` | reachable, but not used | **yes** |
| `/latest.json`, `/download/apk`, `/.well-known/assetlinks.json` | reachable | **yes — the app and GitHub call these** |
| Icons, manifest, service worker, favicon | **static files, no invocation** | served as before |

`gradle.properties` pins the installed app to
`https://undal.bsdc.workers.dev/`, and an installed build cannot be repointed.
So every route the app touches stays on the Worker, and the Worker's copy of the
web routes is **not** dead code: it is the fallback that keeps the site up if the
Pages project is ever rolled back, and it is what `tools/preview-web.mts` runs on
a laptop. Two deployments, one router, tested once.

**Why this reduces load rather than moving it.** A request for a file in
`web/public/` is served by the Pages CDN and costs nothing at all: no
invocation, no CPU, no KV read. A request that reaches a Function is charged to
Pages' own allowance, not the Worker's. The Worker's budget now goes to the app,
the kitchen's alerts and the crons — the things that cannot be moved and cannot
fail.

---

## 2 · The three answers a request can get

Every response from a snapshot-eligible path carries **`x-undal-prerender`**:

| Value | What happened | Cost |
|---|---|---|
| `hit` | The build wrote this page and it is fresh enough; the file was served | one CDN read |
| `stale` | The live render failed (5xx) and an old snapshot stood in for it | one CDN read |
| `miss` | A snapshot exists but it is older than `PRERENDER_MAX_AGE_SECONDS`; the page was rendered | a render, then the Cache API |
| `bypass` | The visitor has state — a basket, a session, an `Authorization` header — or the URL carries a query string | a render |
| `unavailable` | There is no manifest, or none for this origin; everything renders | a render |

`unavailable` is the answer a deployment gives before its first successful build.
It is also the answer this site gave for its whole life until now: nothing breaks,
the pages are simply rendered.

A live render additionally carries **`x-undal-edge`** (`hit` / `stale` / `miss` /
`bypass` / `unavailable`) from the Cache API layer in `worker/src/util/page-cache.ts`.
The two headers answer different questions: `x-undal-prerender` says whether a
*file* answered, `x-undal-edge` says whether the *cache* answered.

### Why a basket can never be a file

The basket badge, the basket bar and the account header are rendered from cookies
the browser sends, and both cookies are `HttpOnly` — no script can read them and
rebuild the markup. So a snapshot is served only to a request with **no cookies at
all**: a first visit, a crawler, a link shared on WhatsApp. The moment a customer
adds a dish, every later request carries `undal_basket` and is rendered. Serving a
snapshot to that customer would show them an empty basket, which is the one bug
this design must never have.

Cloudflare's own cookies (`__cf_bm`, `cf_clearance`, `_cfuvid`, `__cfruid`,
`cf_chl_*`) are **not** identity and are ignored by that test. They are set on the
first request from any browser with JavaScript, so treating them as state meant
the fast path answered `curl` and rendered for every human being.

### Why a search can never be a file

The query string is part of the URL, and the build wrote one file per **path**:
`/store` is the whole menu. `/store?q=bhaji`, `/store?section=rice` and
`/store?sort=price-asc` are three different pages, and answering any of them from
the whole-menu file is a filter that silently does nothing — the customer asks
for one dish, is handed every dish, and has no way to know they were ignored. A
search that does nothing is worse than a slow search, because it looks like an
answer.

So a file answers only a request with **no query at all**. Everything else is
rendered by the router that knows what the query means, and stays cheap anyway:
the Cache API layer in the Worker caches by full URL, so the second person to
search for the same word is answered from the cache rather than from Firestore.

This was found in production, not in review: the first deploy that actually
published the Function made it possible to ask the site a question, and the
answer to `/store?q=bhaji` was 57 dishes. `worker/test/pages.test.ts` now asserts
`bypass` for a queried snapshot path, and the deploy smoke test asks the live site
the same question and fails if a file answers it.

---

## 3 · What the build writes

`cd worker && npm run build:web` asks **the production router** for every URL in
the sitemap and writes the bytes into `web/public/`. There is no second
implementation of the storefront and no template language: a page that renders in
production renders here, and a route that 404s there 404s here — the build says so
instead of writing an empty file.

```
web/public/
├── index.html  store/  about/  download/      the four public pages
├── item/<dish-id>/index.html                  one per dish in the sitemap
├── robots.txt  sitemap.xml                    rendered, so the origin is right
├── favicon.svg  apple-touch-icon.png  icons/  artwork, excluded from Functions
├── manifest.webmanifest  sw.js                the installable-web files
├── 404.html  500.html                         what Pages serves if a Function cannot
├── _headers  _redirects  _routes.json         Pages' own configuration
└── prerender-manifest.json                    which files exist, and when they were written
```

* **The route list is the sitemap.** A dish Google is told about and a dish that
  is prerendered are the same set by construction; a new dish appears in both
  without anybody editing the build.
* **Snapshots are per origin.** Canonical URLs, Open Graph tags and the sitemap
  are built from the request's origin, so a snapshot made for `undal-web.pages.dev`
  and served on a custom domain would tell Google to index the wrong host. The
  first origin is written at the root of `public/` (which keeps `wrangler pages
  dev` and any plain static host working) and each further origin under
  `_snapshots/<n>`. A request from an origin the build never saw is rendered.
* **Every snapshot is stamped** with `data-prerendered-at` and
  `data-prerender-source` on its `<html>` element. "Is this customer looking at a
  snapshot?" is answered by *view source*, not by a deployment log.
* **`_routes.json` excludes the artwork** — `/icons/*`, `/favicon.svg`,
  `/apple-touch-icon.png`, `/manifest.webmanifest`, `/sw.js` and the manifest —
  so the files a page asks for on every load never reach the Function at all.

### Freshness

`PRERENDER_MAX_AGE_SECONDS` (a Pages var, default **300**) decides how long a
snapshot may answer a request before it is a last resort instead of a first
answer. Five minutes is deliberate:

* a burst of crawlers and first-time visitors costs no rendering at all;
* a dish the kitchen has just sold out of is not on the anonymous page for an
  hour — it is on it for five minutes, and for *zero* minutes for anybody with a
  basket, because they are rendered;
* a live render goes through the Cache API anyway (30 seconds for the storefront,
  900 seconds stale-while-revalidate), so repeated hits after the snapshot ages
  out are still cheap.

Set it to `0` to render every request — the site then behaves exactly as it did on
the Worker. Set it to `86400` to serve snapshots until the next deploy. A deploy
rebuilds them, which is why every push that touches `worker/` or `web/` runs
`deploy-pages`.

---

## 4 · Deploying

### The first time

1. The secrets `deploy-worker` already needs are the ones this needs:
   `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `FIREBASE_SA_JSON`,
   `FIREBASE_RTDB_URL`, `CLOUDINARY_CLOUD_NAME`, `FIREBASE_WEB_API_KEY`.
   Optional: `WEB_ORIGIN`, the custom domain, if there is one.
2. Run **`deploy-worker`** once. It creates the `undal-kv` namespace; the website
   binds to the same one, because one store for the menu snapshot, the rate limits
   and the release record is the point.
3. Run **`deploy-pages`**. It creates the Pages project if it does not exist,
   pushes the four secrets to it, prerenders against the live menu, refuses to
   publish a sample build, deploys `web/public` **from inside `web/`**, and then
   smoke-tests the deployed site: `/healthz` answers, a path nobody prerendered is
   rendered rather than served from `404.html`, `/store` comes back
   `x-undal-prerender: hit`, `/store` with a basket cookie comes back `bypass`,
   `/store?q=…` is not answered from the whole-menu file, and `/store/` is a 301
   to `/store`.

   Deploying from inside `web/` is not a style choice. Wrangler resolves two
   things against its **working directory** rather than against the directory it
   is handed: the project's `wrangler.toml` — the vars and the `UNDAL_KV`
   binding — and the `functions/` directory it compiles into Pages Functions.
   `wrangler pages deploy web/public` from the repository root finds neither and
   uploads a folder of HTML, and reports success while doing it. The site then
   answers, `/healthz` 404s against the prerendered `404.html`, and a snapshot
   goes to a customer with dishes in their basket. When a deploy fails, the smoke
   test's own log is published to the commit or the pull request, so the response
   the site gave is readable without opening a raw log.

After that it runs on every push to `main` that touches `web/`, `worker/` or
`tools/`.

### A custom domain

Add the domain in the Cloudflare dashboard (Pages → `undal-web` → Custom domains),
set the `WEB_ORIGIN` secret to `https://that-domain`, and run `deploy-pages`. The
build then writes snapshots for **both** origins, and each request is answered from
the set built for its own host — so the canonical URL is right on both.

### Verifying a deployment by hand

```bash
base=https://undal-web.pages.dev

curl -sD- -o /dev/null "$base/store" | grep -i 'x-undal-\|cache-control'
#   x-undal-prerender: hit          ← a file answered
#   x-undal-prerendered-at: …       ← when the build wrote it
#   x-undal-prerender-age: 12       ← seconds old

curl -sD- -o /dev/null -H 'cookie: undal_basket=' "$base/store" | grep -i x-undal-prerender
#   x-undal-prerender: bypass       ← a basket is rendered, never served from a file

curl -sD- -o /dev/null "$base/store?q=bhaji" | grep -i x-undal-prerender
#   x-undal-prerender: bypass       ← a search is a different URL from the menu

curl -s "$base/a-path-nobody-prerendered" | grep -o 'lives at <strong>[^<]*'
#   lives at <strong>/a-path-nobody-prerendered
#                                   ← the Function rendered it; a static folder
#                                     would say /a-page-that-does-not-exist

curl -sD- -o /dev/null "$base/store/" | grep -i 'HTTP/\|location'
#   301, location: /store           ← one menu, one address

curl -sD- -o /dev/null "$base/icons/icon-192.png" | grep -i 'cache-control'
#   max-age=604800                  ← artwork never reaches the Function

curl -s "$base/prerender-manifest.json" | python3 -m json.tool | head
#   source must be "live" on a deployed site; "sample" means it was built
#   without Firebase credentials and must not have been published
```

### On a laptop

```bash
cd worker
npm run build:web -- --source sample --output /tmp/undal-public   # no credentials needed
npm run pages:dev                                                # wrangler pages dev web/public
npx tsx ../tools/preview-web.mts 8790                            # the Worker alone, as before
```

`--source sample` renders the published catalogue against the same fake backend
the tests use, stamps every page `data-prerender-source="sample"`, and is refused
by `deploy-pages.yml`. It exists so the whole pipeline — router, pages, build,
manifest — can be exercised without spending a Firebase project.

---

## 5 · What a page costs

The site shares the Worker's KV namespace, so the free tier's **100,000 reads,
1,000 writes and 1,000 deletes a day** are one budget across both. Everything
below is what keeps the website inside it.

| Cost | Before | Now |
|---|---|---|
| Icons, manifest, service worker | 1 Worker request each, rate-limited | **0** — excluded from Functions |
| Anonymous storefront view | 1 request + render + KV reads | **0 requests, 0 reads** — a file |
| Storefront view with a basket | render + menu read | render + **memoised** menu read (20 s) |
| Rate limiting | 1 KV read **+ 1 KV write per request** | memory-first; KV only past 60% of a limit |
| Menu | 7 Firestore reads per cold isolate, then a KV read | one read per 20 s per isolate, single-flighted |
| Firebase access token | minted per isolate | memoised 5 minutes, single-flighted |
| Repeated identical page | rendered again | Cache API, 30 s + 900 s stale-while-revalidate |

The gateway that does this is `worker/src/util/kv.ts`; the counters it keeps are
published at **`/healthz`** as `kv.usage` and `kv.savings`, so the budget is a
number you can look at rather than a surprise at the end of the month.

---

## 6 · Failure modes

| Failure | What a customer sees | Why |
|---|---|---|
| Firestore unreachable, snapshot fresh | the menu, `x-undal-prerender: hit` | the file was never going to fail |
| Firestore unreachable, snapshot old | the menu, `x-undal-prerender: stale`, cached 30 s | stale food beats no food, and the header says so |
| Firestore unreachable, no snapshot | "We couldn't reach the kitchen", HTTP 503, basket intact | the storefront's own page, which says the basket survived |
| A route with no page of its own fails | "The kitchen dropped a plate", its real status | `worker/src/web/error-pages.ts` |
| An unknown URL, in a browser | a 404 page with four ways back to the menu | negotiated: HTML for a browser |
| An unknown URL, from the app or a webhook | `{"ok":false,"error":"not-found"}` | the JSON the app already parses |
| The build ran without credentials | `deploy-pages` stops before deploying | a sample menu is not the kitchen's menu |
| The manifest is missing or unreadable | everything renders, `unavailable` | the pre-Pages behaviour |
| The Pages project is deleted or rolled back | the Worker still serves the whole site | its routes were never removed |
| The Function itself throws | Pages serves the matching file (`passThroughOnException`) | the last line of defence is the same HTML as the first |

---

## 7 · The files

| Path | What it is |
|---|---|
| `web/functions/[[path]].ts` | the Pages entry point: three lines, so the logic can be tested |
| `web/src/serve.ts` | snapshot or render or stale-snapshot; canonical redirects; header merging |
| `web/src/prerender.ts` | the rules the build and the Function share, so they cannot disagree |
| `web/scripts/build.mts` | renders the sitemap into `web/public/` with the production router |
| `web/wrangler.toml` | the Pages project: KV binding, vars, `PRERENDER_MAX_AGE_SECONDS` |
| `web/static/_headers`, `_redirects` | copied into every build, then extended by it |
| `worker/src/web/error-pages.ts` | the 404 and 500 pages, and who gets HTML instead of JSON |
| `worker/src/util/page-cache.ts` | the Cache API layer and `pageVary`, the identity test |
| `worker/test/pages.test.ts` | 21 tests, including the build run end to end |
| `.github/workflows/deploy-pages.yml` | gates, prerender, refuse-sample, deploy, smoke test |
