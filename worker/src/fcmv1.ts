/**
 * Firebase Cloud Messaging — HTTP v1 only (LAW-9).
 *
 * Three rules shape this file:
 *   1. Kitchen alarm messages are DATA-ONLY. A `notification` payload would let the system
 *      tray swallow the message in the background and `onMessageReceived` would
 *      never run — which would break the vendor order-alert loop (PART I).
 *   2. A token that FCM reports as UNREGISTERED must be deleted from
 *      fcmTokens/{uid}/tokens, otherwise dead tokens accumulate forever.
 *   3. Every message says how urgently it should be delivered, on both platforms
 *      — `android.priority` and the APNs headers below. "High priority" is the
 *      difference between a phone that rings now and one that rings whenever the
 *      radio next wakes up.
 */
import type { Env } from "./env";
import { projectId } from "./env";
import { accessToken, invalidateAccessToken } from "./gcp";

/**
 * How long FCM keeps trying to deliver a message to an offline device.
 *
 * Five minutes, not zero, and not because zero was overlooked: the per-minute
 * re-alert cron (`crons/realert.ts`) re-sends anything still unanswered, so a
 * device that comes back after four minutes still gets the order it missed. A
 * zero TTL would drop it on the floor and leave the re-alert as the only route,
 * which is one more thing to fail. `docs/ALERT-RELIABILITY.md` records the
 * reasoning and the trade.
 */
export const DEFAULT_TTL = "300s";

/** `"300s"` → 300, `"5m"` → 300, `"1h"` → 3600, anything unparseable → 0. */
export function ttlSeconds(ttl: string | undefined): number {
  const match = /^\s*(\d+)\s*([smhd])?\s*$/i.exec(ttl ?? "");
  if (!match?.[1]) return 0;
  const value = Number(match[1]);
  switch ((match[2] ?? "s").toLowerCase()) {
    case "m": return value * 60;
    case "h": return value * 3600;
    case "d": return value * 86400;
    default: return value;
  }
}

/**
 * The iOS half of "deliver this immediately".
 *
 * Apple decides what a priority means by the shape of the push, so the urgent
 * instruction is expressed the only way each shape allows:
 *
 *   · a push with something to show takes `apns-priority: 10` — send now, even
 *     if that means waking the radio;
 *   · a data-only push takes `apns-priority: 5` with `content-available`, because
 *     APNs refuses a background push at 10 with `BadPriority`. Priority 5 is not
 *     a downgrade here: for a background push it is the only legal value, and the
 *     wake-up guarantee on iOS comes from the app's own VoIP-style alarm
 *     handling, not from this header.
 *
 * `apns-expiration` is derived from the same TTL the Android side carries, so a
 * stale alert does not survive on one platform longer than the other.
 *
 * None of this reaches an Android device — FCM drops the apns block for Android
 * tokens. It is here so that a kitchen running an iPhone rings correctly on the
 * day one appears, instead of being silently throttled.
 */
function apnsConfig(showsNotification: boolean, urgent: boolean, ttl: string): Record<string, unknown> {
  const expiration = String(Math.floor(Date.now() / 1000) + ttlSeconds(ttl));
  if (showsNotification && urgent) {
    return { headers: { "apns-priority": "10", "apns-expiration": expiration } };
  }
  return {
    headers: {
      "apns-priority": "5",
      "apns-push-type": "background",
      "apns-expiration": expiration,
    },
    payload: { aps: { "content-available": 1 } },
  };
}

export interface PushOptions {
  data: Record<string, string>;
  /** Message time-to-live, e.g. "300s". */
  ttl?: string;
  collapseKey?: string;
  highPriority?: boolean;
  /** Customer status only: Android displays this even without an app callback. */
  display?: { title: string; body: string; channelId: string; tag: string };
}

export type PushResult =
  | { ok: true }
  | { ok: false; status: number; code: string; unregistered: boolean };

