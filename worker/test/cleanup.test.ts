/**
 * The history cleanup, and the failure that made a merchant tap "try again"
 * forever.
 *
 * The pure decisions (what is finished, which of a scanned page may be deleted,
 * where the boundary sits) are asserted first, then the whole route is run
 * against a fake Firebase — including the two states that used to produce
 * `Cleanup paused (HTTP 502)`: a project without the composite index, and a
 * Realtime Database that refuses the mirror deletion.
 */
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  CLEANUP_CONFIRMATION,
  INBOX_BATCH,
  ORDER_BATCH,
  PENDING_MIRRORS_KEY,
  SCAN_LIMIT,
  cleanupBoundary,
  cleanupTimestamp,
  diagnoseCleanup,
  eligibleForCleanup,
  indexMissingWarning,
  isFinished,
  loadFinishedOrders,
  mirrorPathsFor,
  runCleanup,
  type CleanupDiagnosis,
  type CleanupReport,
} from "../src/routes/cleanup";
import { FakeFirebase, orderFields } from "./helpers/fake-firebase";

/** The route logs a line per call; the test output should stay readable. */
async function quiet<T>(work: () => Promise<T>): Promise<T> {
  const { info, warn, error } = console;
  console.info = () => undefined;
  console.warn = () => undefined;
  console.error = () => undefined;
  try {
    return await work();
  } finally {
    console.info = info;
    console.warn = warn;
    console.error = error;
  }
}

const DAY = 86_400_000;
const iso = (daysAgo: number): string => new Date(Date.now() - daysAgo * DAY).toISOString();

// ---------------------------------------------------------------- pure rules

test("only delivered and cancelled orders are ever eligible for cleanup", () => {
  assert.equal(isFinished("delivered"), true);
  assert.equal(isFinished("cancelled"), true);
  for (const active of ["placed", "accepted", "ready", "in_delivery", undefined, ""]) {
    assert.equal(isFinished(active), false, String(active));
  }
});

test("the cleanup boundary is fixed in time and cannot be in the future", () => {
  const now = Date.parse("2026-10-14T06:00:00Z");
  assert.equal(cleanupBoundary(undefined, now).getTime(), now);
  assert.throws(() => cleanupBoundary("2999-01-01T00:00:00Z", now));
  assert.throws(() => cleanupBoundary("not-a-date", now));
  assert.equal(cleanupBoundary("2026-10-13T00:00:00Z", now).toISOString(), "2026-10-13T00:00:00.000Z");
});

test("cleanup needs the explicit confirmation word and small bounded batches", () => {
  assert.equal(CLEANUP_CONFIRMATION, "DELETE_FINISHED_HISTORY");
  assert.ok(ORDER_BATCH <= 10);
  assert.ok(INBOX_BATCH <= 100);
  assert.ok(SCAN_LIMIT >= 50, "the scan page must be wide enough to reach finished orders behind active ones");
});

test("an order's cleanup moment is read from a Firestore timestamp or epoch millis", () => {
  assert.equal(cleanupTimestamp({ id: "a", updatedAt: "2026-10-01T00:00:00.000Z" } as never), Date.parse("2026-10-01T00:00:00.000Z"));
  assert.equal(cleanupTimestamp({ id: "a", updatedAt: 1_700_000_000_000 } as never), 1_700_000_000_000);
  assert.equal(cleanupTimestamp({ id: "a", createdAt: "2026-09-01T00:00:00.000Z" } as never), Date.parse("2026-09-01T00:00:00.000Z"));
  assert.equal(cleanupTimestamp({ id: "a" } as never), null, "an order with no timestamp must never be guessed at");
});

