/**
 * Undal HomeKitchen — single Worker router.
 *
 * Surfaces in one deployment:
 *   /                        web store front (live open/closed state)
 *   /dish/{id} /combo/{id}   share cards + smart open
 *   /order/{id} /app         deep-link smart open
 *   /download /latest.json   APK delivery + in-app update check
 *   /.well-known/assetlinks.json  App Links verification
 *   /notify/*                FCM HTTP v1 relay (ID-token protected)
 *   /admin/release           release webhook (shared secret)
 *
 * Cron triggers: *​/5 presence watchdog · 0 12 * * * (18:00 Asia/Dhaka) promo blast.
 */
import type { Env } from "./env";
import { projectId } from "./env";
import { HttpError, clientIp, errorJson, json, rateLimit } from "./util/http";
import { handleChat, handleOrderCreated, handleOrderStatus, handleVendorSelf } from "./routes/notify";
import { handleAssetLinks } from "./routes/wellknown";
import { handleDownload, handleLatestJson, handleReleaseWebhook } from "./routes/download";
import { handleLanding } from "./web/landing";
import { handleItemSharePage, handleOrderOpenPage, handleSmartOpen } from "./web/deeplink";
import { favicon } from "./web/layout";
import { runPresenceCheck } from "./crons/presence";
import { runPromoBlast } from "./crons/promo";

const PRESENCE_CRON = "*/5 * * * *";
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
  const allowed = await rateLimit(env, `${isApi ? "api" : "web"}:${ip}`, isApi ? 120 : 300, 60);
  if (!allowed) {
    return errorJson(429, "rate-limited", "Too many requests. Please try again in a minute.");
  }

  if (path === "/" || path === "/store") return handleLanding(env, request);
  if (path === "/favicon.svg") return favicon();
  if (path === "/.well-known/assetlinks.json") return handleAssetLinks(env);
  if (path === "/download") return handleDownload(env);
  if (path === "/latest.json") return handleLatestJson(env);
  if (path === "/app" || path === "/open") return handleSmartOpen(env, request, "");

  if (path.startsWith("/dish/")) {
    return handleItemSharePage(env, request, "dish", path.slice("/dish/".length));
  }
  if (path.startsWith("/combo/")) {
    return handleItemSharePage(env, request, "combo", path.slice("/combo/".length));
  }
  if (path.startsWith("/order/")) {
    return handleOrderOpenPage(env, request, path.slice("/order/".length));
  }

  if (request.method === "POST") {
    if (path === "/notify/order-created") return handleOrderCreated(env, request);
    if (path === "/notify/order-status") return handleOrderStatus(env, request);
    if (path === "/notify/chat") return handleChat(env, request);
    if (path === "/notify/vendor-self") return handleVendorSelf(env, request);
    if (path === "/admin/release") return handleReleaseWebhook(env, request);
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
