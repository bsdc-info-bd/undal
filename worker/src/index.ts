/**
 * Undal HomeKitchen — single Worker router.
 *
 * Surfaces in one deployment:
 *   /                        the web store: live menu, search, basket, checkout
 *   /dish/{id} /combo/{id}   share cards + smart open
 *   /order/{id} /app         deep-link smart open
 *   /store /item/{id} /basket /checkout /track   the storefront pages
 *   /login                   sign in, sign up, reset a password
 *   /account                 profile, address book, order history, security
 *   /about                   who the kitchen is, hours, delivery economics
 *   /robots.txt /sitemap.xml crawler routes, built from the live catalogue
 *   /download                install page with QR codes · /download/apk is the file
 *   /manifest.webmanifest /sw.js /icons/*   installable-web layer (P-014)
 *   /latest.json             in-app update check
 *   /.well-known/assetlinks.json  App Links verification
 *   /notify/*                FCM HTTP v1 relay (ID-token protected)
 *   /admin/release           release webhook (shared secret)
 *   /api/store/*             session or sign-in, menu snapshot, order, status
 *   /api/account/*           the web account: profile, addresses, orders, close
 *
 * Cron triggers: *​/5 presence watchdog · 0 12 * * * (18:00 Asia/Dhaka) promo blast.
 */
import { handleCleanup } from "./routes/cleanup";
import type { Env } from "./env";
import { projectId } from "./env";
import { HttpError, clientIp, errorJson, json, rateLimitDecision } from "./util/http";
import { kvSavings, kvUsage } from "./util/kv";
import { edgePage, pageVary } from "./util/page-cache";
import { BASKET_COOKIE } from "./web/forms";
import {
  handleChat,
  handleOrderCreated,
  handleOrderStatus,
  handleTestAlert,
  handleVendorSelf,
} from "./routes/notify";
import { handleAssetLinks } from "./routes/wellknown";
import { handleAccountForm, handleBasketForm, handleCheckoutPlace, handleCookieSession } from "./routes/webfallback";
import { handleDownload, handleLatestJson, handleReleaseWebhook } from "./routes/download";
import {
  handleStoreCouponsApi,
  handleStoreMenuApi,
  handleStoreOrder,
  handleStoreOrderStatus,
  handleStoreSession,
  handleStoreSignIn,
  looksLikeOrderId,
} from "./routes/store";
import {
  handleAboutPage,
  handleBasket,
  handleCheckout,
  handleOrderTracking,
  handleStoreFront,
  handleStoreItem,
  handleTrackPage,
} from "./web/store-pages";
import { handleAccountPage, handleLoginPage } from "./web/account-page";
import {
  handleAccountAddressDelete,
  handleAccountAddressSave,
  handleAccountAddressesGet,
  handleAccountClose,
  handleAccountOrders,
  handleAccountPassword,
  handleAccountProfileGet,
  handleAccountProfileSave,
  handleAccountRefresh,
  handleAccountReset,
  handleAccountSignUp,
} from "./routes/account";
import { handleRobotsTxt, handleSitemapXml } from "./web/seo";
import { handleDownloadPage } from "./web/download-page";
import { handleIcon, handleManifest, handleServiceWorker } from "./web/pwa";
import { handleItemSharePage, handleOrderOpenPage, handleSmartOpen } from "./web/deeplink";
import { favicon } from "./web/layout";
import { failurePage, notFoundPage, wantsHtml } from "./web/error-pages";
import { runOpeningReminder } from "./crons/opening";
import { runPresenceSweep } from "./crons/presence";
import { runRealertSweep } from "./crons/realert";
import { runPromoBlast } from "./crons/promo";

const PRESENCE_CRON = "*/5 * * * *";
/**
 * Every minute, on the minute: re-alert orders that are still unanswered.
 *
 * Cloudflare's minimum granularity is one minute, so this is the fastest a
 * server-side repeat can be. The phone-side watcher covers the seconds between.
 */
const REALERT_CRON = "* * * * *";
const PROMO_CRON = "0 12 * * *";

