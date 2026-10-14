/**
 * When the kitchen is alerted again — the policy, on its own.
 *
 * This file has no imports on purpose. It is the one piece of the alert ladder
 * whose numbers can be wrong in a way nobody notices (too short and the kitchen
 * is spammed every minute forever; too long and a missed order stays missed), so
 * it is kept pure and is covered by `worker/test/alert-window.test.ts`, which
 * runs with the same Node the deployment uses.
 *
 * The shape is windows, never thresholds:
 *
 *   age of the order               what happens
 *   ---------------------------    --------------------------------------------
 *   0 s   – 45 s                   nothing (the first push is still in flight)
 *   45 s  – 15 min                 re-alert every sweep — the phone may have
 *                                  been asleep, offline or updating
 *   15 min – 27 min                silence (ringing for half an hour is a
 *                                  device problem, not a delivery problem)
 *   27 min – 29 min                one "never answered" notice
 *   older                          silence, forever
 *
 * The last line is the important one: an order left `placed` because nobody ever
 * touched it must not ring a phone every minute for the rest of the week.
 */

/** First re-alert. Long enough that the first push is not duplicated. */
export const REALERT_FROM_SECONDS = 45;

/** Last re-alert. */
export const REALERT_UNTIL_SECONDS = 15 * 60;

/** The single "this order was never answered" notice. */
export const MISSED_FROM_SECONDS = 27 * 60;
export const MISSED_UNTIL_SECONDS = 29 * 60;

export type SweepAction = "realert" | "missed" | "silent";

/** What the sweep should do for an order that has been `placed` this long. */
export function sweepAction(ageSeconds: number): SweepAction {
  if (ageSeconds >= MISSED_FROM_SECONDS && ageSeconds < MISSED_UNTIL_SECONDS) return "missed";
  if (ageSeconds >= REALERT_FROM_SECONDS && ageSeconds < REALERT_UNTIL_SECONDS) return "realert";
  return "silent";
}

/** Minutes the order has waited, for the text a person reads. */
export function waitedMinutes(ageSeconds: number): number {
  return Math.max(1, Math.round(ageSeconds / 60));
}
