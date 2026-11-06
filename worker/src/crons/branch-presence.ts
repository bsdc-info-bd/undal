/** Per-branch presence and opening schedule sweeps.
 *
 * The legacy config/store sweep remains untouched for zero-downtime migration.
 * Real branch documents have independent presence, opening flags, history and
 * staff recipients; one kitchen's dead phone must never close another kitchen.
 */
import type { Env } from "../env";
import type { BranchRecord } from "../branches";
import { branchDisplayName } from "../branches";
import { loadBranches } from "../routes/branches";
import { fsGet, fsPatchWithServerTimestamps } from "../firestore";
import { rtdbGet } from "../rtdb";
import { sendToTokens } from "../fcmv1";
import { tokensForBranch } from "../recipients";
import { dhakaClock, isOpenAt, switchOn, syncedOpenFlag, type StoreConfig } from "../util/store-hours";
import { memoGet, memoPut, type KvBinding } from "../util/kv";
import { presenceDecision } from "./presence-policy";
import { runPresenceSweep, type PresenceSweepResult } from "./presence";

interface PresenceRow { lastSeen?: number; online?: boolean; device?: string }
interface HistoryRow {
  samplesOnline?: number;
  samplesOffline?: number;
  autoOfflineCount?: number;
  restoredCount?: number;
  outages?: Array<{ from: number; to?: number; minutes?: number }>;
}

export function branchPresencePath(branchId: string): string {
  return `presence/branches/${branchId}/vendor`;
}

export function branchHistoryPath(branchId: string, dateKey: string): string {
  return `presenceDays/${branchId}_${dateKey}`;
}

async function branchTokens(env: Env, branchId: string): Promise<string[]> {
  return (await tokensForBranch(env, branchId)).map((entry) => entry.token);
}

async function notifyBranch(
  env: Env,
  branch: BranchRecord,
  data: Record<string, string>,
): Promise<number> {
  const outcome = await sendToTokens(env, await branchTokens(env, branch.id), {
    data: {
      branchId: branch.id,
      clickAction: "vendor/dashboard",
      ...data,
    },
    ttl: "86400s",
  });
  return outcome.sent;
}

async function recordBranchSample(
  env: Env,
  branch: BranchRecord,
  dateKey: string,
  now: Date,
  sample: { online: boolean; autoOffline?: boolean; restored?: boolean; lastSeen: number },
): Promise<void> {
  const path = branchHistoryPath(branch.id, dateKey);
  const old = (await fsGet(env, path).catch(() => null)) as HistoryRow | null;
  const outages = Array.isArray(old?.outages) ? [...old.outages] : [];
  if (sample.autoOffline) outages.push({ from: sample.lastSeen || now.getTime() });
  if (sample.online) {
    for (let index = outages.length - 1; index >= 0; index -= 1) {
      const outage = outages[index]!;
      if (outage.to === undefined) {
        outages[index] = {
          from: outage.from,
          to: now.getTime(),
          minutes: Math.max(5, Math.round((now.getTime() - outage.from) / 60_000)),
        };
        break;
      }
    }
  }
  await fsPatchWithServerTimestamps(env, path, {
    branchId: branch.id,
    date: dateKey,
    samplesOnline: Math.max(0, old?.samplesOnline ?? 0) + (sample.online ? 1 : 0),
    samplesOffline: Math.max(0, old?.samplesOffline ?? 0) + (sample.online ? 0 : 1),
    autoOfflineCount: Math.max(0, old?.autoOfflineCount ?? 0) + (sample.autoOffline ? 1 : 0),
    restoredCount: Math.max(0, old?.restoredCount ?? 0) + (sample.restored ? 1 : 0),
    outages: outages.slice(-24),
    lastSeen: sample.lastSeen,
    ...(sample.online ? { lastOnlineAt: now } : { lastOfflineAt: now }),
  }, ["updatedAt"]).catch(() => undefined);
}

