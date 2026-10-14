/**
 * `POST /admin/cleanup` — permanently clear finished order history (V-095).
 *
 * Vendor-only, bounded, idempotent, and deliberately unimpressive: it deletes a
 * handful of documents per call and tells the caller to come back for more.
 *
 * Why this file grew a second way of doing the same job
 * -----------------------------------------------------
 * The first version asked Firestore for `status == delivered AND updatedAt <
 * before`. That query needs a **composite index**. The index is declared in
 * `firebase/firestore.indexes.json` and deployed by the seed-config workflow, so
 * a project that has not re-run that workflow since the index was added does not
 * have it — and Firestore refuses the query with 400 FAILED_PRECONDITION plus a
 * console link.
 *
 * The Worker turned that into a bare `502 firestore-query-failed`, the app
 * turned the 502 into "Cleanup paused (HTTP 502). You can safely try again.",
 * and the merchant tapped again, and again, forever. Three separate pieces of
 * information were lost on the way: *what* failed, *why*, and *what to do*.
 *
 * So this route now:
 *
 *   · falls back to an **index-free scan** (a single-field `orderBy`, which
 *     Firestore serves from the automatic index every field gets) the moment the
 *     composite query is refused, so cleanup works today on any project;
 *   · keeps the reason. A missing index becomes a warning that names the index
 *     and carries the console link, not a status code;
 *   · treats the Realtime Database mirrors as the cache they are. If they cannot
 *     be deleted, the paths are queued in KV and retried by the next call, and
 *     the response says how many are waiting — instead of failing a cleanup that
 *     already deleted the orders;
 *   · answers `{"dryRun": true}` with a diagnosis of every step, which is what
 *     the app's diagnostics screen runs before it offers the button.
 */
import type { Env } from "../env";
import { requireVendor } from "../authverify";
import { FirestoreError, fsDeleteBatch, fsQuery, type FsRecord } from "../firestore";
import { rtdbGetShallow, rtdbUpdate } from "../rtdb";
import { HttpError, json, readJson } from "../util/http";

export const CLEANUP_CONFIRMATION = "DELETE_FINISHED_HISTORY";
/** Orders deleted per call. Small on purpose: a merchant can always run it again. */
export const ORDER_BATCH = 5;
export const INBOX_BATCH = 25;

/**
 * How many documents the index-free scan reads per timestamp field.
 *
 * The scan orders by a timestamp ascending, so the page it reads is the *oldest*
 * orders in the collection — the ones a cleanup is about. Two hundred is enough
 * that a normal kitchen's finished orders are inside the first page, and small
 * enough that one call stays well inside a Worker's subrequest budget.
 */
export const SCAN_LIMIT = 200;

/**
 * Fields the scan can order by. `updatedAt` is the right one; `createdAt` is
 * here because an order written before `updatedAt` existed has no value for it,
 * and Firestore omits a document from an `orderBy` on a field it does not have.
 */
export const SCAN_FIELDS = ["updatedAt", "createdAt"] as const;

/** Mirror deletions the Realtime Database refused, waiting for the next call. */
export const PENDING_MIRRORS_KEY = "cleanup:pending-mirrors";
export const PENDING_MIRRORS_MAX = 400;
const MIRROR_CHUNK = 100;

// --------------------------------------------------------------------- shapes

export type CleanupStrategy = "indexed" | "scan";

export interface CleanupReport {
  ok: true;
  before: string;
  orders: number;
  notifications: number;
  more: boolean;
  /** How the finished orders were found. `scan` means the composite index is missing. */
  strategy: CleanupStrategy;
  /** Everything that went wrong but did not stop the cleanup. Never empty-handed. */
  warnings: string[];
  /** Mirror deletions still queued in KV after this call. */
  mirrorsPending: number;
  /** The one sentence worth putting on screen, when there is one. */
  hint?: string;
}

export interface DiagnosisCheck {
  name: string;
  ok: boolean;
  detail: string;
  hint?: string;
}