export function pushBody(data: Record<string, string>, options: PushOptions): string {
  // FCM v1 refuses a message whose data values are not strings — the whole
  // message is rejected with INVALID_ARGUMENT, not just the field. Every caller
  // here is TypeScript and already passes strings; the coercion is for the day a
  // cron builds a payload from a Firestore number and an order alert is lost to
  // a type that only exists at runtime.
  const payload: Record<string, string> = {};
  for (const [key, value] of Object.entries(data)) {
    payload[key] = typeof value === "string" ? value : String(value ?? "");
  }

  const ttl = options.ttl ?? DEFAULT_TTL;
  const urgent = options.highPriority !== false;
  // An order alert never carries a display block, even if a caller passes one:
  // the alarm is raised by `onMessageReceived`, and a system-tray notification
  // would arrive instead of it.
  const showsNotification = Boolean(options.display) && payload.type !== "order_created";
  const message: Record<string, unknown> = {
    data: payload,
    android: {
      priority: urgent ? "HIGH" : "NORMAL",
      ttl,
    },
    apns: apnsConfig(showsNotification, urgent, ttl),
  };
  if (showsNotification && options.display) {
    message.notification = { title: options.display.title, body: options.display.body };
    (message.android as Record<string, unknown>).notification = {
      channel_id: options.display.channelId, tag: options.display.tag,
      default_sound: true, default_vibrate_timings: true, visibility: "PRIVATE",
      icon: "ic_undal_mark", color: "#165B3B",
    };
  }
  if (options.collapseKey) {
    (message.android as Record<string, unknown>).collapse_key = options.collapseKey;
  }
  return JSON.stringify({ message });
}

async function send(env: Env, target: { token?: string; topic?: string }, options: PushOptions): Promise<PushResult> {
  if (!target.token && !target.topic) return { ok: false, status: 400, code: "NO_TARGET", unregistered: false };

  const endpoint = `https://fcm.googleapis.com/v1/projects/${projectId(env)}/messages:send`;
  const payload = pushBody(options.data, options);
  const withTarget = (raw: string): string => {
    const parsed = JSON.parse(raw) as { message: Record<string, unknown> };
    if (target.token) parsed.message.token = target.token;
    if (target.topic) parsed.message.topic = target.topic;
    return JSON.stringify(parsed);
  };

  const attempt = async (): Promise<Response> =>
    fetch(endpoint, {
      method: "POST",
      headers: {
        authorization: `Bearer ${await accessToken(env)}`,
        "content-type": "application/json",
      },
      body: withTarget(payload),
    });

  let response = await attempt();
  if (response.status === 401) {
    await invalidateAccessToken(env);
    response = await attempt();
  }

  if (response.ok) return { ok: true };

  const raw = await response.text();
  let code = `HTTP_${response.status}`;
  try {
    const parsed = JSON.parse(raw) as {
      error?: { status?: string; details?: Array<{ errorCode?: string }> };
    };
    code = parsed.error?.details?.[0]?.errorCode ?? parsed.error?.status ?? code;
  } catch {
    // keep the HTTP-derived code
  }
  const unregistered =
    code === "UNREGISTERED" ||
    code === "NOT_FOUND" ||
    (response.status === 400 && /not a valid FCM registration token/i.test(raw));
  return { ok: false, status: response.status, code, unregistered };
}

export async function sendToToken(env: Env, token: string, options: PushOptions): Promise<PushResult> {
  return send(env, { token }, options);
}

export async function sendToTopic(env: Env, topic: string, options: PushOptions): Promise<PushResult> {
  return send(env, { topic }, options);
}

export interface FanOutResult {
  sent: number;
  failed: number;
  unregisteredTokens: string[];
}

/**
 * Fans a message out over a device list with bounded concurrency.
 * FCM v1 has no multicast endpoint (the legacy `registration_ids` field is
 * forbidden), so the loop below is the supported pattern.
 */
export async function sendToTokens(
  env: Env,
  tokens: string[],
  options: PushOptions,
  concurrency = 20,
): Promise<FanOutResult> {
  const result: FanOutResult = { sent: 0, failed: 0, unregisteredTokens: [] };
  const queue = [...new Set(tokens.filter(Boolean))];

  const worker = async (): Promise<void> => {
    for (;;) {
      const token = queue.shift();
      if (!token) return;
      const outcome = await sendToToken(env, token, options).catch((): PushResult => ({
        ok: false, status: 503, code: "TRANSPORT_ERROR", unregistered: false,
      }));
      if (outcome.ok) {
        result.sent += 1;
      } else {
        result.failed += 1;
        if (outcome.unregistered) result.unregisteredTokens.push(token);
      }
    }
  };

  const workers = Array.from({ length: Math.min(Math.max(1, Math.floor(concurrency) || 1), Math.max(1, queue.length)) }, worker);
  await Promise.all(workers);
  return result;
}
