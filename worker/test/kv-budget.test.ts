/**
 * The KV budget: what a page view, a cron and an attack actually cost.
 *
 * Every assertion here is a number of Cloudflare KV operations, because that is
 * the ledger with a hard daily limit on it — 100,000 reads, 1,000 writes and
 * 1,000 deletes on the free tier, for the whole account. A regression in these
 * counts is not a performance nit; it is the site refusing writes by lunchtime.
 */
import assert from "node:assert/strict";
import { test } from "node:test";
import type { Env } from "../src/env";
import { accessToken } from "../src/gcp";
import { rateLimitDecision, resetRateLimitMemory } from "../src/util/http";
import {
  memoDelete,
  memoGet,
  memoPut,
  resetKvUsage,
  kvUsage,
  singleFlight,
  type KvBinding,
} from "../src/util/kv";
import { edgePage, fingerprint, pageVary } from "../src/util/page-cache";
import { runRealertSweep } from "../src/crons/realert";
import { webMenu } from "../src/web/store-data";
import { FakeFirebase } from "./helpers/fake-firebase";

/** A KV namespace that counts every operation it is asked for. */
class CountingKv implements KvBinding {
  readonly store = new Map<string, string>();
  reads = 0;
  writes = 0;
  deletes = 0;
  /** Set to make every call fail: the gateway must degrade, never throw. */
  failEverything = false;

  async get(key: string, type?: "text" | "json"): Promise<unknown> {
    this.reads += 1;
    if (this.failEverything) throw new Error("kv unavailable");
    const value = this.store.get(key);
    if (value === undefined) return null;
    return type === "json" ? JSON.parse(value) : value;
  }
  async put(key: string, value: string): Promise<void> {
    this.writes += 1;
    if (this.failEverything) throw new Error("kv unavailable");
    this.store.set(key, value);
  }
  async delete(key: string): Promise<void> {
    this.deletes += 1;
    if (this.failEverything) throw new Error("kv unavailable");
    this.store.delete(key);
  }
}

const envWith = (kv: KvBinding): Env => ({ UNDAL_KV: kv } as unknown as Env);

/**
 * A counting namespace in front of the fake Firebase: the Worker's own credential
 * handling, with every KV operation tallied. The token is seeded the way
 * `FakeFirebase` seeds it, so no test here pays for a JWT it cannot sign.
 */
function countingEnv(backend: FakeFirebase): { env: Env; kv: CountingKv } {
  const kv = new CountingKv();
  kv.store.set("oauth:token", "fake-access-token-0123456789abcdef");
  kv.store.set("certs:securetoken", JSON.stringify(backend.signer.certificates()));
  return { env: { ...backend.env, UNDAL_KV: kv } as unknown as Env, kv };
}

test("a quiet caller costs no KV operations at all", async () => {
  const kv = new CountingKv();
  resetRateLimitMemory();
  for (let index = 0; index < 100; index += 1) {
    const decision = await rateLimitDecision(envWith(kv), "web:203.0.113.7", 300, 60);
    assert.equal(decision.allowed, true);
    assert.equal(decision.source, "memory");
  }
  assert.equal(kv.reads, 0, "one hundred page views, no KV read");
  assert.equal(kv.writes, 0, "and no KV write: the free tier's 1,000 a day survives a busy hour");
});

test("a caller past the escalation point is counted fleet-wide, and is still stopped", async () => {
  const kv = new CountingKv();
  resetRateLimitMemory();
  const limit = 10;
  let allowed = 0;
  let refused = 0;
  let escalatedFrom = -1;
  for (let index = 1; index <= limit + 4; index += 1) {
    const decision = await rateLimitDecision(envWith(kv), "web:198.51.100.9", limit, 60);
    if (decision.source === "kv" && escalatedFrom < 0) escalatedFrom = index;
    if (decision.allowed) allowed += 1;
    else refused += 1;
  }
  assert.equal(allowed, limit, "the limit is the limit, whichever tier counted it");
  assert.equal(refused, 4);
  assert.ok(escalatedFrom > 1 && escalatedFrom <= Math.ceil(limit * 0.6) + 2, `escalation began at request ${escalatedFrom}`);
  assert.ok(kv.reads > 0, "an escalated bucket asks the fleet what it already knows");
  assert.ok(kv.writes > 0 && kv.writes <= 4, `and publishes sparingly (${kv.writes} writes for ${limit + 4} requests)`);
  assert.equal(Number(kv.store.get(`rl:web:198.51.100.9:${Math.floor(Date.now() / 60000)}`)) > 0, true);
});

