import assert from "node:assert/strict";
import { test } from "node:test";
import { onRequest } from "../../admin/functions/[[path]]";
import type { PagesContext } from "../../web/src/serve";
import worker from "../src/index";

function context(path: string, next: () => Promise<Response>): PagesContext {
  return {
    request: new Request(`https://undal-admin.pages.dev${path}`),
    env: {},
    next,
    waitUntil: () => undefined,
    passThroughOnException: () => undefined,
  } as unknown as PagesContext;
}

test("admin Pages serves static assets itself and forwards only admin API calls", async () => {
  const originalFetch = worker.fetch;
  let workerCalls = 0;
  let forwardedExecution = false;
  worker.fetch = async (_request, _env, execution) => {
    workerCalls += 1;
    execution.waitUntil(Promise.resolve());
    execution.passThroughOnException();
    forwardedExecution = true;
    return new Response("worker-control-plane");
  };
  try {
    for (const path of ["/", "/app.js", "/app.css", "/branch-map.js", "/api/store/order"]) {
      let nextCalls = 0;
      const response = await onRequest(context(path, async () => {
        nextCalls += 1;
        return new Response("pages-static");
      }));
      assert.equal(nextCalls, 1, path);
      assert.equal(await response.text(), "pages-static", path);
    }
    assert.equal(workerCalls, 0);

    const api = await onRequest(context("/api/admin/overview", async () => {
      throw new Error("an admin API must not fall through to a static page");
    }));
    assert.equal(await api.text(), "worker-control-plane");
    assert.equal(workerCalls, 1);
    assert.equal(forwardedExecution, true);
  } finally {
    worker.fetch = originalFetch;
  }
});
