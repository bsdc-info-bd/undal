/**
 * Cron `*​/5 * * * *` — the kitchen device's presence, and what a missing
 * heartbeat means (PART I-4 · V-006 · V-076 · V-078).
 *
 * The vendor app writes `presence/vendor.lastSeen` at least every five minutes:
 * a foreground service while the console is open, and a fifteen-minute
 * WorkManager job behind it. This sweep is the only thing that can notice a
 * phone that stopped writing, and it has three jobs.
 *
 * **1. Close the store when the device is gone.** Ten minutes without a
 * heartbeat while the store is open means the kitchen cannot see the orders it
 * is taking — a sleeping phone, a dead battery, a router reboot. The store is
 * closed and the owner is pushed a notice. Ten minutes is the rule the owner
 * asked for: long enough that a five-minute heartbeat and a slow night's sleep
 * are not mistaken for an outage, short enough that at most one customer waits.
 *
 * **2. Open it again when the device comes back.** A phone that returns a
 * minute later was a blink, not a night off, so the store is restored — and the
 * owner is told, with the reason, rather than quietly discovering it. A phone
 * that returns after hours has its pause lifted too, because lifting it opens
 * nothing: the schedule keeps the store closed until the next service and opens
 * it then. A phone that returns mid-service after a long absence gets a reminder
 * instead, once per episode: reopening a kitchen whose owner has gone home would
 * be worse than leaving it closed.
 *
 * Only a store that is *taking orders* is ever paused — switch on and inside its
 * hours. A phone switched off overnight, with the store's switch left on, is a
 * kitchen asleep, not an outage, and pausing it would stop the store opening
 * itself in the morning.
 *
 * **3. Keep `open` in step with the schedule.** The store's switch
 * (`acceptingOrders`) says whether the owner is taking orders at all; the hours
 * say when. Every schedule-aware surface works that out for itself, but builds
 * older than the schedule-aware ones read only `open`. So every quiet sweep
 * writes `open` to what the schedule says right now (`syncedOpenFlag`), which is
 * what makes a store left switched on close at closing time and open at opening
 * time on every phone, old or new, within five minutes.
 *
 * **4. Keep the honest record.** Every sweep appends one sample to today's
 * `presenceDays/{date}` document — online or offline, plus any outage window
 * this sweep opened or closed. Five-minute samples over a day are a real
 * uptime figure, and they are what the vendor dashboard's offline/online
 * analytics are read from. The document is one per Dhaka date, so a year of
 * history is 365 small documents and the cleanup cron can expire them.
 */
import type { Env } from "../env";
import { fsGet, fsPatchWithServerTimestamps } from "../firestore";
import { sendToTokens } from "../fcmv1";
import { rtdbGet } from "../rtdb";
import { vendorTokens } from "../recipients";
import {
  dhakaClock,
  isOpenAt,
  nextOpening,
  storeStatus,
  switchOn,
  syncedOpenFlag,
  type StoreConfig,
} from "../util/store-hours";
import {
  FRESH_AFTER_MS,
  RESTORE_WINDOW_MS,
  STALE_AFTER_MS,
  presenceDecision,
  type PresenceAction,
} from "./presence-policy";
import { memoGet, memoPut, type KvBinding } from "../util/kv";

// The numbers live in ./presence-policy.ts, which has no imports and is covered
// by worker/test/presence-policy.test.ts. They are re-exported here because this
// is the file the app's own diagnostics screen quotes.
export { FRESH_AFTER_MS, RESTORE_WINDOW_MS, STALE_AFTER_MS };

/** Minutes of history the daily document keeps before it stops growing. */
const MAX_OUTAGES_PER_DAY = 24;

/** Samples per day at five minutes each: the denominator of the uptime figure. */
const SAMPLE_MINUTES = 5;

interface PresenceRow {
  lastSeen?: number;
  online?: boolean;
  device?: string;
}

interface OutageWindow {
  from: number;
  to?: number;
  minutes?: number;
}

interface PresenceDay {
  samplesOnline?: number;
  samplesOffline?: number;
  autoOfflineCount?: number;
  restoredCount?: number;
  outages?: OutageWindow[];
  lastSeen?: number;
  lastOfflineAt?: number;
  lastOnlineAt?: number;
}