async function health(env: Env): Promise<Response> {
  const checks: Record<string, string> = {};
  try {
    await env.UNDAL_KV.get("healthz:probe");
    checks.kv = "ok";
  } catch {
    checks.kv = "error";
  }
  try {
    checks.serviceAccount = projectId(env).length > 0 ? "ok" : "error";
  } catch {
    checks.serviceAccount = "error";
  }
  checks.rtdb = env.FIREBASE_RTDB_URL?.startsWith("https://") ? "ok" : "error";
  checks.webSignIn = (env.FIREBASE_WEB_API_KEY ?? "").length > 0 ? "ok" : "missing-web-api-key";
  checks.assetLinks = (env.APP_SIGNING_SHA256 ?? "").length > 0 ? "ok" : "pending-first-signed-build";

  const ok = checks.kv === "ok" && checks.serviceAccount === "ok" && checks.rtdb === "ok";
  return json(
    {
      ok,
      service: "undal-worker",
      checks,
      // This isolate's own KV spend. The free tier is 100,000 reads and 1,000
      // writes a day for the whole account, so the question "are we going to hit
      // the limit?" needs a number to look at, not a theory. `savings` is what the
      // gateway's memory tier and write brake avoided spending.
      kv: { usage: kvUsage(), savings: kvSavings() },
      time: new Date().toISOString(),
    },
    ok ? 200 : 503,
  );
}

/**
 * Paths whose rendering is public: the same HTML for everybody, no identity, no
 * money. Everything a customer can act on — the basket, the checkout, the account,
 * an order's live status — is deliberately absent, and so is every `/api/` route.
 */
function edgeCacheable(path: string): boolean {
  if (path === "/" || path === "/store" || path === "/about" || path === "/download") return true;
  if (path === "/robots.txt" || path === "/sitemap.xml") return true;
  return (
    path.startsWith("/item/") || path.startsWith("/dish/") || path.startsWith("/combo/")
  );
}

/** Files whose bytes never change inside a deployment: no counting, no rendering. */
function staticAsset(path: string): boolean {
  return (
    path.startsWith("/icons/") ||
    path === "/favicon.svg" ||
    path === "/manifest.webmanifest" ||
    path === "/sw.js" ||
    path === "/apple-touch-icon.png"
  );
}

/**
 * Renders a public page through Cloudflare's Cache API.
 *
 * The basket cookie is part of the page (the no-script storefront prints the
 * basket the server read), so it is part of the key; a request carrying any other
 * cookie — a session above all — bypasses the cache instead of joining it.
 */
function edgeCached(
  request: Request,
  ctx: ExecutionContext | undefined,
  render: () => Promise<Response>,
  ttlSeconds = 60,
): Promise<Response> {
  const vary = pageVary(request, [BASKET_COOKIE]);
  // Belt and braces: a path that is not on the public list is never cached, even
  // if a future route wires it here by mistake.
  const bypass = vary === null || !edgeCacheable(new URL(request.url).pathname.replace(/\/{2,}/g, "/").replace(/\/+$/, "") || "/");
  return edgePage({
    request,
    vary: vary ?? "",
    bypass,
    ttlSeconds,
    staleSeconds: 900,
    ctx,
    render,
  });
}