test("two isolates sharing one namespace stop one caller between them", async () => {
  const kv = new CountingKv();
  resetRateLimitMemory();
  // Two binding objects over one store: what a caller spread across two datacentres
  // looks like. Neither isolate may let through more than the shared limit.
  const isolateA = envWith({ get: kv.get.bind(kv), put: kv.put.bind(kv), delete: kv.delete.bind(kv) } as KvBinding);
  const isolateB = envWith({ get: kv.get.bind(kv), put: kv.put.bind(kv), delete: kv.delete.bind(kv) } as KvBinding);
  let allowed = 0;
  for (let index = 0; index < 40; index += 1) {
    const decision = await rateLimitDecision(index % 2 === 0 ? isolateA : isolateB, "api:203.0.113.20", 10, 60);
    if (decision.allowed) allowed += 1;
  }
  assert.ok(allowed <= 14, `the fleet-wide count held: ${allowed} of 40 requests were allowed against a limit of 10`);
  assert.ok(allowed >= 10, "and honest counting never refuses a request inside the limit");
});

test("a KV outage degrades the limiter instead of throwing", async () => {
  const kv = new CountingKv();
  kv.failEverything = true;
  resetRateLimitMemory();
  for (let index = 0; index < 30; index += 1) {
    const decision = await rateLimitDecision(envWith(kv), "web:192.0.2.5", 10, 60);
    assert.equal(typeof decision.allowed, "boolean");
  }
  const usage = kvUsage();
  assert.ok(usage.failures > 0, "the failure is counted, not hidden");
});

test("the menu snapshot is read from KV once per isolate, not once per page view", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    backend.setDocument("config/store", { storeName: "Undal HomeKitchen", minOrderAmountPaisa: 15000 });
    backend.setDocument("menuItems/kacchi", { name: "Kacchi biryani", amountPaisa: 32000, available: true, categoryId: "rice" });
    const { env, kv: counting } = countingEnv(backend);
    const first = await webMenu(env);
    assert.equal(first.dishes.length, 1);
    const readsAfterFirst = counting.reads;
    const writesAfterFirst = counting.writes;
    assert.equal(writesAfterFirst, 1, "a cold isolate publishes the snapshot once");
    assert.ok(readsAfterFirst >= 1, "and reads what KV already had");

    for (let index = 0; index < 50; index += 1) await webMenu(env);
    assert.equal(counting.reads, readsAfterFirst, "fifty more page views cost no further KV read");
    assert.equal(counting.writes, writesAfterFirst, "and no further write");

    // A different isolate — a different binding over the same namespace — reads
    // the snapshot the first one published instead of rebuilding it from Firestore.
    const firestoreCallsBefore = backend.log.filter((entry) => entry.includes(":runQuery")).length;
    const other = envWith({
      get: counting.get.bind(counting),
      put: counting.put.bind(counting),
      delete: counting.delete.bind(counting),
    } as KvBinding);
    const second = await webMenu(other);
    assert.equal(second.dishes.length, 1, "the published snapshot is the menu the next isolate serves");
    const firestoreCallsAfter = backend.log.filter((entry) => entry.includes(":runQuery")).length;
    assert.equal(firestoreCallsAfter, firestoreCallsBefore, "and Firestore was not asked again");
  } finally {
    restore();
  }
});