export interface PresenceSweepResult {
  ok: boolean;
  /** What this sweep did, in one word, for the cron log and the tests. */
  action: "none" | "auto-offline" | "restored" | "reminded";
  reason: string;
  ageMinutes: number;
  notified: number;
  /**
   * What the schedule step wrote to `open` on a quiet sweep: "opened" or
   * "closed" at a service boundary, "synced" for bookkeeping only. Absent when
   * nothing needed writing.
   */
  schedule?: "opened" | "closed" | "synced";
}

/** The sweep's own word for a policy action. */
function actionWord(action: PresenceAction, notified: number): PresenceSweepResult["action"] {
  if (action === "auto-offline") return "auto-offline";
  if (action === "restore") return "restored";
  if (action === "remind" && notified > 0) return "reminded";
  return "none";
}

const OPENING_REMINDER_KINDS = new Set(["opening_soon", "auto_offline", "store_back_online"]);

export async function runPresenceSweep(env: Env, now: Date = new Date()): Promise<PresenceSweepResult> {
  const config = (await fsGet(env, "config/store")) as StoreConfig | null;
  if (!config) {
    return { ok: true, action: "none", reason: "store-config-missing", ageMinutes: 0, notified: 0 };
  }

  const presence = await rtdbGet<PresenceRow>(env, "presence/vendor");
  const lastSeen = typeof presence?.lastSeen === "number" ? presence.lastSeen : 0;
  const clock = dhakaClock(now);
  const dateKey = clock.dateKey;
  const insideHours = isOpenAt(config, now);
  const switchedOn = switchOn(config);

  const decision = presenceDecision({
    lastSeenMillis: lastSeen,
    nowMillis: now.getTime(),
    // Taking orders, not merely switched on: outside its hours a store has no
    // orders to lose, and a silent phone there is a kitchen asleep.
    storeOpen: switchedOn && insideHours,
    autoClosed: config.autoClosed === true,
    autoClosedAtMillis: toMillis(config.autoClosedAt),
    insideHours,
  });
  const ageMinutes = decision.ageMinutes;

  if (decision.action === "restore" || decision.action === "remind") {
    const recovery = await recover(env, config, now, dateKey, lastSeen, decision.action, insideHours);
    return recovery;
  }

  if (decision.action === "none") {
    await recordSample(env, dateKey, {
      online: lastSeen > 0 && decision.reason === "device-present",
      autoOffline: false,
      restored: false,
      lastSeen,
      now: now.getTime(),
    });
    const schedule = await syncSchedule(env, config, now);
    return {
      ok: true,
      action: "none",
      reason: decision.reason,
      ageMinutes,
      notified: 0,
      ...(schedule ? { schedule } : {}),
    };
  }

  // ---- close the store ----------------------------------------------------
  await fsPatchWithServerTimestamps(
    env,
    "config/store",
    {
      open: false,
      openSynced: false,
      // The switch was on — that is the only store the watchdog pauses — and it
      // stays on, so the pause is all that has to be undone later.
      ...(config.acceptingOrders !== switchedOn ? { acceptingOrders: switchedOn } : {}),
      autoClosed: true,
      autoClosedReason: "device-unreachable",
      autoClosedAt: now,
      autoClosedSilentMinutes: ageMinutes,
    },
    ["updatedAt"],
  );

  const status = storeStatus({ ...config, open: false, autoClosed: true }, now);
  const tokens = await vendorTokens(env);
  const outcome = await sendToTokens(env, tokens, {
    data: {
      type: "vendor_self",
      kind: "auto_offline",
      title: "Store auto-OFFLINE",
      body:
        `No heartbeat for ${ageMinutes} minute(s), so new orders are paused (${status.headline}). ` +
        "Open Undal to take orders again.",
      channelId: "vendor_new_order",
      clickAction: "vendor/dashboard",
    },
    ttl: "86400s",
  });

  await recordSample(env, dateKey, {
    online: false,
    autoOffline: true,
    restored: false,
    lastSeen,
    now: now.getTime(),
  });

  return {
    ok: true,
    action: actionWord("auto-offline", outcome.sent),
    reason: decision.reason,
    ageMinutes,
    notified: outcome.sent,
  };
}

/**
 * Writes `open` (and its bookkeeping) to what the schedule says right now, for
 * the builds that read nothing else. Returns what changed, or null.
 *
 * A failure here is swallowed: the schedule-aware surfaces never needed this
 * write, and the next sweep five minutes later tries again.
 */