export interface CleanupDiagnosis {
  ok: true;
  dryRun: true;
  before: string;
  /** `ready` deletes everything · `degraded` deletes with a caveat · `blocked` cannot. */
  verdict: "ready" | "degraded" | "blocked";
  summary: string;
  checks: DiagnosisCheck[];
  strategy: CleanupStrategy;
  /** Finished orders the next real call would delete, up to one batch. */
  finishedSample: number;
  mirrorsPending: number;
  indexUrl?: string;
}

// ----------------------------------------------------------- pure decisions

/** Fixed snapshot boundary prevents a cleanup from chasing newly arriving data. */
export function cleanupBoundary(value: unknown, now = Date.now()): Date {
  if (value === undefined) return new Date(now);
  if (typeof value !== "string") {
    throw new HttpError(400, "invalid-cleanup-boundary", "The cleanup boundary must be an ISO timestamp.", {
      hint: "Send no `before` at all to clean everything finished up to now.",
    });
  }
  const time = Date.parse(value);
  if (!Number.isFinite(time) || time > now || time < 0) {
    throw new HttpError(400, "invalid-cleanup-boundary", "The cleanup boundary is not a moment in the past.", {
      hint: "Send no `before` at all to clean everything finished up to now.",
    });
  }
  return new Date(time);
}

export function isFinished(status: unknown): boolean {
  return status === "delivered" || status === "cancelled";
}

/**
 * The moment an order became eligible for cleanup.
 *
 * Firestore REST returns a timestamp as an ISO string; an order written by an
 * older build may hold epoch milliseconds instead. Both are read, and an order
 * with neither is treated as *not* eligible — guessing a date is how a live
 * order gets deleted.
 */
export function cleanupTimestamp(record: FsRecord): number | null {
  for (const field of ["updatedAt", "createdAt"] as const) {
    const value = record[field];
    if (typeof value === "string") {
      const parsed = Date.parse(value);
      if (Number.isFinite(parsed)) return parsed;
    }
    if (typeof value === "number" && Number.isFinite(value) && value > 0) return value;
  }
  return null;
}

/**
 * Filters a scanned page down to what may be deleted, oldest first, deduplicated
 * by document id (the scan reads two timestamp fields, so a document can appear
 * twice).
 *
 * Two invariants this function holds that the caller must not have to re-check:
 * every returned record is finished, and every returned record is strictly older
 * than the boundary.
 */
export function eligibleForCleanup(records: FsRecord[], before: Date, limit: number): FsRecord[] {
  const cutoff = before.getTime();
  const unique = new Map<string, FsRecord>();
  for (const record of records) {
    if (!record?.id || !isFinished(record.status)) continue;
    const timestamp = cleanupTimestamp(record);
    if (timestamp === null || timestamp >= cutoff) continue;
    if (!unique.has(record.id)) unique.set(record.id, record);
  }
  return [...unique.values()]
    .sort((left, right) => (cleanupTimestamp(left) ?? 0) - (cleanupTimestamp(right) ?? 0))
    .slice(0, Math.max(0, limit));
}

/** The RTDB mirrors of a set of orders: the live flag and the chat thread. */
export function mirrorPathsFor(orders: FsRecord[]): string[] {
  const paths: string[] = [];
  for (const order of orders) {
    if (!order.id) continue;
    paths.push(`orderLive/${order.id}`, `chats/${order.id}`);
  }
  return paths;
}

export function indexMissingWarning(indexUrl: string | null): string {
  return indexUrl
    ? `The composite index Firestore wants for this query does not exist yet, so Undal used its slower index-free scan. Create the index at ${indexUrl} or re-run the seed-config workflow to make cleanup fast again.`
    : "The composite index Firestore wants for this query does not exist yet, so Undal used its slower index-free scan. Re-run the seed-config workflow to make cleanup fast again.";
}

