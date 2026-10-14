import type { Env } from "../env";
import { requireVendor } from "../authverify";
import { fsDeleteBatch, fsQuery } from "../firestore";
import { rtdbUpdate } from "../rtdb";
import { HttpError, json, readJson } from "../util/http";

export const CLEANUP_CONFIRMATION = "DELETE_FINISHED_HISTORY";
export const ORDER_BATCH = 5;
export const INBOX_BATCH = 25;

/** Fixed snapshot boundary prevents a cleanup from chasing newly arriving data. */
export function cleanupBoundary(value: unknown, now = Date.now()): Date {
  if (value === undefined) return new Date(now);
  if (typeof value !== "string") throw new HttpError(400, "invalid-cleanup-boundary");
  const time = Date.parse(value);
  if (!Number.isFinite(time) || time > now || time < 0) throw new HttpError(400, "invalid-cleanup-boundary");
  return new Date(time);
}

export function isFinished(status: unknown): boolean {
  return status === "delivered" || status === "cancelled";
}

/** Vendor-only, bounded, idempotent batches. Never accepts arbitrary collection paths. */
export async function handleCleanup(env: Env, request: Request): Promise<Response> {
  const caller = await requireVendor(env, request);
  const body = await readJson<{ confirmation?: string; before?: string }>(request);
  if (body.confirmation !== CLEANUP_CONFIRMATION) throw new HttpError(400, "cleanup-confirmation-required");
  const before = cleanupBoundary(body.before);
  const batches = await Promise.all(["delivered", "cancelled"].map(status => fsQuery(env, {
    collectionId: "orders",
    where: [{ field: "status", op: "EQUAL", value: status },
      { field: "updatedAt", op: "LESS_THAN", value: before }],
    limit: ORDER_BATCH,
  })));
  const orders = batches.flat();
  if (orders.some(order => !isFinished(order.status))) throw new HttpError(409, "active-order-protected");
  const inbox = await fsQuery(env, {
    parentPath: `notifInbox/${caller.sub}`, collectionId: "items",
    where: [{ field: "createdAt", op: "LESS_THAN", value: before }], limit: INBOX_BATCH,
  });

  // Remove mirrors first. If this fails, Firestore remains as a retry anchor.
  // A later Firestore failure is safely retryable: RTDB null writes are idempotent.
  if (orders.length) {
    const mirrors: Record<string, null> = {};
    for (const order of orders) {
      mirrors[`orderLive/${order.id}`] = null;
      mirrors[`chats/${order.id}`] = null;
    }
    await rtdbUpdate(env, "", mirrors);
  }
  await fsDeleteBatch(env, [
    ...orders.flatMap(order => [`orders/${order.id}`, `chats/${order.id}`]),
    ...inbox.map(item => `notifInbox/${caller.sub}/items/${item.id}`),
  ]);
  const more = batches.some(batch => batch.length === ORDER_BATCH) || inbox.length === INBOX_BATCH;
  console.info("undal-cleanup", JSON.stringify({ vendor: caller.sub, before: before.toISOString(), orders: orders.length, notifications: inbox.length, more }));
  return json({ ok: true, before: before.toISOString(), orders: orders.length, notifications: inbox.length, more });
}