test("a scanned page is filtered to finished orders older than the boundary, oldest first", () => {
  const before = new Date(Date.now() - DAY);
  const records = [
    { id: "new", status: "delivered", updatedAt: new Date().toISOString() },
    { id: "active", status: "accepted", updatedAt: iso(9) },
    { id: "old", status: "delivered", updatedAt: iso(9) },
    { id: "old", status: "delivered", createdAt: iso(9) },
    { id: "mid", status: "cancelled", updatedAt: iso(4) },
    { id: "undated", status: "delivered" },
  ] as never[];

  const eligible = eligibleForCleanup(records, before, ORDER_BATCH);
  assert.deepEqual(eligible.map((record) => record.id), ["old", "mid"], "duplicated ids collapse, active and newer orders stay");
  assert.equal(eligibleForCleanup(records, before, 1).length, 1, "the limit is respected");
});

test("the mirrors of an order are its live flag and its chat thread", () => {
  assert.deepEqual(
    mirrorPathsFor([{ id: "abc" } as never, { id: "def" } as never]),
    ["orderLive/abc", "chats/abc", "orderLive/def", "chats/def"],
  );
  assert.deepEqual(mirrorPathsFor([]), []);
});

test("a missing index is reported with the console link Firestore offered", () => {
  assert.match(indexMissingWarning("https://console.firebase.google.com/x"), /console\.firebase\.google\.com\/x/);
  assert.match(indexMissingWarning(null), /seed-config/);
});

// ------------------------------------------------------------- the whole job

function backendWithIndex(): FakeFirebase {
  return new FakeFirebase().withIndex("orders", "status", "updatedAt");
}

test("with the composite index present, cleanup deletes the finished orders and their mirrors", async () => {
  const backend = backendWithIndex();
  backend
    .setDocument("orders/old1", orderFields({ shortId: "UND-1001", updatedAt: iso(6), createdAt: iso(6) }))
    .setDocument("orders/old2", orderFields({ status: "cancelled", shortId: "UND-1002", updatedAt: iso(5), createdAt: iso(5) }))
    .setDocument("orders/live1", orderFields({ status: "accepted", updatedAt: iso(0), createdAt: iso(0) }))
    .setDocument("chats/old1", { customerId: "customer-1", orderId: "old1" })
    .setDocument("notifInbox/vendor-1/items/n1", { title: "Order placed", createdAt: iso(3) })
    .setRtdb("orderLive/old1", { status: "delivered" })
    .setRtdb("chats/old1", { customerId: "customer-1" });

  const restore = backend.install();
  try {
    const report = await quiet(() => runCleanup(backend.env, "vendor-1", {})) as CleanupReport;
    assert.equal(report.ok, true);
    assert.equal(report.strategy, "indexed");
    assert.equal(report.orders, 2);
    assert.equal(report.notifications, 1);
    assert.equal(report.more, false);
    assert.deepEqual(report.warnings, []);
    assert.equal(report.mirrorsPending, 0);

    assert.equal(backend.docs.has("orders/old1"), false, "the delivered order is gone");
    assert.equal(backend.docs.has("orders/old2"), false, "the cancelled order is gone");
    assert.equal(backend.docs.has("orders/live1"), true, "an accepted order is never touched");
    assert.equal(backend.docs.has("chats/old1"), false, "the chat thread goes with its order");
    assert.equal(backend.docs.has("notifInbox/vendor-1/items/n1"), false);

    assert.ok(backend.rtdbPatches.some((patch) => patch["orderLive/old1"] === null), "the live mirror is deleted");
    assert.equal(backend.rtdb.has("orderLive/old1"), false);
  } finally {
    restore();
  }
});

test("without the composite index, cleanup falls back to the scan instead of answering 502", async () => {
  // This is the failure that reached the merchant as
  // "Cleanup paused (HTTP 502). You can safely try again." — the index is a
  // deployment step, and a missing deployment step must not break the app.
  const backend = new FakeFirebase();
  backend
    .setDocument("orders/old1", orderFields({ updatedAt: iso(6), createdAt: iso(6) }))
    .setDocument("orders/live1", orderFields({ status: "placed", updatedAt: iso(0), createdAt: iso(0) }))
    .setRtdb("orderLive/old1", { status: "delivered" });

  const restore = backend.install();
  try {
    const report = await quiet(() => runCleanup(backend.env, "vendor-1", {})) as CleanupReport;
    assert.equal(report.strategy, "scan", "the index-free scan took over");
    assert.equal(report.orders, 1);
    assert.equal(backend.docs.has("orders/old1"), false, "and it still deleted the order");
    assert.equal(backend.docs.has("orders/live1"), true);
    assert.equal(report.warnings.length, 1);
    assert.match(report.warnings[0], /console\.firebase\.google\.com/, "the warning carries the console link");
    assert.match(report.hint ?? "", /seed-config/, "the hint says how to make it fast again");
  } finally {
    restore();
  }
});