test("an expired snapshot is rebuilt once, however many requests arrive together", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    backend.setDocument("config/store", { storeName: "Undal HomeKitchen" });
    const { env, kv: counting } = countingEnv(backend);
    const stale = { fetchedAt: Date.now() - 120_000, sections: [], subsections: [], dishes: [], combos: [], campaigns: [], coupons: [], store: null };
    counting.store.set("web:menu:v1", JSON.stringify(stale));
    const results = await Promise.all([webMenu(env), webMenu(env), webMenu(env), webMenu(env), webMenu(env)]);
    for (const menu of results) assert.ok(menu.fetchedAt > stale.fetchedAt);
    // One rebuild is six collection queries — categories, subcategories,
    // menuItems, combos, campaigns and coupons. Five simultaneous requests that
    // each rebuilt it would be thirty.
    const queries = backend.log.filter((entry) => entry.includes(":runQuery")).length;
    assert.equal(queries, 6, `five simultaneous requests rebuilt the menu ${queries / 6} time(s)`);
    assert.equal(counting.writes, 1, "and published it once");
  } finally {
    restore();
  }
});

test("an unreadable catalogue is an error, not a menu with nothing on it", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const env = {
      UNDAL_KV: new CountingKv(),
      FIREBASE_SA_JSON: "{ not json",
    } as unknown as Env;
    await assert.rejects(() => webMenu(env), /catalogue-unavailable/);
  } finally {
    restore();
  }
});

test("the OAuth token is read once per isolate, and one mint is shared", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const counting = new CountingKv();
    counting.store.set("oauth:token", "fake-access-token-0123456789abcdef");
    const env = { ...backend.env, UNDAL_KV: counting } as unknown as Env;
    const tokens = await Promise.all([accessToken(env), accessToken(env), accessToken(env)]);
    for (const token of tokens) assert.equal(token, "fake-access-token-0123456789abcdef");
    assert.equal(counting.reads, 1, "three Google calls, one KV read");
    for (let index = 0; index < 40; index += 1) await accessToken(env);
    assert.equal(counting.reads, 1, "and forty more cost nothing");
  } finally {
    restore();
  }
});

test("the gateway coalesces a repeated write and brakes optional ones", async () => {
  const kv = new CountingKv();
  resetKvUsage();
  assert.equal(await memoPut(kv, "web:menu:v1", '{"a":1}', { expirationTtl: 60 }), true);
  assert.equal(kv.writes, 1);
  assert.equal(await memoPut(kv, "web:menu:v1", '{"a":1}', { expirationTtl: 60 }), false, "the same value again is not a write");
  assert.equal(kv.writes, 1);
  assert.equal(await memoPut(kv, "web:menu:v1", '{"a":2}', { expirationTtl: 60 }), true, "a new value is");
  assert.equal(kv.writes, 2);

  // The brake: optional writes stop at the per-minute ceiling, required ones never do.
  for (let index = 0; index < 10; index += 1) {
    await memoPut(kv, `optional:${index}`, String(index), { expirationTtl: 60 });
  }
  const afterOptional = kv.writes;
  assert.ok(afterOptional <= 6, `optional writes were braked (${afterOptional} in one minute)`);
  assert.equal(await memoPut(kv, "alerts:cursor", "orders/abc", { required: true }), true);
  assert.equal(kv.writes, afterOptional + 1, "a required write is never the one that is refused");
  assert.ok(kvUsage().refusedWrites > 0, "and the refusals are counted where /healthz can see them");
});

test("a delete is only spent when the caller knows the key is there", async () => {
  const kv = new CountingKv();
  await memoDelete(kv, "alerts:realert-cursor:v1", { required: true, present: false });
  assert.equal(kv.deletes, 0, "1,440 sweeps a day used to spend a delete each on a key that was never set");
  await memoDelete(kv, "alerts:realert-cursor:v1", { required: true, present: true });
  assert.equal(kv.deletes, 1);
});

