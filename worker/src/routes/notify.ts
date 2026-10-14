/**
 * Notification relay endpoints (PART J).
 *
 * All four require a verified Firebase ID token. Bodies are *hints*: the order
 * is always re-read from Firestore so a compromised client cannot forge content
 * that the kitchen or a customer sees.
 *
 * Time-to-live policy (documented deviation, chosen for the real kitchen):
 *   order_created → 300 s   the alert loop is time-critical and phones sleep;
 *                           the redundancy ladder still covers longer outages
 *   order_status  → 60 s    a stale status push is worse than none
 *   chat          → 3600 s  a late chat ping is still useful
 */
import type { Env } from "../env";
import { fsGet, fsCreateIfAbsent } from "../firestore";
import { sendToTokens } from "../fcmv1";
import { rtdbUpdate } from "../rtdb";
import { raiseKitchenAlert } from "../alerting";
import { HttpError, json, readJson } from "../util/http";
import { formatPaisa } from "../util/money";
import { purgeTokens, tokensForUser, vendorTokens, vendorUid } from "../recipients";
import { requireCaller, requireVendor } from "../authverify";

const ORDER_ALERT_TTL = "300s";
const ORDER_STATUS_TTL = "60s";
const CHAT_TTL = "3600s";
const CHAT_THROTTLE_SECONDS = 60;
const TEXT_PREVIEW_LIMIT = 140;

const STATUS_COPY: Record<string, { title: string; body: string }> = {
  accepted: {
    title: "Order accepted",
    body: "The kitchen has started cooking. Open the app to watch the timer.",
  },
  ready: {
    title: "Your order is ready",
    body: "Packed and waiting for the rider. Please be reachable on your phone.",
  },
  in_delivery: {
    title: "On the way",
    body: "Your Undal order left the kitchen. Keep the cash ready, please.",
  },
  delivered: {
    title: "Delivered",
    body: "Thank you for ordering from Undal HomeKitchen. Enjoy your meal.",
  },
  cancelled: {
    title: "Order cancelled",
    body: "The kitchen cancelled this order. Their note is inside the app.",
  },
};

function stringField(order: Record<string, unknown>, key: string): string {
  const value = order[key];
  return typeof value === "string" ? value : "";
}

export async function handleOrderCreated(env: Env, request: Request): Promise<Response> {
  const claims = await requireCaller(env, request);
  const body = await readJson<{ orderId?: string }>(request);
  const orderId = body.orderId?.trim();
  if (!orderId) throw new HttpError(400, "order-id-required");

  const order = await fsGet(env, `orders/${orderId}`);
  if (!order) throw new HttpError(404, "order-not-found");
  if (order.customerId !== claims.sub) throw new HttpError(403, "order-belongs-to-another-customer");

  const items = Array.isArray(order.items) ? order.items : [];
  const total = Number(order.totalAmountPaisa ?? 0);
  const shortId = stringField(order, "shortId");

  const outcome = await raiseKitchenAlert(
    env,
    {
      orderId,
      shortId,
      title: `New order ${shortId}`.trim(),
      body: `${items.length} item(s) · ${formatPaisa(total)} · cash on delivery`,
      ttl: ORDER_ALERT_TTL,
    },
    `order-${orderId}`,
  );

  // A kitchen with no reachable device is a fact the caller must see. It used to
  // be swallowed, which is how a silent kitchen stayed silent for a whole day.
  if (outcome.warning) {
    console.warn("undal-kitchen-alert", outcome.warning, JSON.stringify({ orderId, shortId }));
  }
  return json({
    ok: true,
    delivered: outcome.delivered,
    devices: outcome.tokens,
    sent: outcome.tokenSent,
    failed: outcome.tokenFailed,
    topic: outcome.topic,
    topicSent: outcome.topicSent,
    warning: outcome.warning ?? null,
  });
}

export async function handleOrderStatus(env: Env, request: Request): Promise<Response> {
  await requireVendor(env, request);
  const body = await readJson<{ orderId?: string }>(request);
  const orderId = body.orderId?.trim();
  if (!orderId) throw new HttpError(400, "order-id-required");

  const order = await fsGet(env, `orders/${orderId}`);
  if (!order) throw new HttpError(404, "order-not-found");

  const status = stringField(order, "status");
  const customerId = stringField(order, "customerId");
  if (!customerId) throw new HttpError(409, "order-missing-customer");

  const copy = STATUS_COPY[status] ?? {
    title: "Order updated",
    body: `Your Undal order is now ${status || "updated"}.`,
  };
  const note = stringField(order, "cancelNote") || stringField(order, "vendorNote");

  const notificationBody = note.length > 0 && status === "cancelled"
    ? `${copy.body} Note: ${note}`.slice(0, 240) : copy.body;
  // Write once on the server: a background display push does not run the app's
  // callback, and a phone may be offline when the event happens.
  await fsCreateIfAbsent(env, `notifInbox/${customerId}/items/${encodeURIComponent(`status-${orderId}-${status}`)}`, {
    title: copy.title, body: notificationBody, type: `order_status:${status}`,
    orderId, read: false, createdAt: new Date(),
  });
  const tokens = await tokensForUser(env, customerId);
  const outcome = await sendToTokens(env, tokens, {
    data: {
      type: "order_status",
      orderId,
      shortId: stringField(order, "shortId"),
      status,
      title: copy.title,
      body: notificationBody,
      deepLink: `customer/order/${orderId}`,
      inboxRecorded: "true",
      channelId: "customer_orders",
      clickAction: "customer_order",
    },
    display: { title: copy.title, body: notificationBody, channelId: "customer_orders", tag: `order-${orderId}` },
    ttl: ORDER_STATUS_TTL,
    collapseKey: `order-${orderId}`,
  });
  await purgeTokens(env, customerId, outcome.unregisteredTokens);

  // Mirror the sub-second flags the apps listen to (redundancy ladder ②).
  const prepEndsAtRaw = order.prepEndsAt;
  const prepEndsAt =
    typeof prepEndsAtRaw === "string" && prepEndsAtRaw.length > 0 ? Date.parse(prepEndsAtRaw) : null;
  await rtdbUpdate(env, `orderLive/${orderId}`, {
    customerId,
    status,
    prepEndsAt,
    updatedAt: Date.now(),
    vendorNote: note.slice(0, 300),
  }).catch(() => undefined);

  return json({ ok: true, status, sent: outcome.sent, failed: outcome.failed });
}

