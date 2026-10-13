/**
 * Cron `*​/5 * * * *` — vendor presence watchdog (PART I-4 · V-076).
 *
 * The vendor app writes presence/vendor.lastSeen at least every five minutes.
 * If the kitchen device has been unreachable for fifteen minutes while the store
 * is open, the store is closed automatically and the owner is pushed a notice
 * with a reopen action — before a customer ever sees an unanswerable order form.
 */
import type { Env } from "../env";
import { fsGet, fsPatchWithServerTimestamps } from "../firestore";
import { sendToTokens } from "../fcmv1";
import { rtdbGet } from "../rtdb";
import { vendorTokens } from "../recipients";
import { storeStatus, type StoreConfig } from "../util/store-hours";

const STALE_AFTER_MS = 15 * 60 * 1000;

interface PresenceRow {
  lastSeen?: number;
  online?: boolean;
  device?: string;
}

export interface PresenceCheckResult {
  ok: boolean;
  autoClosed: boolean;
  reason?: string;
  ageMinutes?: number;
  notified?: number;
}

export async function runPresenceCheck(env: Env, now: Date = new Date()): Promise<PresenceCheckResult> {
  const config = (await fsGet(env, "config/store")) as StoreConfig | null;
  if (!config) return { ok: true, autoClosed: false, reason: "store-config-missing" };
  if (config.autoClosed === true) return { ok: true, autoClosed: false, reason: "already-auto-closed" };
  if (config.open !== true) return { ok: true, autoClosed: false, reason: "store-already-closed" };

  const presence = await rtdbGet<PresenceRow>(env, "presence/vendor");
  const lastSeen = typeof presence?.lastSeen === "number" ? presence.lastSeen : 0;
  const age = now.getTime() - lastSeen;
  const ageMinutes = Math.max(0, Math.round(age / 60_000));

  if (lastSeen > 0 && age <= STALE_AFTER_MS) {
    return { ok: true, autoClosed: false, reason: "device-present", ageMinutes };
  }

  await fsPatchWithServerTimestamps(
    env,
    "config/store",
    {
      open: false,
      autoClosed: true,
      autoClosedReason: lastSeen === 0 ? "device-never-seen" : "device-unreachable",
      autoClosedAt: now,
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
      body: `No heartbeat for ${ageMinutes} minute(s), so new orders are paused (${status.headline}). Tap to reopen.`,
      channelId: "vendor_new_order",
      clickAction: "vendor_store_settings",
    },
    ttl: "86400s",
  });

  return { ok: true, autoClosed: true, ageMinutes, notified: outcome.sent };
}
