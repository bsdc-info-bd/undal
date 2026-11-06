// The admin Pages Function runs the same authenticated Worker control plane.
// Static assets are served by Pages; only /api/* reaches this function.
import worker from "../../worker/src/index";
import type { PagesContext } from "../../web/src/serve";

export async function onRequest(context: PagesContext): Promise<Response> {
  // A catch-all Pages Function otherwise intercepts /app.js, /app.css and the
  // login HTML too. Only the authenticated control-plane API belongs to the
  // Worker; every other byte must continue to Pages' static asset handling.
  if (!new URL(context.request.url).pathname.startsWith("/api/admin/")) return context.next();
  return worker.fetch(context.request, context.env, {
    waitUntil: (promise: Promise<unknown>) => context.waitUntil(promise),
    passThroughOnException: () => context.passThroughOnException(),
  } as ExecutionContext);
}