export async function handleChat(env: Env, request: Request): Promise<Response> {
  const claims = await requireCaller(env, request);
  const body = await readJson<{ orderId?: string; text?: string }>(request);
  const orderId = body.orderId?.trim();
  const text = (body.text ?? "").trim();
  if (!orderId || text.length === 0) throw new HttpError(400, "order-id-and-text-required");

  const order = await fsGet(env, `orders/${orderId}`);
  if (!order) throw new HttpError(404, "order-not-found");

  const callerIsVendor = claims.vendor === true;
  const callerIsCustomer = order.customerId === claims.sub;
  if (!callerIsVendor && !callerIsCustomer) throw new HttpError(403, "not-a-chat-participant");

  const sender = callerIsVendor ? "vendor" : "customer";
  const throttleKey = `chat:throttle:${orderId}:${sender}`;
  const lastChat = Number(await env.UNDAL_KV.get(throttleKey));
  if (lastChat > Date.now() - 3000) return json({ ok: true, throttled: true });
  // KV requires a TTL of at least 60s; store the timestamp for the 3s window.
  await env.UNDAL_KV.put(throttleKey, String(Date.now()), { expirationTtl: CHAT_THROTTLE_SECONDS });

  const recipientUid = callerIsVendor ? stringField(order, "customerId") : await vendorUid(env);
  const tokens = await tokensForUser(env, recipientUid);
  const preview = text.length > TEXT_PREVIEW_LIMIT ? `${text.slice(0, TEXT_PREVIEW_LIMIT - 1)}…` : text;

  const outcome = await sendToTokens(env, tokens, {
    data: {
      type: "chat",
      orderId,
      shortId: stringField(order, "shortId"),
      from: sender,
      title: sender === "vendor" ? "Message from Undal kitchen" : "Message from your customer",
      body: preview,
      channelId: sender === "vendor" ? "customer_orders" : "vendor_general",
      clickAction: "chat",
    },
    ttl: CHAT_TTL,
    collapseKey: `chat-${orderId}`,
  });
  await purgeTokens(env, recipientUid, outcome.unregisteredTokens);

  return json({ ok: true, sender, sent: outcome.sent, failed: outcome.failed });
}

export async function handleVendorSelf(env: Env, request: Request): Promise<Response> {
  await requireVendor(env, request);
  const body = await readJson<{
    kind?: string;
    title?: string;
    body?: string;
    orderId?: string;
  }>(request);

  const kind = (body.kind ?? "notice").slice(0, 40);
  const title = (body.title ?? "Undal kitchen").slice(0, 90);
  const text = (body.body ?? "").slice(0, 180);
  const orderId = (body.orderId ?? "").slice(0, 120);

  const tokens = await vendorTokens(env);
  const outcome = await sendToTokens(env, tokens, {
    data: {
      type: "vendor_self",
      kind,
      orderId,
      title,
      body: text,
      channelId: kind === "prep_due" ? "vendor_general" : "vendor_new_order",
      clickAction: "vendor_order",
    },
    ttl: ORDER_ALERT_TTL,
  });
  await purgeTokens(env, await vendorUid(env), outcome.unregisteredTokens);

  return json({ ok: true, sent: outcome.sent, failed: outcome.failed, devices: tokens.length });
}

/**
 * "Ring my phone" — the one test that proves the whole chain.
 *
 * The readiness check on the kitchen phone can verify everything the device is
 * allowed to know: the permission is granted, the channel is loud, the token is
 * registered, the heartbeat is fresh. Only a real push proves the part it cannot
 * see — that the Worker can reach FCM, that FCM accepts the token, and that the
 * device rings. So this sends the *real* `order_created` message with a `test`
 * marker, and the app rings exactly as it would for a real order, labels itself
 * as a test and stops itself.
 *
 * The failure detail matters more than the success: `TOKEN_UNREGISTERED`, a
 * missing vendor document and a topic that FCM refused are three different
 * repairs, and the response says which one it is.
 */
export async function handleTestAlert(env: Env, request: Request): Promise<Response> {
  const claims = await requireVendor(env, request);
  const body = await readJson<{ shortId?: string }>(request).catch(() => ({ shortId: undefined }));
  const shortId = (body.shortId ?? `TEST-${new Date().toISOString().slice(11, 19)}`).slice(0, 40);

  const outcome = await raiseKitchenAlert(
    env,
    {
      orderId: `test-${crypto.randomUUID()}`,
      shortId,
      title: "Kitchen alert test",
      body: "This is a test. If you can hear this, the kitchen will ring for a real order.",
      ttl: "120s",
      test: true,
      extra: { kind: "test" },
    },
    undefined,
  );

  return json(
    {
      ok: true,
      caller: claims.sub,
      devices: outcome.tokens,
      sent: outcome.tokenSent,
      failed: outcome.tokenFailed,
      topic: outcome.topic,
      topicSent: outcome.topicSent,
      delivered: outcome.delivered,
      warning: outcome.warning ?? null,
    },
    200,
    { "cache-control": "no-store" },
  );
}
