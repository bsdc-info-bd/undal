import assert from "node:assert/strict";
import { test } from "node:test";
import { cleanupBoundary, isFinished, CLEANUP_CONFIRMATION, ORDER_BATCH } from "../src/routes/cleanup";
import { pushBody } from "../src/fcmv1";

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
});

test("order alerts stay data-only; customer status carries a display notification", () => {
  const alarm = JSON.parse(pushBody({ type: "order_created", orderId: "a" }, { data: { type: "order_created", orderId: "a" }, highPriority: true } as never)).message as Record<string, unknown>;
  assert.equal(alarm.notification, undefined);
  const status = JSON.parse(pushBody({ type: "order_status" }, { data: { type: "order_status" }, display: { title: "T", body: "B", channelId: "customer_orders", tag: "order-x" } } as never)).message as Record<string, unknown>;
  assert.deepEqual(status.notification, { title: "T", body: "B" });
});