// ------------------------------------------------------- the pending queue

export async function readPendingMirrors(env: Env): Promise<string[]> {
  const stored = (await env.UNDAL_KV.get(PENDING_MIRRORS_KEY, "json").catch(() => null)) as string[] | null;
  if (!Array.isArray(stored)) return [];
  return stored.filter((path) => typeof path === "string" && path.length > 0).slice(0, PENDING_MIRRORS_MAX);
}

async function writePendingMirrors(env: Env, paths: string[]): Promise<void> {
  const unique = [...new Set(paths)].slice(0, PENDING_MIRRORS_MAX);
  if (unique.length === 0) {
    await env.UNDAL_KV.delete(PENDING_MIRRORS_KEY).catch(() => undefined);
    return;
  }
  await env.UNDAL_KV.put(PENDING_MIRRORS_KEY, JSON.stringify(unique)).catch(() => undefined);
}

/**
 * Retries the mirror deletions an earlier call could not make.
 *
 * Returns what it cleared and what is still waiting; a failure here is a
 * warning, never a thrown error, because the orders those mirrors belonged to
 * are already gone from Firestore and the mirrors are only a cache.
 */
export async function drainPendingMirrors(env: Env): Promise<{ drained: number; remaining: number; warning?: string }> {
  const pending = await readPendingMirrors(env);
  if (pending.length === 0) return { drained: 0, remaining: 0 };

  const remaining: string[] = [];
  let drained = 0;
  for (let start = 0; start < pending.length; start += MIRROR_CHUNK) {
    const chunk = pending.slice(start, start + MIRROR_CHUNK);
    const body: Record<string, null> = {};
    for (const path of chunk) body[path] = null;
    try {
      await rtdbUpdate(env, "", body);
      drained += chunk.length;
    } catch (error) {
      remaining.push(...chunk);
      const reason = error instanceof Error ? error.message : String(error);
      return { drained, remaining: remaining.length, warning: `${reason} (${remaining.length} mirror path(s) still queued.)` };
    }
  }
  await writePendingMirrors(env, remaining);
  return { drained, remaining: remaining.length };
}

// ------------------------------------------------------------ loading orders

export interface OrderLoad {
  orders: FsRecord[];
  strategy: CleanupStrategy;
  warnings: string[];
  indexUrl: string | null;
  /** True when the scan filled its page and found nothing eligible. */
  scanExhausted: boolean;
}

async function indexedQuery(env: Env, before: Date, limit: number): Promise<FsRecord[]> {
  const batches = await Promise.all(
    (["delivered", "cancelled"] as const).map((status) =>
      fsQuery(
        env,
        {
          collectionId: "orders",
          where: [
            { field: "status", op: "EQUAL", value: status },
            { field: "updatedAt", op: "LESS_THAN", value: before },
          ],
          limit,
        },
        `the ${status}-order history query`,
      ),
    ),
  );
  return batches.flat();
}

async function scanQuery(env: Env, before: Date, limit: number): Promise<{ orders: FsRecord[]; pagesFull: boolean }> {
  const pages = await Promise.all(
    SCAN_FIELDS.map((field) =>
      fsQuery(
        env,
        { collectionId: "orders", orderBy: [{ field, direction: "ASCENDING" }], limit: SCAN_LIMIT },
        `the index-free ${field} scan`,
      ),
    ),
  );
  const merged = pages.flat();
  return {
    orders: eligibleForCleanup(merged, before, limit),
    pagesFull: pages.some((page) => page.length >= SCAN_LIMIT),
  };
}

/**
 * Finds the finished orders to delete, by the fast route if the project has the
 * index and by the scan if it does not.
 *
 * This is the function that turns a 502 into a working cleanup: the composite
 * index is a deployment step, and a missing deployment step must not be able to
 * break a merchant's app.
 */