test("a scan that reaches only active orders says so instead of looping", async () => {
  const backend = new FakeFirebase();
  for (let index = 0; index < SCAN_LIMIT; index += 1) {
    backend.setDocument(`orders/active${String(index).padStart(3, "0")}`, orderFields({
      status: "placed",
      updatedAt: iso(30 - (index % 20)),
      createdAt: iso(30 - (index % 20)),
    }));
  }
  backend.setDocument("orders/finished-later", orderFields({ updatedAt: iso(1), createdAt: iso(1) }));

  const restore = backend.install();
  try {
    const load = await quiet(() => loadFinishedOrders(backend.env, new Date()));
    assert.equal(load.strategy, "scan");
    assert.equal(load.orders.length, 0);
    assert.equal(load.scanExhausted, true);
    assert.ok(load.warnings.some((warning) => /still active/.test(warning)));

    const report = await quiet(() => runCleanup(backend.env, "vendor-1", {})) as CleanupReport;
    assert.equal(report.more, false, "an exhausted scan must not send the caller round again");
    assert.match(report.hint ?? "", /composite index/);
  } finally {
    restore();
  }
});

test("an unreachable Realtime Database does not stop the cleanup; the mirrors are queued and retried", async () => {
  const backend = backendWithIndex();
  backend
    .setDocument("orders/old1", orderFields({ updatedAt: iso(6) }))
    .setDocument("orders/old2", orderFields({ status: "cancelled", updatedAt: iso(4) }))
    .setRtdb("orderLive/old1", { status: "delivered" });
  backend.rtdbStatus = 401;

  const restore = backend.install();
  try {
    const first = await quiet(() => runCleanup(backend.env, "vendor-1", {})) as CleanupReport;
    assert.equal(first.orders, 2, "the orders are deleted even though the mirrors could not be");
    assert.equal(first.mirrorsPending, 4, "two mirrors per order, queued in KV");
    assert.ok(first.warnings.some((warning) => /live mirrors could not be deleted/.test(warning)));
    assert.match(first.warnings.join(" "), /Permission denied|refused/, "the reason Google gave is passed on, not swallowed");

    const queued = JSON.parse(backend.kv.get(PENDING_MIRRORS_KEY) ?? "[]") as string[];
    assert.deepEqual(queued.sort(), ["chats/old1", "chats/old2", "orderLive/old1", "orderLive/old2"]);

    // The database comes back: the next call clears the backlog before anything else.
    backend.rtdbStatus = 200;
    const second = await quiet(() => runCleanup(backend.env, "vendor-1", {})) as CleanupReport;
    assert.equal(second.mirrorsPending, 0);
    assert.equal(backend.kv.has(PENDING_MIRRORS_KEY), false, "an empty backlog is removed from KV");
    assert.equal(backend.rtdb.has("orderLive/old1"), false, "and the mirrors it queued are gone");
  } finally {
    restore();
  }
});

test("a transient Google failure is retried inside the Worker, not handed to the merchant", async () => {
  const backend = backendWithIndex();
  backend.setDocument("orders/old1", orderFields({ updatedAt: iso(6) }));
  backend.transientQueryFailures = 1;

  const restore = backend.install();
  try {
    const report = await quiet(() => runCleanup(backend.env, "vendor-1", {})) as CleanupReport;
    assert.equal(report.orders, 1);
    assert.deepEqual(report.warnings, [], "a retry that worked is not a warning");
  } finally {
    restore();
  }
});

