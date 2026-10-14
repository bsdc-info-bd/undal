/**
 * The kitchen alert ladder — the piece that has to be right (PART I).
 *
 * A new order must reach the kitchen phone. Three independent facts make that
 * hard, and each of them has already been a real bug somewhere:
 *
 *   1. `vendorUid()` can return nothing (the bootstrap never ran, the account
 *      was recreated, the KV cache is cold and the query failed at 3 a.m.).
 *   2. The vendor device may have no token document — the app was reinstalled,
 *      the customer signed in on that phone, or the write was refused.
 *   3. FCM itself can drop a message: a phone in a lift, an aggressive OEM
 *      battery saver, a token that rotated a second before the send.
 *
 * The old code answered (1) and (2) by returning silently — `if (tokens.length
 * === 0) return;` — which turns a configuration mistake into a kitchen that
 * quietly stops receiving orders. This module makes that impossible:
 *
 *   · the alert goes to the vendor's tokens **and** to a topic every kitchen
 *     device subscribes to, so no account lookup can silence it;
 *   · the outcome is returned, never swallowed, so the caller can record it and
 *     the diagnostics screen can show it;
 *   · a topic fan-out needs no uid at all, so it works while (1) is broken.
 *
 * The remaining hole — a phone that was off — is covered by the re-alert sweep
 * in `crons/realert.ts`, which repeats the same push for as long as the order is
 * still unanswered.
 */
import type { Env } from "./env";
import { sendToTokens, sendToTopic, type FanOutResult } from "./fcmv1";
import { purgeTokens, tokensForUser, vendorUid } from "./recipients";

/**
 * The topic every kitchen device subscribes to.
 *
 * It is a constant on purpose: the Worker and the Android app have to agree on
 * it exactly, and `tools/check-web-rules.mjs` fails the build when the two
 * copies disagree. The wrangler variable can override it, but nothing depends on
 * that being set.
 */
export const KITCHEN_TOPIC = "undal_kitchen";

export function kitchenTopic(env: Env): string {
  const configured = (env.KITCHEN_TOPIC ?? "").trim();
  return configured.length > 0 ? configured : KITCHEN_TOPIC;
}

export interface KitchenAlert {
  orderId: string;
  shortId: string;
  title: string;
  body: string;
  /**
   * Message type. `order_created` (the default) makes the phone *ring*; anything
   * else is delivered as a notice. The distinction matters: an order that can
   * still be saved rings, and an order that has already been missed for half an
   * hour does not take the kitchen hostage — it is reported.
   */
  type?: string;
  /** Sub-kind for a notice (`missed`, …), read by the app's handler. */
  kind?: string;
  /** Message lifetime; the order path uses 300 s. */
  ttl?: string;
  /** Marks a diagnostics alert so the app can label it and stop itself. */
  test?: boolean;
  /** Any extra data fields the app's handler understands. */
  extra?: Record<string, string>;
}

export interface KitchenAlertOutcome {
  /** Device tokens found for the kitchen account (0 is a real, reportable fact). */
  tokens: number;
  tokenSent: number;
  tokenFailed: number;
  topic: string;
  topicSent: boolean;
  /**
   * Everything we have positive proof of. The topic counts as one, not as the
   * number of subscribers — the Worker cannot see FCM's subscription list, and
   * pretending otherwise would make a log line lie.
   */
  delivered: number;
  /** Set when nothing at all could be reached, so the caller can shout about it. */
  warning?: string;
}

/**
 * Sends one order alert to the kitchen by every route available.
 *
 * Never throws for a delivery problem: an alert failure must not fail an order.
 * It returns the outcome instead, and the caller decides whether to log, retry
 * or surface it.
 */
export async function raiseKitchenAlert(
  env: Env,
  alert: KitchenAlert,
  collapseKey?: string,
): Promise<KitchenAlertOutcome> {
  const topic = kitchenTopic(env);
  const data: Record<string, string> = {
    type: alert.type ?? "order_created",
    orderId: alert.orderId,
    shortId: alert.shortId,
    title: alert.title,
    body: alert.body,
    channelId: "vendor_new_order",
    clickAction: "vendor_order",
    ...(alert.test ? { test: "true" } : {}),
    ...(alert.kind ? { kind: alert.kind } : {}),
    ...(alert.extra ?? {}),
  };
  const options = { data, ttl: alert.ttl ?? "300s", collapseKey };

  const outcome: KitchenAlertOutcome = {
    tokens: 0,
    tokenSent: 0,
    tokenFailed: 0,
    topic,
    topicSent: false,
    delivered: 0,
  };

  // --- route 1: the kitchen account's own devices -------------------------
  const uid = await vendorUid(env).catch(() => null);
  if (!uid) {
    outcome.warning = "no vendor account document found (vendor/{uid})";
  } else {
    const tokens = await tokensForUser(env, uid).catch(() => [] as string[]);
    outcome.tokens = tokens.length;
    if (tokens.length === 0) {
      outcome.warning =
        "the kitchen account has no registered device token — open the Undal app on the kitchen phone and run the readiness check";
    } else {
      const fan: FanOutResult = await sendToTokens(env, tokens, options).catch(() => ({
        sent: 0,
        failed: tokens.length,
        unregisteredTokens: [] as string[],
      }));
      outcome.tokenSent = fan.sent;
      outcome.tokenFailed = fan.failed;
      if (fan.unregisteredTokens.length > 0) {
        await purgeTokens(env, uid, fan.unregisteredTokens).catch(() => undefined);
      }
    }
  }

  // --- route 2: the topic every kitchen device subscribes to --------------
  // This route does not consult the account at all, which is exactly why it is
  // here: a renamed, recreated or unprovisioned vendor account cannot silence an
  // order while a device is subscribed.
  const topicPush = await sendToTopic(env, topic, options).catch(() => ({
    ok: false as const,
    status: 0,
    code: "TOPIC_SEND_FAILED",
    unregistered: false,
  }));
  outcome.topicSent = topicPush.ok;
  if (!topicPush.ok && topicPush.code !== "NO_TARGET") {
    outcome.warning = outcome.warning ?? `topic fan-out failed (${topicPush.code})`;
  }

  outcome.delivered = outcome.tokenSent + (outcome.topicSent ? 1 : 0);
  return outcome;
}