/** One real branch. Legacy documents go through runPresenceSweep instead. */
export async function runSingleBranchPresenceSweep(
  env: Env,
  branch: BranchRecord,
  now: Date = new Date(),
): Promise<PresenceSweepResult> {
  const config = branch as StoreConfig;
  const path = `branches/${branch.id}`;
  const clock = dhakaClock(now);
  const presence = await rtdbGet<PresenceRow>(env, branchPresencePath(branch.id)).catch(() => null);
  const lastSeen = typeof presence?.lastSeen === "number" ? presence.lastSeen : 0;
  const insideHours = isOpenAt(config, now);
  const switchedOn = switchOn(config);
  const decision = presenceDecision({
    lastSeenMillis: lastSeen,
    nowMillis: now.getTime(),
    storeOpen: switchedOn && insideHours,
    autoClosed: config.autoClosed === true,
    autoClosedAtMillis: toMillis(config.autoClosedAt),
    insideHours,
  });
  const ageMinutes = decision.ageMinutes;

  if (decision.action === "auto-offline") {
    await fsPatchWithServerTimestamps(env, path, {
      open: false,
      openSynced: false,
      autoClosed: true,
      autoClosedReason: "device-unreachable",
      autoClosedAt: now,
      autoClosedSilentMinutes: ageMinutes,
    }, ["updatedAt"]);
    const notified = await notifyBranch(env, branch, {
      type: "vendor_self",
      kind: "auto_offline",
      title: `${branchDisplayName(branch)} auto-OFFLINE`,
      body: `No heartbeat for ${ageMinutes} minute(s). New orders are paused for this branch only.`,
      channelId: "vendor_new_order",
    });
    await recordBranchSample(env, branch, clock.dateKey, now, { online: false, autoOffline: true, lastSeen });
    return { ok: true, action: "auto-offline", reason: decision.reason, ageMinutes, notified };
  }

  if (decision.action === "restore" || decision.action === "remind") {
    if (decision.action === "restore") {
      const takingOrders = switchedOn && insideHours;
      await fsPatchWithServerTimestamps(env, path, {
        open: takingOrders,
        openSynced: takingOrders,
        autoClosed: false,
        autoClosedReason: "device-restored",
        autoRestoredAt: now,
        autoClosedSilentMinutes: 0,
      }, ["updatedAt"]);
    }
    let notified = 0;
    // A long outage gets one reminder per auto-close episode, not one per cron.
    const key = `presence:branch-return:${branch.id}:${toMillis(config.autoClosedAt)}`;
    const binding = env.UNDAL_KV as KvBinding;
    const already = await memoGet<string>(binding, key, { ttlMs: 4 * 60_000, cacheNulls: true }).catch(() => null);
    if (!already) {
      await memoPut(binding, key, String(now.getTime()), { expirationTtl: 86_400, required: true }).catch(() => undefined);
      notified = await notifyBranch(env, branch, {
        type: "vendor_self",
        kind: "store_back_online",
        title: `${branchDisplayName(branch)} is back online`,
        body: decision.action === "restore" && switchedOn && insideHours
          ? "This branch is taking orders again."
          : "The branch phone has returned. Confirm opening from Undal Vendor when ready.",
        channelId: "vendor_general",
      });
    }
    await recordBranchSample(env, branch, clock.dateKey, now, {
      online: true,
      restored: decision.action === "restore",
      lastSeen,
    });
    return {
      ok: true,
      action: decision.action === "restore" ? "restored" : notified > 0 ? "reminded" : "none",
      reason: decision.reason,
      ageMinutes,
      notified,
    };
  }

  const patch = syncedOpenFlag(config, now);
  let schedule: PresenceSweepResult["schedule"] | undefined;
  if (Object.keys(patch).length > 0) {
    await fsPatchWithServerTimestamps(env, path, { ...patch, scheduleSyncedAt: now }, ["updatedAt"])
      .then(() => { schedule = patch.open === true ? "opened" : patch.open === false ? "closed" : "synced"; })
      .catch(() => undefined);
  }
  await recordBranchSample(env, branch, clock.dateKey, now, {
    online: lastSeen > 0 && decision.reason === "device-present",
    lastSeen,
  });
  return {
    ok: true,
    action: "none",
    reason: decision.reason,
    ageMinutes,
    notified: 0,
    ...(schedule ? { schedule } : {}),
  };
}

/** Runs every active branch independently; one failure is reported, not hidden. */
export async function runAllPresenceSweeps(env: Env, now: Date = new Date()): Promise<PresenceSweepResult[]> {
  const branches = await loadBranches(env);
  return Promise.all(branches.map(async (branch) => {
    if (branch.legacy === true) return runPresenceSweep(env, now);
    try {
      return await runSingleBranchPresenceSweep(env, branch, now);
    } catch (error) {
      return {
        ok: false,
        action: "none" as const,
        reason: `${branch.id}:${error instanceof Error ? error.message : "sweep-failed"}`,
        ageMinutes: 0,
        notified: 0,
      };
    }
  }));
}

function toMillis(value: unknown): number {
  if (value instanceof Date) return value.getTime();
  if (typeof value === "number") return value > 10_000_000_000 ? value : value * 1000;
  if (typeof value === "string") return Date.parse(value) || 0;
  if (value && typeof value === "object") {
    const seconds = Number((value as { seconds?: unknown }).seconds);
    if (Number.isFinite(seconds)) return seconds * 1000;
  }
  return 0;
}