async function route(request: Request, env: Env, ctx?: ExecutionContext): Promise<Response> {
  const url = new URL(request.url);
  // Collapse duplicate slashes before matching. A base URL with a trailing slash
  // — `https://host//healthz` — used to answer "No route for GET //healthz",
  // which is how a working deployment reported itself as broken, how the release
  // webhook silently stopped updating /latest.json, and how any client with a
  // stray slash in its configuration would have been told the route does not
  // exist. One character of a client's config must never decide that.
  const path = url.pathname.replace(/\/{2,}/g, "/").replace(/\/+$/, "") || "/";
  const ip = clientIp(request);

  if (request.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "access-control-allow-headers": "authorization,content-type,x-undal-release-secret",
        "access-control-allow-methods": "GET,POST,DELETE,OPTIONS",
        "access-control-max-age": "86400",
      },
    });
  }

  if (path === "/healthz") return health(env);

  const isApi = path.startsWith("/notify/") || path.startsWith("/admin/");
  const isStoreApi = path.startsWith("/api/store/") || path.startsWith("/api/account/");
  // Icons, the manifest and the service worker are counted nowhere: they are bytes
  // that never change, they cost nothing to serve, and counting them was how an
  // installable-web page spent a customer's whole budget on its own artwork.
  const decision = staticAsset(path)
    ? null
    : await rateLimitDecision(
        env,
        `${isApi ? "api" : isStoreApi ? "store" : "web"}:${ip}`,
        isApi ? 120 : isStoreApi ? 90 : 300,
        60,
      );
  if (decision && !decision.allowed) {
    // `Retry-After` is the difference between "the site is broken" and "the site
    // is busy, come back in 40 seconds" for a client that reads it.
    return errorJson(
      429,
      "rate-limited",
      "Too many requests. Please try again in a minute.",
      { hint: `Wait ${decision.retryAfterSeconds} seconds and try again.` },
      { "retry-after": String(decision.retryAfterSeconds) },
    );
  }

  if (path === "/admin/cleanup" && request.method === "POST") return handleCleanup(env, request);

  if (path === "/" || path === "/store") return edgeCached(request, ctx, () => handleStoreFront(env, request), 30);
  if (path === "/about") return edgeCached(request, ctx, () => handleAboutPage(env, request), 60);
  if (path === "/basket") return handleBasket(env, request);
  if (path === "/checkout") return handleCheckout(env, request);
  if (path === "/login") return handleLoginPage(env, request);
  if (path === "/account") return handleAccountPage(env, request);
  if (path === "/robots.txt") return edgeCached(request, ctx, async () => handleRobotsTxt(request), 3600);
  if (path === "/sitemap.xml") return edgeCached(request, ctx, () => handleSitemapXml(env, request), 3600);
  if (path === "/track") {
    const orderId = url.searchParams.get("id") ?? "";
    return orderId ? handleOrderTracking(orderId) : handleTrackPage("");
  }
  if (path.startsWith("/item/")) {
    const itemId = path.slice("/item/".length);
    return edgeCached(request, ctx, () => handleStoreItem(env, request, itemId), 60);
  }
  if (path === "/favicon.svg") return favicon();
  if (path === "/manifest.webmanifest") return handleManifest();
  if (path === "/sw.js") return handleServiceWorker();
  if (path.startsWith("/icons/")) return handleIcon(path);
  // iOS asks for this by name when a customer puts the store on a home screen,
  // and `staticAsset()` already exempted it from the rate limiter — an exemption
  // for a path that answered 404, so every home-screen icon was the default
  // screenshot. It is the 180px drawing, which is the size Apple wants.
  if (path === "/apple-touch-icon.png") return handleIcon("/icons/icon-180.png");
  if (path === "/.well-known/assetlinks.json") return handleAssetLinks(env);
  if (path === "/download") return edgeCached(request, ctx, () => handleDownloadPage(env, request), 60);
  if (path === "/download/apk") return handleDownload(env);
  if (path === "/latest.json") return handleLatestJson(env);
  if (path === "/app" || path === "/open") return handleSmartOpen(env, request, "");

  if (path.startsWith("/dish/")) {
    const shareId = path.slice("/dish/".length);
    return edgeCached(request, ctx, () => handleItemSharePage(env, request, "dish", shareId), 60);
  }
  if (path.startsWith("/combo/")) {
    const shareId = path.slice("/combo/".length);
    return edgeCached(request, ctx, () => handleItemSharePage(env, request, "combo", shareId), 60);
  }
  if (path.startsWith("/order/")) {
    const orderId = path.slice("/order/".length).replace(/[^A-Za-z0-9]/g, "");
    if (!orderId) return errorJson(404, "order-id-missing");
    // The Android app's WebView-free deep links are opened with an `undal://`
    // intent; anything else is a human on the web and gets the status page.
    const wantsApp = (request.headers.get("user-agent") ?? "").includes("UndalApp");
    if (wantsApp) return handleOrderOpenPage(env, request, orderId);
    return handleOrderTracking(orderId);
  }

  if (request.method === "POST") {
    // The plain-HTML layer: the same actions the script performs, posted by a
    // browser that has no script at all. These are the routes that make sign-in,
    // registration, "continue as a guest" and the basket work on every phone.
    if (path === "/basket/add") return handleBasketForm(request, "add");
    if (path === "/basket/set") return handleBasketForm(request, "set");
    if (path === "/basket/remove") return handleBasketForm(request, "remove");
    if (path === "/basket/clear") return handleBasketForm(request, "clear");
    if (path === "/checkout/place") return handleCheckoutPlace(env, request);
    if (path === "/login") return handleAccountForm(env, request);

    if (path === "/notify/order-created") return handleOrderCreated(env, request);
    if (path === "/notify/order-status") return handleOrderStatus(env, request);
    if (path === "/notify/chat") return handleChat(env, request);
    if (path === "/notify/vendor-self") return handleVendorSelf(env, request);
    if (path === "/notify/test-alert") return handleTestAlert(env, request);
    if (path === "/admin/release") return handleReleaseWebhook(env, request);
    if (path === "/api/store/session") return handleStoreSession(env);
    if (path === "/api/store/signin") return handleStoreSignIn(env, request);
    if (path === "/api/store/order") return handleStoreOrder(env, request);
    if (path === "/api/account/signup") return handleAccountSignUp(env, request);
    if (path === "/api/account/reset") return handleAccountReset(env, request);
    if (path === "/api/account/refresh") return handleAccountRefresh(env, request);
    if (path === "/api/account/profile") return handleAccountProfileSave(env, request);
    if (path === "/api/account/addresses") return handleAccountAddressSave(env, request);
    if (path === "/api/account/password") return handleAccountPassword(env, request);
    if (path === "/api/account/close") return handleAccountClose(env, request);
  }

  if (request.method === "DELETE") {
    // Only the address book is deletable from the web: an order is the kitchen's
    // record and a customer cancels it through the status ladder, never a DELETE.
    if (path.startsWith("/api/account/addresses/")) {
      const id = path.slice("/api/account/addresses/".length);
      if (!/^[a-z0-9]{8,32}$/.test(id)) return errorJson(400, "address-id-invalid");
      return handleAccountAddressDelete(env, request, id);
    }
  }

  if (path === "/api/store/menu") return handleStoreMenuApi(env);
  if (path === "/api/store/coupons") return handleStoreCouponsApi(env);
  if (path === "/api/account/session") return handleCookieSession(request);
  if (path === "/api/account/profile") return handleAccountProfileGet(env, request);
  if (path === "/api/account/addresses") return handleAccountAddressesGet(env, request);
  if (path === "/api/account/orders") return handleAccountOrders(env, request);
  if (path.startsWith("/api/store/order/")) {
    const orderId = path.slice("/api/store/order/".length);
    if (!looksLikeOrderId(orderId)) return errorJson(400, "order-id-invalid");
    return handleStoreOrderStatus(env, orderId);
  }

  // A browser that followed a dead link gets a page with a way back to the menu;
  // the app, a webhook and every `/api/` caller keep the JSON they already parse.
  if (request.method === "GET" && wantsHtml(request, path)) return notFoundPage(path);
  return errorJson(404, "not-found", `No route for ${request.method} ${path}`);
}