test("the re-alert sweep spends no delete on a short page and rotates a full one", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { env, kv: counting } = countingEnv(backend);
    backend.setDocument("orders/one", { status: "placed", createdAt: new Date(Date.now() - 120_000).toISOString(), shortId: "UND1" });
    await runRealertSweep(env);
    assert.equal(counting.deletes, 0, "a sweep that found no cursor deletes nothing");

    for (let index = 0; index < 51; index += 1) {
      backend.setDocument(`orders/old-${String(index).padStart(3, "0")}`, {
        status: "placed", createdAt: new Date(Date.now() - 86_400_000).toISOString(), shortId: "OLD",
      });
    }
    await runRealertSweep(env);
    assert.equal(counting.writes, 1, "a full page publishes where the next sweep starts");
    assert.ok(counting.store.get("alerts:realert-cursor:v1")?.startsWith("orders/old-"));
    await runRealertSweep(env);
    assert.equal(counting.deletes, 1, "and a short page after it clears the rotation");
  } finally {
    restore();
  }
});

test("single-flight runs one job for everybody waiting on it", async () => {
  const marker = {};
  let runs = 0;
  const results = await Promise.all(
    [0, 1, 2, 3, 4].map(() =>
      singleFlight(marker, "menu", async () => {
        runs += 1;
        await new Promise((resolve) => setTimeout(resolve, 5));
        return runs;
      }),
    ),
  );
  assert.equal(runs, 1);
  assert.deepEqual(results, [1, 1, 1, 1, 1]);
});

test("the edge cache serves a second visitor without rendering the page", async () => {
  const fake = installFakeCacheApi();
  try {
    let renders = 0;
    const render = async (): Promise<Response> => {
      renders += 1;
      return new Response(`<html><body>menu ${renders}</body></html>`, {
        status: 200,
        headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=30" },
      });
    };
    const request = () => new Request("https://undal.test/store");
    const first = await edgePage({ request: request(), ttlSeconds: 60, render });
    assert.equal(first.headers.get("x-undal-edge"), "miss");
    assert.equal(await first.text(), "<html><body>menu 1</body></html>");

    const second = await edgePage({ request: request(), ttlSeconds: 60, render });
    assert.equal(second.headers.get("x-undal-edge"), "hit");
    assert.equal(await second.text(), "<html><body>menu 1</body></html>");
    assert.equal(renders, 1, "the renderer ran once for two page views");

    // A different basket is a different page, and never shares an entry.
    const withBasket = new Request("https://undal.test/store", { headers: { cookie: "undal_basket=abc" } });
    assert.equal(pageVary(withBasket, ["undal_basket"]), "undal_basket=abc");
    const third = await edgePage({ request: withBasket, vary: "undal_basket=abc", ttlSeconds: 60, render });
    assert.equal(third.headers.get("x-undal-edge"), "miss");
    assert.equal(renders, 2);

    // A signed-in caller is never cached, and never served somebody else's page.
    const signedIn = new Request("https://undal.test/store", { headers: { cookie: "undal_session=xyz" } });
    assert.equal(pageVary(signedIn, ["undal_basket"]), null, "an unknown cookie means an unshareable request");
    const fourth = await edgePage({ request: signedIn, vary: "", bypass: true, ttlSeconds: 60, render });
    assert.equal(fourth.headers.get("x-undal-edge"), "bypass");
    assert.equal(renders, 3);

    // A page that sets a cookie is never stored.
    const setting = await edgePage({
      request: new Request("https://undal.test/track"),
      ttlSeconds: 60,
      render: async () => new Response("nope", { status: 200, headers: { "set-cookie": "undal_session=abc" } }),
    });
    assert.equal(setting.headers.get("x-undal-edge"), "miss");
    assert.equal(fake.puts.filter((key) => key.includes("/track")).length, 0, "a Set-Cookie response is not stored");
  } finally {
    fake.restore();
  }
});

