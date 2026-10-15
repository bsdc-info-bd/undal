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
 * that returns after a long absence, or outside the kitchen's own opening hours,
 * gets a reminder instead: reopening a kitchen whose owner has gone home would
 * be worse than leaving it closed. Both cases are reported once per episode.
 *
 * **3. Keep the honest record.** Every sweep appends one sample to today's
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
import { dhakaClock, slotsForDate, isWithinAnySlot, storeStatus, type StoreConfig } from "../util/store-hours";
import {
  FRESH_AFTER_MS,
  RESTORE_WINDOW_MS,
  STALE_AFTER_MS,
  presenceDecision,
  type PresenceAction,
} from "./presence-policy";

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
  const slots = slotsForDate(config, clock);
  const insideHours = isWithinAnySlot(slots, clock.minutes);

  const decision = presenceDecision({
    lastSeenMillis: lastSeen,
    nowMillis: now.getTime(),
    storeOpen: config.open === true,
    autoClosed: config.autoClosed === true,
    autoClosedAtMillis: toMillis(config.autoClosedAt),
    insideHours,
  });
  const ageMinutes = decision.ageMinutes;

  if (decision.action === "restore" || decision.action === "remind") {
    const recovery = await recover(env, config, now, dateKey, lastSeen, decision.action, insideHours, slots);
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
    return { ok: true, action: "none", reason: decision.reason, ageMinutes, notified: 0 };
  }

  // ---- close the store ----------------------------------------------------
  await fsPatchWithServerTimestamps(
    env,
    "config/store",
    {
      open: false,
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
  slots: ReturnType<typeof slotsForDate>,
): Promise<PresenceSweepResult> {
  const closedAt = toMillis(config.autoClosedAt);
  const awayMs = closedAt > 0 ? now.getTime() - closedAt : 0;
  const tokens = await vendorTokens(env);

  if (action === "restore") {
    // A pause of half an hour or less, inside the kitchen's own hours: the
    // device only blinked. Restoring it is the least surprising thing to do —
    // a store that stays closed all evening because a router rebooted is a
    // worse failure than one that comes back and says so.
    await fsPatchWithServerTimestamps(
      env,
      "config/store",
      {
        open: true,
        autoClosed: false,
        autoClosedReason: "device-restored",
        autoRestoredAt: now,
        autoClosedSilentMinutes: 0,
      },
      ["updatedAt"],
    );
    const outcome = await sendToTokens(env, tokens, {
      data: {
        type: "vendor_self",
        kind: "store_back_online",
        title: "Back online — taking orders",
        body:
          `Your phone is back (${Math.max(1, Math.round(awayMs / 60_000))} minute(s) away), so the store is open and ` +
          "orders ring again. Tap if you would rather stay closed.",
        channelId: "vendor_new_order",
        clickAction: "vendor/dashboard",
      },
      ttl: "86400s",
    });
    await recordSample(env, dateKey, { online: true, autoOffline: false, restored: true, lastSeen, now: now.getTime() });
    return {
      ok: true,
      action: "restored",
      reason: "device-returned-within-window",
      ageMinutes: 0,
      notified: outcome.sent,
    };
  }

  // Either the outage was long, or the kitchen's hours say it is not service
  // time. The store stays paused; the owner is asked, once per episode.
  const prompted = await promptOnce(env, dateKey, closedAt);
  if (!prompted) {
    await recordSample(env, dateKey, { online: true, autoOffline: false, restored: false, lastSeen, now: now.getTime() });
    return { ok: true, action: "none", reason: "recovery-already-prompted", ageMinutes: 0, notified: 0 };
  }

  const body = insideHours
    ? "Your phone is back online. Tap to confirm you are taking orders again — the store is still paused."
    : `Your phone is back online, but your hours say the kitchen is closed (${slots.length === 0 ? "closed today" : `opens at ${slots[0]?.open ?? ""}`}). Tap to open early if you are cooking.`;
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
    reason: insideHours ? "long-outage" : "outside-hours",
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
    const existing = await env.UNDAL_KV.get(key);
    if (existing) return false;
    await env.UNDAL_KV.put(key, String(Date.now()), { expirationTtl: 86_400 });
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