/**
 * The path the router would have matched, recomputed for the error handlers: a
 * failure page that offers "try this page again" has to know which page it is on,
 * and the router's own `path` is not in scope inside the `fetch` wrapper.
 */
function requestPath(request: Request): string {
  return new URL(request.url).pathname.replace(/\/{2,}/g, "/").replace(/\/+$/, "") || "/";
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    try {
      return await route(request, env, ctx);
    } catch (error) {
      const path = requestPath(request);
      if (error instanceof HttpError) {
        // An upstream failure on a page a person is reading is not a JSON
        // problem: "the menu could not be read" belongs on a page that still
        // offers the basket, the app and a way to try again.
        if (error.status >= 500 && request.method === "GET" && wantsHtml(request, path)) return failurePage(path, error.status);
        // The hint is the part a merchant can act on. Dropping it here is what
        // turned "Firestore needs a composite index, create it at <link>" into
        // "Cleanup paused (HTTP 502). You can safely try again."
        return errorJson(error.status, error.code, error.message, {
          hint: error.hint,
          detail: error.detail,
        });
      }
      console.error("undal-worker failure", error);
      if (request.method === "GET" && wantsHtml(request, path)) return failurePage(path);
      return errorJson(500, "internal-error", "The Undal worker hit an unexpected error.");
    }
  },

  async scheduled(controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    const cron = controller.cron;
    if (cron === PRESENCE_CRON) {
      ctx.waitUntil(
        // Presence first — a store that has to be paused is paused before the
        // opening reminder below asks anybody to open anything.
        runPresenceSweep(env)
          .then((result) => {
            // Only the sweeps that decided something are logged: five minutes of
            // "device-present" is a log nobody reads.
            if (result.action !== "none" || result.reason === "device-never-seen") {
              console.log("undal-cron presence", JSON.stringify(result));
            }
          })
          .catch((error) => console.error("undal-cron presence failed", error)),
      );
      ctx.waitUntil(
        runOpeningReminder(env)
          .then((result) => {
            if (result.reminded) console.log("undal-cron opening", JSON.stringify(result));
          })
          .catch((error) => console.error("undal-cron opening failed", error)),
      );
      return;
    }
    if (cron === REALERT_CRON) {
      ctx.waitUntil(
        runRealertSweep(env)
          .then((result) => {
            // One line per sweep, and only when it did something: a cron that
            // logs "nothing to do" every minute is a log nobody reads.
            if (result.realerted > 0 || result.missed > 0 || result.failures.length > 0) {
              console.log("undal-cron realert", JSON.stringify(result));
            }
          })
          .catch((error) => console.error("undal-cron realert failed", error)),
      );
      return;
    }

    if (cron === PROMO_CRON) {
      ctx.waitUntil(
        runPromoBlast(env)
          .then((result) => console.log("undal-cron promo", JSON.stringify(result)))
          .catch((error) => console.error("undal-cron promo failed", error)),
      );
      return;
    }
    console.log("undal-cron unknown trigger", cron);
  },
} satisfies ExportedHandler<Env>;
