/**
 * Firebase Cloud Messaging — HTTP v1 only (LAW-9).
 *
 * Two rules shape this file:
 *   1. Kitchen alarm messages are DATA-ONLY. A `notification` payload would let the system
 *      tray swallow the message in the background and `onMessageReceived` would
 *      never run — which would break the vendor order-alert loop (PART I).
 *   2. A token that FCM reports as UNREGISTERED must be deleted from
 *      fcmTokens/{uid}/tokens, otherwise dead tokens accumulate forever.
 */
import type { Env } from "./env";
import { projectId } from "./env";
import { accessToken, invalidateAccessToken } from "./gcp";

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
  const message: Record<string, unknown> = {
    data,
    android: {
      priority: options.highPriority === false ? "NORMAL" : "HIGH",
      ttl: options.ttl ?? "300s",
    },
  };
  if (options.display && data.type !== "order_created") {
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
