/**
 * What a missing heartbeat means — the policy, on its own.
 *
 * Like `alert-window.ts`, this file has no imports on purpose. The numbers in it
 * are the ones an owner feels: too eager and the store closes while somebody is
 * eating lunch, too patient and customers order from a kitchen that went to
 * sleep. `worker/test/presence-policy.test.ts` runs with the same Node the
 * deployment uses, and `crons/presence.ts` only carries the decisions out.
 *
 *   time since the device's last heartbeat    what happens
 *   ---------------------------------------   ---------------------------------
 *   0 – 6 min                                 present: nothing (and, if the store
 *                                             was paused, it comes back)
 *   6 – 10 min                                nothing yet: one missed beat is a
 *                                             slow network, not an outage
 *   over 10 min, store open                   the store is paused and the owner
 *                                             is told why
 *   over 10 min, store already closed         nothing: a closed kitchen has no
 *                                             orders to lose
 *
 * And when the device returns while the store is paused:
 *
 *   away ≤ 30 min and inside opening hours    restored automatically, and the
 *                                             owner is told it happened
 *   anything else                             the owner is asked, once, instead
 *                                             of a kitchen reopening itself
 *                                             outside the hours it published
 */

/** The owner's rule: ten minutes of silence while open pauses the store. */
export const STALE_AFTER_MS = 10 * 60 * 1000;

/** One missed five-minute beat is not an outage; two is. */
export const FRESH_AFTER_MS = 6 * 60 * 1000;

/** A pause this short is a blink and is undone without asking. */
export const RESTORE_WINDOW_MS = 30 * 60 * 1000;

export type PresenceAction =
  /** Nothing to decide: sample the day and move on. */
  | "none"
  /** Open the store: the device is back after a short, in-hours pause. */
  | "restore"
  /** Ask the owner: the device is back, but the pause should not undo itself. */
  | "remind"
  /** Pause the store: the device is gone and the store was open. */
  | "auto-offline";

export interface PresenceInput {
  /** When the device last checked in, or 0 if it never has. */
  lastSeenMillis: number;
  /** Now, in epoch millis. */
  nowMillis: number;
  /** `config/store.open`. */
  storeOpen: boolean;
  /** `config/store.autoClosed`. */
  autoClosed: boolean;
  /** When the watchdog applied the pause, or 0 if unknown. */
  autoClosedAtMillis: number;
  /** Whether the kitchen's own hours cover this minute. */
  insideHours: boolean;
}

export interface PresenceDecision {
  action: PresenceAction;
  /** One word a log line can carry, and the tests assert on. */
  reason: string;
  ageMinutes: number;
  /** Minutes the device was away when the pause was applied. */
  awayMinutes: number;
}

export function presenceDecision(input: PresenceInput): PresenceDecision {
  const lastSeen = Number.isFinite(input.lastSeenMillis) ? input.lastSeenMillis : 0;
  const age = input.nowMillis - lastSeen;
  const ageMinutes = lastSeen > 0 ? Math.max(0, Math.round(age / 60_000)) : 0;
  const fresh = lastSeen > 0 && age <= FRESH_AFTER_MS;

  if (fresh) {
    if (!input.autoClosed) {
      return { action: "none", reason: "device-present", ageMinutes, awayMinutes: 0 };
    }
    const away = input.autoClosedAtMillis > 0 ? input.nowMillis - input.autoClosedAtMillis : 0;
    const awayMinutes = Math.max(0, Math.round(away / 60_000));
    if (away > 0 && away <= RESTORE_WINDOW_MS && input.insideHours) {
      return { action: "restore", reason: "device-returned-within-window", ageMinutes, awayMinutes };
    }
    return {
      action: "remind",
      reason: input.insideHours ? "long-outage" : "outside-hours",
      ageMinutes,
      awayMinutes,
    };
  }

  if (input.autoClosed) {
    return { action: "none", reason: "auto-closed-device-absent", ageMinutes, awayMinutes: 0 };
  }
  if (!input.storeOpen) {
    return { action: "none", reason: "store-already-closed", ageMinutes, awayMinutes: 0 };
  }
  if (lastSeen === 0) {
    // Nothing has ever checked in and the store claims to be open: a device that
    // was never set up, not an outage. Pausing here would close a store that has
    // never opened on a phone at all.
    return { action: "none", reason: "device-never-seen", ageMinutes, awayMinutes: 0 };
  }
  return { action: "auto-offline", reason: "device-unreachable", ageMinutes, awayMinutes: 0 };
}
