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
import { HttpError, clientIp, errorJson, json, rateLimit } from "./util/http";
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
  return json({ ok, service: "undal-worker", checks, time: new Date().toISOString() }, ok ? 200 : 503);
}

async function route(request: Request, env: Env): Promise<Response> {
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
  const allowed = await rateLimit(
    env,
    `${isApi ? "api" : isStoreApi ? "store" : "web"}:${ip}`,
    isApi ? 120 : isStoreApi ? 90 : 300,
    60,
  );
  if (!allowed) {
    return errorJson(429, "rate-limited", "Too many requests. Please try again in a minute.");
  }

  if (path === "/admin/cleanup" && request.method === "POST") return handleCleanup(env, request);

  if (path === "/" || path === "/store") return handleStoreFront(env, request);
  if (path === "/about") return handleAboutPage(env, request);
  if (path === "/basket") return handleBasket(env, request);
  if (path === "/checkout") return handleCheckout(env, request);
  if (path === "/login") return handleLoginPage(env, request);
  if (path === "/account") return handleAccountPage(env, request);
  if (path === "/robots.txt") return handleRobotsTxt(request);
  if (path === "/sitemap.xml") return handleSitemapXml(env, request);
  if (path === "/track") {
    const orderId = url.searchParams.get("id") ?? "";
    return orderId ? handleOrderTracking(orderId) : handleTrackPage("");
  }
  if (path.startsWith("/item/")) {
    return handleStoreItem(env, request, path.slice("/item/".length));
  }
  if (path === "/favicon.svg") return favicon();
  if (path === "/manifest.webmanifest") return handleManifest();
  if (path === "/sw.js") return handleServiceWorker();
  if (path.startsWith("/icons/")) return handleIcon(path);
  if (path === "/.well-known/assetlinks.json") return handleAssetLinks(env);
  if (path === "/download") return handleDownloadPage(env, request);
  if (path === "/download/apk") return handleDownload(env);
  if (path === "/latest.json") return handleLatestJson(env);
  if (path === "/app" || path === "/open") return handleSmartOpen(env, request, "");

  if (path.startsWith("/dish/")) {
    return handleItemSharePage(env, request, "dish", path.slice("/dish/".length));
  }
  if (path.startsWith("/combo/")) {
    return handleItemSharePage(env, request, "combo", path.slice("/combo/".length));
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

  return errorJson(404, "not-found", `No route for ${request.method} ${path}`);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await route(request, env);
    } catch (error) {
      if (error instanceof HttpError) {
        // The hint is the part a merchant can act on. Dropping it here is what
        // turned "Firestore needs a composite index, create it at <link>" into
        // "Cleanup paused (HTTP 502). You can safely try again."
        return errorJson(error.status, error.code, error.message, {
          hint: error.hint,
          detail: error.detail,
        });
      }
      console.error("undal-worker failure", error);
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
