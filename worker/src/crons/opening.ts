/**
 * Cron `*​/5 * * * *` — "are you cooking tonight?" (PART I-4 · the owner's rule).
 *
 * A kitchen that opens at 21:00 and forgets to open the store loses the first
 * orders of the night, and the person who finds out is a customer staring at
 * "Closed". This sweep is the reminder that arrives *before* that: thirty
 * minutes before a service the schedule says is about to start, the kitchen's
 * phone is pushed one question — are you taking orders? — with a tap that opens
 * the store screen with the switch already in reach.
 *
 * Four rules keep it from becoming noise, and each one is load-bearing:
 *
 *  1. **Only one reminder per service, ever.** The KV key is the date and the
 *     service's opening minute, written with a twelve-hour life. A sweep every
 *     five minutes therefore reminds once, and tomorrow's identical 21:00 is a
 *     new key.
 *  2. **Only when the store is actually closed.** An owner who already opened is
 *     not asked to open again.
 *  3. **Only when the device is actually there.** Pushing a phone that stopped
 *     beating five minutes ago is how a kitchen learns to ignore Undal pushes;
 *     the presence sweep has already closed that store and said why.
 *  4. **Only inside the reminder window.** The window is a half hour, so a slot
 *     the schedule has already entered (the kitchen is late by ten minutes) is
 *     still reminded, and one that starts in four hours is not.
 */
import type { Env } from "../env";
import { fsGet } from "../firestore";
import { sendToTokens } from "../fcmv1";
import { rtdbGet } from "../rtdb";
import { vendorTokens } from "../recipients";
import { dhakaClock, slotsForDate, parseHhMm, type StoreConfig, type StoreSlot } from "../util/store-hours";
import { FRESH_AFTER_MS } from "./presence";

/** How long before a service the kitchen is asked. The owner's example: 20:30 for 21:00. */
export const REMIND_BEFORE_MINUTES = 30;

/** How late a service may be and still be worth asking about. */
export const REMIND_GRACE_MINUTES = 15;

/**
 * The service a reminder is about, if any: the next one whose opening is inside
 * the window — still ahead of us, or already begun by no more than the grace
 * period. Pure, exported, and proven by `worker/test/presence-policy.test.ts`.
 */
export function serviceInWindow(
  slots: StoreSlot[],
  minutesNow: number,
): { service: string; minutesUntil: number } | null {
  const candidate = slots
    .map((slot) => ({ service: slot.open, minutesUntil: parseHhMm(slot.open) - minutesNow }))
    .filter(
      (entry) => entry.minutesUntil <= REMIND_BEFORE_MINUTES && entry.minutesUntil >= -REMIND_GRACE_MINUTES,
    )
    .sort((left, right) => left.minutesUntil - right.minutesUntil)[0];
  return candidate ?? null;
}

export interface OpeningReminderResult {
  ok: boolean;
  reminded: boolean;
  reason: string;
  /** The service the reminder was about, "HH:MM" Dhaka, when there was one. */
  service?: string;
  minutesUntil?: number;
  notified?: number;
}

export async function runOpeningReminder(env: Env, now: Date = new Date()): Promise<OpeningReminderResult> {
  const config = (await fsGet(env, "config/store")) as StoreConfig | null;
  if (!config) return { ok: true, reminded: false, reason: "store-config-missing" };

  const clock = dhakaClock(now);
  const slots = slotsForDate(config, clock);
  if (slots.length === 0) return { ok: true, reminded: false, reason: "closed-today" };

  const candidate = serviceInWindow(slots, clock.minutes);
  if (!candidate) return { ok: true, reminded: false, reason: "no-service-in-window" };
  const service = candidate.service;
  const minutesUntil = candidate.minutesUntil;

  if (config.autoClosed === true) {
    // Already handled by the presence sweep, which pushed its own notice. Two
    // pushes about the same closed store is the noise this file exists to avoid.
    return { ok: true, reminded: false, reason: "store-auto-closed", service, minutesUntil };
  }
  if (config.open === true) {
    return { ok: true, reminded: false, reason: "store-already-open", service, minutesUntil };
  }

  // Rule 3: a phone that has stopped beating is a phone that will not ring, and
  // asking it to open a store would create an order nobody sees.
  const presence = await rtdbGet<{ lastSeen?: number }>(env, "presence/vendor");
  const lastSeen = typeof presence?.lastSeen === "number" ? presence.lastSeen : 0;
  if (lastSeen === 0 || now.getTime() - lastSeen > FRESH_AFTER_MS * 3) {
    return { ok: true, reminded: false, reason: "device-absent", service, minutesUntil };
  }

  // Rule 1: one reminder per service.
  const key = `presence:opening-reminder:${clock.dateKey}:${service}`;
  try {
    const already = await env.UNDAL_KV.get(key);
    if (already) return { ok: true, reminded: false, reason: "already-reminded", service, minutesUntil };
    await env.UNDAL_KV.put(key, String(now.getTime()), { expirationTtl: 43_200 });
  } catch {
    // KV unreachable: one duplicate reminder is better than a silent kitchen.
  }

  const when =
    minutesUntil <= 0
      ? "should have started by now"
      : minutesUntil === 1
        ? "starts in a minute"
        : `starts in ${minutesUntil} minutes`;
  const tokens = await vendorTokens(env);
  const outcome = await sendToTokens(env, tokens, {
    data: {
      type: "vendor_self",
      kind: "opening_soon",
      title: `You open at ${service}`,
      body:
        `Today's service ${when}. Tap to confirm you are taking orders — if you are, the store opens and orders ` +
        "ring on this phone immediately.",
      channelId: "vendor_general",
      clickAction: "vendor/dashboard",
    },
    ttl: "10800s",
  });

  return { ok: true, reminded: true, reason: "reminded", service, minutesUntil, notified: outcome.sent };
}