export async function loadFinishedOrders(env: Env, before: Date, limit = ORDER_BATCH): Promise<OrderLoad> {
  const warnings: string[] = [];
  try {
    const orders = await indexedQuery(env, before, limit);
    return { orders, strategy: "indexed", warnings, indexUrl: null, scanExhausted: false };
  } catch (error) {
    if (!(error instanceof FirestoreError) || !error.failure.needsIndex) throw error;
    const indexUrl = error.failure.indexUrl;
    warnings.push(indexMissingWarning(indexUrl));
    console.warn("undal-cleanup index missing", JSON.stringify({ indexUrl, upstream: error.failure.message }));
    const scan = await scanQuery(env, before, limit);
    if (scan.orders.length === 0 && scan.pagesFull) {
      warnings.push(
        `The ${SCAN_LIMIT} oldest orders are all still active, so nothing finished was reachable in one scan. `
        + "Creating the composite index removes this limit.",
      );
    }
    return {
      orders: scan.orders,
      strategy: "scan",
      warnings,
      indexUrl,
      scanExhausted: scan.orders.length === 0 && scan.pagesFull,
    };
  }
}

async function loadInbox(env: Env, uid: string, before: Date): Promise<{ items: FsRecord[]; warning?: string }> {
  try {
    const items = await fsQuery(
      env,
      {
        parentPath: `notifInbox/${uid}`,
        collectionId: "items",
        where: [{ field: "createdAt", op: "LESS_THAN", value: before }],
        limit: INBOX_BATCH,
      },
      "the kitchen notification history query",
    );
    return { items };
  } catch (error) {
    const reason = error instanceof HttpError ? error.message : String(error);
    return { items: [], warning: `Kitchen notifications were left alone: ${reason}` };
  }
}

// ----------------------------------------------------------------- the route

/**
 * The cleanup job, separated from the HTTP that asks for it.
 *
 * `handleCleanup` owns transport concerns — who is calling, the confirmation
 * word, the JSON body. This owns the work, and takes the vendor uid as a
 * parameter so the whole ladder (index, scan, mirrors, backlog, diagnosis) can be
 * exercised in a test without forging a Firebase ID token.
 */