test("a stale entry is served at once and refreshed behind it", async () => {
  const fake = installFakeCacheApi();
  try {
    let renders = 0;
    const render = async (): Promise<Response> => {
      renders += 1;
      return new Response(`menu ${renders}`, { status: 200, headers: { "cache-control": "public, max-age=30" } });
    };
    const request = new Request("https://undal.test/about");
    await edgePage({ request, ttlSeconds: 60, render });
    // Backdate the stored entry past its lifetime but inside the stale window.
    fake.backdate("https://undal.test/about", 120);
    const waiting: Array<Promise<unknown>> = [];
    const served = await edgePage({
      request,
      ttlSeconds: 60,
      staleSeconds: 900,
      ctx: { waitUntil: (promise) => waiting.push(promise) },
      render,
    });
    assert.equal(served.headers.get("x-undal-edge"), "stale");
    assert.equal(await served.text(), "menu 1", "the customer is not made to wait for the refresh");
    await Promise.all(waiting);
    assert.equal(renders, 2, "and the next caller gets the new page");
  } finally {
    fake.restore();
  }
});

test("a cache that fails or is absent still serves the page", async () => {
  const fake = installFakeCacheApi();
  fake.failEverything = true;
  try {
    const response = await edgePage({
      request: new Request("https://undal.test/store"),
      ttlSeconds: 60,
      render: async () => new Response("the menu", { status: 200 }),
    });
    assert.equal(response.status, 200);
    assert.equal(await response.text(), "the menu");
  } finally {
    fake.restore();
  }
  const withoutCacheApi = await edgePage({
    request: new Request("https://undal.test/store"),
    ttlSeconds: 60,
    render: async () => new Response("the menu", { status: 200 }),
  });
  assert.equal(withoutCacheApi.headers.get("x-undal-edge"), "unavailable");
  assert.equal(await withoutCacheApi.text(), "the menu");
});

test("the cache key keeps two baskets apart and ignores nothing else", () => {
  assert.notEqual(fingerprint("undal_basket=abc"), fingerprint("undal_basket=abd"));
  assert.equal(fingerprint("undal_basket=abc"), fingerprint("undal_basket=abc"));
  assert.equal(pageVary(new Request("https://undal.test/store"), ["undal_basket"]), "");
  assert.equal(
    pageVary(new Request("https://undal.test/store", { headers: { authorization: "Bearer x" } }), ["undal_basket"]),
    null,
  );
});

// --------------------------------------------------------------------- helpers

/** A stand-in for `caches.default`, which only exists inside a Worker. */
interface FakeCacheApi {
  puts: string[];
  failEverything: boolean;
  restore: () => void;
  /** Rewrites an entry's stored timestamp, so "stale" can be tested without waiting. */
  backdate: (url: string, seconds: number) => void;
}

function installFakeCacheApi(): FakeCacheApi {
  const entries = new Map<string, { body: string; headers: Headers; status: number }>();
  const previous = (globalThis as { caches?: unknown }).caches;
  const state: FakeCacheApi = {
    puts: [],
    failEverything: false,
    restore: () => {
      (globalThis as { caches?: unknown }).caches = previous;
    },
    backdate: (url: string, seconds: number) => {
      const entry = entries.get(url);
      if (!entry) return;
      entry.headers.set("x-undal-cached-at", String(Date.now() - seconds * 1000));
    },
  };
  const fakeCache = {
    match: async (request: Request): Promise<Response | undefined> => {
      if (state.failEverything) throw new Error("cache unavailable");
      const entry = entries.get(request.url);
      if (!entry) return undefined;
      return new Response(entry.body, { status: entry.status, headers: entry.headers });
    },
    put: async (request: Request, response: Response): Promise<void> => {
      if (state.failEverything) throw new Error("cache unavailable");
      state.puts.push(request.url);
      entries.set(request.url, {
        body: await response.text(),
        headers: new Headers(response.headers),
        status: response.status,
      });
    },
  };
  (globalThis as { caches?: { default?: unknown } }).caches = { default: fakeCache };
  return state;
}