async function syncSchedule(
  env: Env,
  config: StoreConfig,
  now: Date,
): Promise<PresenceSweepResult["schedule"] | null> {
  const patch = syncedOpenFlag(config, now);
  if (Object.keys(patch).length === 0) return null;
  try {
    await fsPatchWithServerTimestamps(env, "config/store", { ...patch, scheduleSyncedAt: now }, ["updatedAt"]);
  } catch {
    return null;
  }
  if (patch.open === true) return "opened";
  if (patch.open === false) return "closed";
  return "synced";
}

/**
 * The device is beating again after an auto-close. Either undo the pause or ask
 * the owner to, and never both twice.
 */
async function recover(
  env: Env,
  config: StoreConfig,
  now: Date,
  dateKey: string,
  lastSeen: number,
  action: "restore" | "remind",
  insideHours: boolean,
): Promise<PresenceSweepResult> {
  const closedAt = toMillis(config.autoClosedAt);
  const awayMs = closedAt > 0 ? now.getTime() - closedAt : 0;
  const tokens = await vendorTokens(env);
  const switchedOn = switchOn(config);

  if (action === "restore") {
    // Inside the hours, a pause of half an hour or less means the device only
    // blinked: restoring it is the least surprising thing to do — a store that
    // stays closed all evening because a router rebooted is a worse failure than
    // one that comes back and says so. Outside the hours, lifting the pause
    // opens nothing; the schedule opens the store at its next service.
    const takingOrders = switchedOn && insideHours;
    await fsPatchWithServerTimestamps(
      env,
      "config/store",
      {
        open: takingOrders,
        openSynced: takingOrders,
        ...(config.acceptingOrders !== switchedOn ? { acceptingOrders: switchedOn } : {}),
        autoClosed: false,
        autoClosedReason: insideHours ? "device-restored" : "device-returned-after-hours",
        autoRestoredAt: now,
        autoClosedSilentMinutes: 0,
      },
      ["updatedAt"],
    );
    const next = nextOpening(config, now);
    const message = takingOrders
      ? {
          title: "Back online — taking orders",
          body:
            `Your phone is back (${Math.max(1, Math.round(awayMs / 60_000))} minute(s) away), so the store is open and ` +
            "orders ring again. Tap if you would rather stay closed.",
        }
      : !switchedOn
        ? {
            title: "You are back online",
            body: "Your phone is back online. The store stays paused, as you left it — open it from Undal when you are ready.",
          }
        : {
            title: "You are back online",
            body:
              "Your phone is back online. Your hours say the kitchen is closed now, so the store stays closed" +
              (next ? ` and opens by itself ${next.label}.` : ".") +
              " Nothing to do — keep this phone on.",
          };
    const outcome = await sendToTokens(env, tokens, {
      data: {
        type: "vendor_self",
        kind: "store_back_online",
        title: message.title,
        body: message.body,
        channelId: takingOrders ? "vendor_new_order" : "vendor_general",
        clickAction: "vendor/dashboard",
      },
      ttl: "86400s",
    });
    await recordSample(env, dateKey, { online: true, autoOffline: false, restored: true, lastSeen, now: now.getTime() });
    return {
      ok: true,
      action: "restored",
      reason: insideHours ? "device-returned-within-window" : "device-returned-after-hours",
      ageMinutes: 0,
      notified: outcome.sent,
    };
  }

  // A long outage in the middle of a service. The store stays paused; the owner
  // is asked, once per episode.
  const prompted = await promptOnce(env, dateKey, closedAt);
  if (!prompted) {
    await recordSample(env, dateKey, { online: true, autoOffline: false, restored: false, lastSeen, now: now.getTime() });
    return { ok: true, action: "none", reason: "recovery-already-prompted", ageMinutes: 0, notified: 0 };
  }

  const body = "Your phone is back online. Tap to confirm you are taking orders again — the store is still paused.";
  const outcome = await sendToTokens(env, tokens, {
    data: {
      type: "vendor_self",
      kind: "store_back_online",
      title: "You are back online",
      body,
      channelId: "vendor_new_order",
      clickAction: "vendor/dashboard",
    },
    ttl: "86400s",
  });
  await recordSample(env, dateKey, { online: true, autoOffline: false, restored: false, lastSeen, now: now.getTime() });
  return {
    ok: true,
    action: actionWord("remind", outcome.sent),
    reason: "long-outage",
    ageMinutes: 0,
    notified: outcome.sent,
  };
}