export async function runCleanup(
  env: Env,
  vendorUid: string,
  options: { before?: string; dryRun?: boolean; now?: number },
): Promise<CleanupReport | CleanupDiagnosis> {
  const before = cleanupBoundary(options.before, options.now);
  if (options.dryRun === true) return diagnoseCleanup(env, vendorUid, before);

  const warnings: string[] = [];

  // Mirrors an earlier call could not delete go first: they are already orphans,
  // and clearing them now keeps the queue from growing without bound.
  const drained = await drainPendingMirrors(env);
  if (drained.warning) warnings.push(drained.warning);

  const load = await loadFinishedOrders(env, before);
  warnings.push(...load.warnings);

  // Belt and braces: the loaders already refuse an active order, and a deletion
  // is the one thing here that cannot be undone.
  if (load.orders.some((order) => !isFinished(order.status))) {
    throw new HttpError(409, "active-order-protected", "An active order reached the cleanup batch and nothing was deleted.", {
      hint: "This is a bug, not a setting. Tell the developer; the order ids are in the Worker log.",
    });
  }

  const inbox = await loadInbox(env, vendorUid, before);
  if (inbox.warning) warnings.push(inbox.warning);

  // Mirrors first. If this fails the orders survive in Firestore and the mirrors
  // are queued, so the next call finishes the job; if the Firestore deletion
  // fails afterwards the mirrors are simply gone early, and the apps re-read
  // Firestore when a mirror is missing.
  let mirrorsPending = drained.remaining;
  const mirrors = mirrorPathsFor(load.orders);
  if (mirrors.length > 0) {
    const mirrorBody: Record<string, null> = {};
    for (const path of mirrors) mirrorBody[path] = null;
    try {
      await rtdbUpdate(env, "", mirrorBody);
    } catch (error) {
      const reason = error instanceof HttpError ? error.message : String(error);
      const queued = [...(await readPendingMirrors(env)), ...mirrors];
      await writePendingMirrors(env, queued);
      mirrorsPending = Math.min(queued.length, PENDING_MIRRORS_MAX);
      warnings.push(
        `The live mirrors could not be deleted (${reason}). The orders themselves were still cleared, and ${mirrorsPending} mirror path(s) are queued for the next run.`,
      );
    }
  }

  const paths = [
    ...load.orders.flatMap((order) => [`orders/${order.id}`, `chats/${order.id}`]),
    ...inbox.items.map((item) => `notifInbox/${vendorUid}/items/${item.id}`),
  ];
  await fsDeleteBatch(env, paths);

  // "More" means another call would find more work. A full batch of orders or a
  // full batch of notifications is that signal. The one case that must NOT claim
  // more is an exhausted scan: it would send the app round a hundred times to
  // delete nothing, which is the loop this route exists to end.
  const more = !load.scanExhausted && (load.orders.length >= ORDER_BATCH || inbox.items.length === INBOX_BATCH);

  const report: CleanupReport = {
    ok: true,
    before: before.toISOString(),
    orders: load.orders.length,
    notifications: inbox.items.length,
    more,
    strategy: load.strategy,
    warnings,
    mirrorsPending,
  };
  if (load.scanExhausted) {
    report.hint =
      "Nothing finished could be reached in one scan because the oldest orders are all still active. Create the composite index named in the warning, then run this again.";
  } else if (load.strategy === "scan") {
    report.hint = "Cleanup is working through the slower index-free scan. Re-run the seed-config workflow to deploy the composite index and make it fast again.";
  }

  console.info("undal-cleanup", JSON.stringify({
    vendor: vendorUid,
    before: report.before,
    orders: report.orders,
    notifications: report.notifications,
    more: report.more,
    strategy: report.strategy,
    mirrorsPending: report.mirrorsPending,
    mirrorsDrained: drained.drained,
    warnings: report.warnings.length,
  }));
  return report;
}

/** Vendor-only, bounded, idempotent batches. Never accepts arbitrary collection paths. */
export async function handleCleanup(env: Env, request: Request): Promise<Response> {
  const caller = await requireVendor(env, request);
  const body = await readJson<{ confirmation?: string; before?: string; dryRun?: boolean }>(request);
  if (body.confirmation !== CLEANUP_CONFIRMATION) {
    throw new HttpError(400, "cleanup-confirmation-required", "Deleting order history needs the confirmation word.", {
      hint: `Send {"confirmation": "${CLEANUP_CONFIRMATION}"} — the app does this from the confirmation dialog.`,
    });
  }
  return json(await runCleanup(env, caller.sub, body));
}

// ------------------------------------------------------------- the diagnosis

/**
 * `{"dryRun": true}` — walks every step the real call takes, deletes nothing,
 * and reports what would happen.
 *
 * This exists because "try again" was the only advice a merchant could be given.
 * With this, the app's diagnostics screen can say *which* piece is missing
 * before anybody taps the destructive button.
 */
