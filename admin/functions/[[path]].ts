// The admin Pages Function runs the same authenticated Worker control plane.
// Static assets are served by Pages; only /api/* reaches this function.
import worker from "../../worker/src/index";
import type { PagesContext } from "../../web/src/serve";

export async function onRequest(context: PagesContext): Promise<Response> {
  return worker.fetch(context.request, context.env, {
    waitUntil: (promise: Promise<unknown>) => context.waitUntil(promise),
    passThroughOnException: () => context.passThroughOnException(),
  } as ExecutionContext);
}
