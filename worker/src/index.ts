/**
 * Undal HomeKitchen — single Worker router.
 *
 * Surfaces in one deployment:
 *   /                        the web store: live menu, search, basket, checkout
 *   /dish/{id} /combo/{id}   share cards + smart open
 *   /order/{id} /app         deep-link smart open
 *   /store /item/{id} /basket /checkout /track   the storefront pages
 *   /login                   the account the app already has, on the web
 *   /about                   who the kitchen is, hours, delivery economics
 *   /download                install page with QR codes · /download/apk is the file
 *   /manifest.webmanifest /sw.js /icons/*   installable-web layer (P-014)
 *   /latest.json             in-app update check
 *   /.well-known/assetlinks.json  App Links verification
 *   /notify/*                FCM HTTP v1 relay (ID-token protected)
 *   /admin/release           release webhook (shared secret)
 *   /api/store/*             session or sign-in, menu snapshot, order, status
 *
 * Cron triggers: *​/5 presence watchdog · 0 12 * * * (18:00 Asia/Dhaka) promo blast.
 */
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
import { handleDownload, handleLatestJson, handleReleaseWebhook } from "./routes/download";
import {
  handleStoreMenuApi,
  handleStoreOrder,
  handleStoreOrderStatus,
  handleStoreSession,
  handleStoreSignIn,
  looksLikeOrderId,
} from "./routes/store";
import { handleAboutPage } from "./web/landing";
import {
  handleBasket,
  handleCheckout,
  handleLoginPage,
  handleOrderTracking,
  handleStoreFront,
  handleStoreItem,
  handleTrackPage,
} from "./web/store-pages";
import { handleDownloadPage } from "./web/download-page";
import { handleIcon, handleManifest, handleServiceWorker } from "./web/pwa";
import { handleItemSharePage, handleOrderOpenPage, handleSmartOpen } from "./web/deeplink";
import { favicon } from "./web/layout";
import { runPresenceCheck } from "./crons/presence";
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
  const path = url.pathname.replace(/\/+$/, "") || "/";
  const ip = clientIp(request);

  if (request.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "access-control-allow-headers": "authorization,content-type,x-undal-release-secret",
        "access-control-allow-methods": "GET,POST,OPTIONS",
        "access-control-max-age": "86400",
      },
    });
  }

  if (path === "/healthz") return health(env);

  const isApi = path.startsWith("/notify/") || path.startsWith("/admin/");
  const isStoreApi = path.startsWith("/api/store/");
  const allowed = await rateLimit(
    env,
    `${isApi ? "api" : isStoreApi ? "store" : "web"}:${ip}`,
    isApi ? 120 : isStoreApi ? 90 : 300,
    60,
  );
  if (!allowed) {
    return errorJson(429, "rate-limited", "Too many requests. Please try again in a minute.");
  }

  if (path === "/" || path === "/store") return handleStoreFront(env, request);
  if (path === "/about") return handleAboutPage(env, request);
  if (path === "/basket") return handleBasket(env);
  if (path === "/checkout") return handleCheckout(env);
  if (path === "/login") return handleLoginPage(env, request);
  if (path === "/track") {
    const orderId = url.searchParams.get("id") ?? "";
    return orderId ? handleOrderTracking(orderId) : handleTrackPage("");
  }
  if (path.startsWith("/item/")) {
    return handleStoreItem(env, path.slice("/item/".length));
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
    if (path === "/notify/order-created") return handleOrderCreated(env, request);
    if (path === "/notify/order-status") return handleOrderStatus(env, request);
    if (path === "/notify/chat") return handleChat(env, request);
    if (path === "/notify/vendor-self") return handleVendorSelf(env, request);
    if (path === "/notify/test-alert") return handleTestAlert(env, request);
    if (path === "/admin/release") return handleReleaseWebhook(env, request);
    if (path === "/api/store/session") return handleStoreSession(env);
    if (path === "/api/store/signin") return handleStoreSignIn(env, request);
    if (path === "/api/store/order") return handleStoreOrder(env, request);
  }

  if (path === "/api/store/menu") return handleStoreMenuApi(env);
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
        return errorJson(error.status, error.code, error.message);
      }
      console.error("undal-worker failure", error);
      return errorJson(500, "internal-error", "The Undal worker hit an unexpected error.");
    }
  },

  async scheduled(controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    const cron = controller.cron;
    if (cron === PRESENCE_CRON) {
      ctx.waitUntil(
        runPresenceCheck(env)
          .then((result) => console.log("undal-cron presence", JSON.stringify(result)))
          .catch((error) => console.error("undal-cron presence failed", error)),
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