export async function diagnoseCleanup(env: Env, uid: string, before: Date): Promise<CleanupDiagnosis> {
  const checks: DiagnosisCheck[] = [];
  let strategy: CleanupStrategy = "indexed";
  let indexUrl: string | null = null;
  let sample: FsRecord[] = [];

  // 1 · the fast, indexed query
  try {
    const orders = await indexedQuery(env, before, ORDER_BATCH);
    sample = orders;
    checks.push({ name: "Composite index (status + updatedAt)", ok: true, detail: `Serves the finished-order query; ${orders.length} order(s) in the first batch.` });
  } catch (error) {
    if (error instanceof FirestoreError && error.failure.needsIndex) {
      indexUrl = error.failure.indexUrl;
      checks.push({
        name: "Composite index (status + updatedAt)",
        ok: false,
        detail: "Firestore refused the query: the index does not exist in this project.",
        hint: indexUrl
          ? `Create it at ${indexUrl}, or re-run the seed-config workflow. Cleanup keeps working without it, through the slower scan below.`
          : "Re-run the seed-config workflow, which deploys firebase/firestore.indexes.json. Cleanup keeps working without it, through the slower scan below.",
      });
    } else {
      checks.push({
        name: "Composite index (status + updatedAt)",
        ok: false,
        detail: error instanceof Error ? error.message : String(error),
        hint: error instanceof HttpError ? error.hint : undefined,
      });
    }
  }

  // 2 · the index-free fallback
  try {
    const scan = await scanQuery(env, before, ORDER_BATCH);
    if (checks[0]?.ok !== true) {
      strategy = "scan";
      sample = scan.orders;
    }
    checks.push({
      name: "Index-free scan (oldest orders first)",
      ok: true,
      detail: scan.orders.length > 0
        ? `${scan.orders.length} finished order(s) reachable without any composite index.`
        : scan.pagesFull
          ? `Read ${SCAN_LIMIT} orders per timestamp field and found none finished — the oldest orders are all still active.`
          : "No finished orders are waiting, so there is nothing to delete.",
    });
  } catch (error) {
    checks.push({
      name: "Index-free scan (oldest orders first)",
      ok: false,
      detail: error instanceof Error ? error.message : String(error),
      hint: error instanceof HttpError ? error.hint : undefined,
    });
  }

  // 3 · the Realtime Database mirrors
  try {
    await rtdbGetShallow<Record<string, unknown>>(env, "orderLive");
    checks.push({ name: "Realtime Database (live mirrors)", ok: true, detail: "Readable with the Worker's service account, so mirrors can be deleted." });
  } catch (error) {
    checks.push({
      name: "Realtime Database (live mirrors)",
      ok: false,
      detail: error instanceof Error ? error.message : String(error),
      hint: "Orders are still deleted from Firestore without this; the mirrors are queued and retried. Fixing it usually means FIREBASE_RTDB_URL.",
    });
  }

  // 4 · the mirror backlog
  const pending = await readPendingMirrors(env);
  checks.push({
    name: "Mirror backlog",
    ok: pending.length === 0,
    detail: pending.length === 0 ? "No mirror deletions are waiting." : `${pending.length} mirror path(s) are queued and will be retried by the next cleanup.`,
  });

  // 5 · the kitchen's own notification history
  const inbox = await loadInbox(env, uid, before);
  checks.push({
    name: "Kitchen notification history",
    ok: !inbox.warning,
    detail: inbox.warning ?? `${inbox.items.length} notification(s) in the first batch.`,
  });

  const ordersReadable = checks[0].ok || checks[1].ok;
  const degraded = !checks.every((check) => check.ok);
  const verdict: CleanupDiagnosis["verdict"] = !ordersReadable ? "blocked" : degraded ? "degraded" : "ready";
  const summary = verdict === "blocked"
    ? "Cleanup cannot read the finished orders, so it would delete nothing. The failed check above says why."
    : verdict === "degraded"
      ? `Cleanup works${strategy === "scan" ? " through the index-free scan" : ""}. One or more checks below need attention, but nothing is blocking.`
      : "Cleanup is fully wired: the index serves the query, the mirrors are reachable and the notification history is readable.";

  const diagnosis: CleanupDiagnosis = {
    ok: true,
    dryRun: true,
    before: before.toISOString(),
    verdict,
    summary,
    checks,
    strategy,
    finishedSample: sample.filter((order) => isFinished(order.status)).length,
    mirrorsPending: pending.length,
  };
  if (indexUrl) diagnosis.indexUrl = indexUrl;

  console.info("undal-cleanup-diagnosis", JSON.stringify({ vendor: uid, verdict, strategy, checks: checks.map((check) => `${check.name}=${check.ok}`) }));
  return diagnosis;
}
