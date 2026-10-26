/**
 * The re-alert sweep — the answer to "the phone was off / asleep / in a lift".
 *
 * Every minute this looks at orders that are still `placed` and pushes the same
 * order alert again. One push can be lost; a phone that is asleep, updating, or
 * on an aggressive battery saver can miss it entirely, and FCM keeps no copy.
 * Repeating the alert costs one topic fan-out per minute and closes that hole
 * without asking the phone to do anything at all.
 *
 * The windows themselves live in `alert-window.ts`, where they are pure, named
 * and tested.
 *
 * A query on `status == placed` alone is used on purpose. Adding `orderBy
 * createdAt` can need a missing composite index, and a cron that
 * throws FAILED_PRECONDITION every minute is worse than no cron: the sort is
 * done here per page. An ascending document-name cursor rotates the pages
 * between sweeps, so abandoned orders cannot starve the rest of the queue.
 */
import type { Env } from "../env";
import { fsQuery, type FsRecord } from "../firestore";
import { raiseKitchenAlert } from "../alerting";
import { formatPaisa } from "../util/money";
import { sweepAction, waitedMinutes } from "./alert-window";

/** At most this many live orders are considered per sweep. */
const SWEEP_LIMIT = 50;
const CURSOR_KEY = "alerts:realert-cursor:v1";

export interface RealertSweepResult {
  considered: number;
  realerted: number;
  missed: number;
  failures: string[];
}

function ageSeconds(order: FsRecord, now: number): number | null {
  // `fromFsValue` hands timestamps back as ISO strings.
  const createdAt = typeof order.createdAt === "string" ? Date.parse(order.createdAt) : Number.NaN;
  if (Number.isNaN(createdAt)) return null;
  return Math.round((now - createdAt) / 1000);
}

function text(order: FsRecord, key: string): string {
  const value = order[key];
  return typeof value === "string" ? value : "";
}

function itemCount(order: FsRecord): number {
  return Array.isArray(order.items) ? order.items.length : 0;
}

export async function runRealertSweep(env: Env): Promise<RealertSweepResult> {
  const result: RealertSweepResult = { considered: 0, realerted: 0, missed: 0, failures: [] };

  // Rotate through the complete placed-order set, not the same first page.
  // Otherwise ten abandoned orders could permanently hide every new order.
  const cursor = await env.UNDAL_KV.get(CURSOR_KEY).catch(() => null);
  const outstanding = await fsQuery(env, {
    collectionId: "orders",
    where: [{ field: "status", op: "EQUAL", value: "placed" }],
    limit: SWEEP_LIMIT,
    orderBy: [{ field: "__name__", direction: "ASCENDING" }],
    ...(cursor && /^orders\/[^/]+$/.test(cursor) ? { afterDocument: cursor } : {}),
  });
  result.considered = outstanding.length;
  if (outstanding.length === 0) {
    await env.UNDAL_KV.delete(CURSOR_KEY);
    return result;
  }

  const now = Date.now();
  // Oldest first: if the kitchen is drowning, the order that has waited longest
  // is the one that most needs to be loud.
  const aged = outstanding
    .map((order) => ({ order, age: ageSeconds(order, now) }))
    .filter((entry): entry is { order: FsRecord; age: number } => entry.age !== null)
    .sort((left, right) => right.age - left.age);

  for (const { order, age } of aged) {
    const shortId = text(order, "shortId");
    const orderId = order.id;

    try {
      switch (sweepAction(age)) {
        case "missed": {
          // Unanswered for nearly half an hour. Stopping the ring is deliberate —
          // half an hour of ringing is a device problem, not a delivery problem —
          // but silence is not: the kitchen is told once, with the facts it needs
          // to act on (who is waiting, how long, and the number to call).
          const customer = text(order, "customerName") || "the customer";
          const phone = text(order, "phone");
          const outcome = await raiseKitchenAlert(
            env,
            {
              orderId,
              shortId,
              title: `Order ${shortId} was never answered`,
              body:
                `${customer} has been waiting ${waitedMinutes(age)} minutes. ` +
                `${itemCount(order)} item(s) · ${formatPaisa(Number(order.totalAmountPaisa ?? 0))}. ` +
                (phone ? `Call ${phone} or cancel it with a note.` : "Open the app to accept or cancel it."),
              ttl: "600s",
              // A notice, not a ring: the phone has already been ringing for a
              // quarter of an hour, and by now the useful thing is the customer's
              // number and the fact, not another alarm.
              type: "vendor_self",
              kind: "missed",
              extra: { alertLevel: "missed" },
            },
            `order-${orderId}-missed`,
          );
          if (outcome.delivered === 0) result.failures.push(`${orderId}: ${outcome.warning ?? "FCM refused all routes"}`);
          result.missed += 1;
          break;
        }
        case "realert": {
          // The same collapse key as the first push: a phone that is already
          // ringing refreshes its notification instead of stacking a second one.
          const outcome = await raiseKitchenAlert(
            env,
            {
              orderId,
              shortId,
              title: `New order ${shortId}`,
              body: `${itemCount(order)} item(s) · ${formatPaisa(Number(order.totalAmountPaisa ?? 0))} · still waiting`,
              ttl: "300s",
              extra: { repeated: "true", waitingSeconds: String(age) },
            },
            `order-${orderId}`,
          );
          if (outcome.delivered === 0) result.failures.push(`${orderId}: ${outcome.warning ?? "FCM refused all routes"}`);
          result.realerted += 1;
          break;
        }
        case "silent":
          break;
      }
    } catch (error) {
      result.failures.push(`${orderId}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  if (outstanding.length === SWEEP_LIMIT) {
    await env.UNDAL_KV.put(CURSOR_KEY, `orders/${outstanding[outstanding.length - 1]!.id}`, { expirationTtl: 300 });
  } else {
    await env.UNDAL_KV.delete(CURSOR_KEY);
  }
  return result;
}