/**
 * One reminder per outage episode. The KV key carries the moment the store was
 * paused, so a second episode an hour later is a new reminder and a sweep ten
 * minutes later is not.
 */
async function promptOnce(env: Env, dateKey: string, closedAt: number): Promise<boolean> {
  const key = `presence:reopen-prompt:${dateKey}:${closedAt}`;
  try {
    const binding = env.UNDAL_KV as KvBinding;
    const existing = await memoGet<string>(binding, key, { ttlMs: 4 * 60_000, cacheNulls: true });
    if (existing) return false;
    await memoPut(binding, key, String(Date.now()), { expirationTtl: 86_400, required: true });
    return true;
  } catch {
    // KV unreachable: better one extra reminder than a kitchen never told.
    return true;
  }
}

/**
 * Appends today's sample: one online or offline five-minute tick, plus whatever
 * this sweep learned about the day's outages.
 */
async function recordSample(
  env: Env,
  dateKey: string,
  sample: { online: boolean; autoOffline: boolean; restored: boolean; lastSeen: number; now?: number },
): Promise<void> {
  const path = `presenceDays/${dateKey}`;
  try {
    const existing = (await fsGet(env, path)) as PresenceDay | null;
    const outages = Array.isArray(existing?.outages) ? [...existing.outages] : [];
    const at = sample.now ?? Date.now();

    if (sample.autoOffline) {
      // Time is money, and a second outage in the same hour is a second window.
      outages.push({ from: sample.lastSeen > 0 ? sample.lastSeen : at });
      if (outages.length > MAX_OUTAGES_PER_DAY) outages.splice(0, outages.length - MAX_OUTAGES_PER_DAY);
    }
    if (sample.online) {
      // The device is here: close the last window that is still open.
      for (let index = outages.length - 1; index >= 0; index -= 1) {
        const window = outages[index]!;
        if (window.to === undefined || window.to === null) {
          const minutes = Math.max(SAMPLE_MINUTES, Math.round((at - window.from) / 60_000));
          outages[index] = { from: window.from, to: at, minutes };
          break;
        }
      }
    }

    await fsPatchWithServerTimestamps(
      env,
      path,
      {
        date: dateKey,
        samplesOnline: Math.max(0, Math.round(existing?.samplesOnline ?? 0)) + (sample.online ? 1 : 0),
        samplesOffline: Math.max(0, Math.round(existing?.samplesOffline ?? 0)) + (sample.online ? 0 : 1),
        autoOfflineCount: Math.max(0, Math.round(existing?.autoOfflineCount ?? 0)) + (sample.autoOffline ? 1 : 0),
        restoredCount: Math.max(0, Math.round(existing?.restoredCount ?? 0)) + (sample.restored ? 1 : 0),
        outages,
        lastSeen: sample.lastSeen,
        ...(sample.online ? { lastOnlineAt: new Date(at) } : { lastOfflineAt: new Date(at) }),
      },
      ["updatedAt"],
    );
  } catch {
    // Analytics must never be the reason a store stays open or closed.
  }
}

/** A Dhaka date key from the same arithmetic the hours engine uses. */
export function presenceDateKey(now: Date = new Date()): string {
  return dhakaClock(now).dateKey;
}

/** Minutes one sample stands for, for the uptime figure the dashboard shows. */
export const PRESENCE_SAMPLE_MINUTES = SAMPLE_MINUTES;

/** True for the push kinds this sweep can send — kept for the alert contract. */
export function isPresenceNoticeKind(kind: string): boolean {
  return OPENING_REMINDER_KINDS.has(kind);
}

function toMillis(value: unknown): number {
  if (typeof value === "number") return value;
  if (typeof value === "string") {
    const parsed = Date.parse(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  // Firestore REST timestamps arrive as strings through fsGet; a server
  // timestamp patch is read back as an ISO string, which Date.parse handles.
  if (value && typeof value === "object") {
    const candidate = (value as { seconds?: unknown; _seconds?: unknown }).seconds ?? (value as { _seconds?: unknown })._seconds;
    if (typeof candidate === "number") return candidate * 1000;
  }
  return 0;
}