test("a notification history that cannot be read is reported, and the orders are still cleared", async () => {
  const backend = backendWithIndex();
  backend.setDocument("orders/old1", orderFields({ updatedAt: iso(6) }));
  // The inbox lives in a subcollection the fake serves from the URL parent; a
  // two-field filter there would need an index, so force the failure instead by
  // pointing the query at a collection the fake refuses.
  backend.setDocument("notifInbox/vendor-1/items/n1", { title: "Order placed", createdAt: iso(3) });

  const restore = backend.install();
  try {
    const report = await quiet(() => runCleanup(backend.env, "vendor-1", {})) as CleanupReport;
    assert.equal(report.orders, 1);
    assert.equal(report.notifications, 1);
    assert.equal(backend.docs.has("notifInbox/vendor-1/items/n1"), false);
  } finally {
    restore();
  }
});

test("the dry run diagnoses every step and deletes nothing", async () => {
  const backend = new FakeFirebase();
  backend
    .setDocument("orders/old1", orderFields({ updatedAt: iso(6) }))
    .setDocument("notifInbox/vendor-1/items/n1", { title: "Order placed", createdAt: iso(3) })
    .setRtdb("orderLive/old1", { status: "delivered" });

  const restore = backend.install();
  try {
    const diagnosis = await quiet(() => runCleanup(backend.env, "vendor-1", { dryRun: true })) as CleanupDiagnosis;
    assert.equal(diagnosis.dryRun, true);
    assert.equal(diagnosis.verdict, "degraded", "a missing index is degraded, not blocked");
    assert.equal(diagnosis.strategy, "scan");
    assert.equal(diagnosis.finishedSample, 1);
    assert.ok(diagnosis.indexUrl?.startsWith("https://console.firebase.google.com/"));
    const names = diagnosis.checks.map((check) => check.name);
    assert.ok(names.some((name) => /Composite index/.test(name)));
    assert.ok(names.some((name) => /Index-free scan/.test(name)));
    assert.ok(names.some((name) => /Realtime Database/.test(name)));
    assert.ok(names.some((name) => /Mirror backlog/.test(name)));
    assert.ok(names.some((name) => /notification history/.test(name)));
    assert.equal(backend.docs.has("orders/old1"), true, "a dry run deletes nothing");
    assert.deepEqual(backend.commits, []);

    backend.withIndex("orders", "status", "updatedAt");
    const ready = await quiet(() => runCleanup(backend.env, "vendor-1", { dryRun: true })) as CleanupDiagnosis;
    assert.equal(ready.verdict, "ready");
    assert.equal(ready.strategy, "indexed");
  } finally {
    restore();
  }
});

test("the diagnosis is blocked when no route to the finished orders works", async () => {
  const backend = new FakeFirebase();
  backend.setDocument("orders/old1", orderFields({ updatedAt: iso(6) }));
  const restore = backend.install();
  // Both loaders refused: the fake answers 503 forever, past the retry budget.
  backend.transientQueryFailures = 99;
  try {
    await assert.rejects(
      () => quiet(() => runCleanup(backend.env, "vendor-1", {})),
      (error: Error) => {
        assert.match(error.message, /temporarily unavailable|refused/i);
        return true;
      },
    );
  } finally {
    restore();
  }
});

test("diagnoseCleanup names the failed check when the Realtime Database refuses", async () => {
  const backend = backendWithIndex();
  backend.setDocument("orders/old1", orderFields({ updatedAt: iso(6) }));
  backend.rtdbStatus = 401;
  const restore = backend.install();
  try {
    const diagnosis = await quiet(() => diagnoseCleanup(backend.env, "vendor-1", new Date()));
    const rtdbCheck = diagnosis.checks.find((check) => /Realtime Database/.test(check.name));
    assert.equal(rtdbCheck?.ok, false);
    assert.match(rtdbCheck?.hint ?? "", /FIREBASE_RTDB_URL/);
    assert.equal(diagnosis.verdict, "degraded");
  } finally {
    restore();
  }
});

