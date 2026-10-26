/**
 * The alert wire shape — what actually leaves the Worker for FCM.
 *
 * This is the payload a sleeping kitchen phone has to react to, so every field
 * here is load-bearing rather than decorative:
 *
 *   · `notification` must be absent for an order alert. With it, Android hands
 *     the message to the system tray and `onMessageReceived` never runs, so the
 *     alarm channel, the wake lock and the looping audio never start. The order
 *     silently becomes a quiet icon.
 *   · `android.priority: HIGH` is what lets a dozing device use the radio
 *     immediately instead of waiting for the next maintenance window.
 *   · `ttl` decides how long FCM keeps trying. Zero would drop an alert the
 *     moment the phone went offline; five minutes gives the re-alert cron
 *     something to overlap with. `docs/ALERT-RELIABILITY.md` records the trade.
 *   · the APNs half exists so an iPhone kitchen would behave the same way, at
 *     the priority Apple actually permits for each shape of push.
 *   · the ladder must never go quiet because one lookup failed: a missing vendor
 *     document, an empty token list or a dead token each still leave the topic
 *     route standing, and each is reported in the outcome.
 *
 * A regression in any of these passes a typecheck and every other test, and
 * shows up only as a merchant who stops hearing orders. Hence the file.
 */
import assert from "node:assert/strict";
import { test } from "node:test";
import { DEFAULT_TTL, pushBody, ttlSeconds } from "../src/fcmv1";
import { KITCHEN_TOPIC, raiseKitchenAlert } from "../src/alerting";
import { FakeFirebase } from "./helpers/fake-firebase";

interface WireMessage {
  data: Record<string, string>;
  notification?: { title: string; body: string };
  android: Record<string, any>;
  apns: Record<string, any>;
}

const message = (data: Record<string, string>, options: unknown): WireMessage =>
  (JSON.parse(pushBody(data, options as never)) as { message: WireMessage }).message;

/**
 * The exact data map `alerting.ts` sends for a new order, spelled out here so a
 * change on either side fails an assertion instead of leaving a phone holding a
 * message it no longer recognises.
 */
const orderData = (): Record<string, string> => ({
  type: "order_created",
  orderId: "order-1",
  shortId: "UND-1042",
  title: "New order UND-1042",
  body: "Tap to review and accept",
  channelId: "vendor_new_order",
  clickAction: "vendor_order",
});

const orderAlert = (): WireMessage =>
  message(orderData(), { data: orderData(), highPriority: true });

const statusPush = (): WireMessage =>
  message(
    { type: "order_status", orderId: "order-1", status: "accepted" },
    {
      data: { type: "order_status", orderId: "order-1", status: "accepted" },
      highPriority: true,
      display: { title: "Order accepted", body: "The kitchen is cooking.", channelId: "customer_orders", tag: "order-1" },
    },
  );

// ------------------------------------------------------------- the payload

test("an order alert is data-only, high priority, and carries no notification block", () => {
  const alert = orderAlert();
  assert.equal(alert.notification, undefined, "a notification block would bypass onMessageReceived entirely");
  assert.equal(alert.android.notification, undefined, "and so would an Android-specific one");
  assert.equal(alert.data.type, "order_created", "the FCM service switches on this exact value");
  assert.equal(alert.data.channelId, "vendor_new_order", "the alarm channel, not the fallback one");
  assert.equal(alert.data.clickAction, "vendor_order");
  assert.equal(alert.android.priority, "HIGH");
  assert.equal(alert.android.ttl, DEFAULT_TTL);
  assert.equal(alert.android.collapse_key, undefined, "a new order must never be collapsed away by the one after it");
});

test("a notice may collapse, because four collapse keys is Android's whole budget", () => {
  const notice = message(
    { ...orderData(), type: "vendor_self", kind: "missed" },
    { data: { ...orderData(), type: "vendor_self", kind: "missed" }, collapseKey: "missed-order-1" },
  );
  assert.equal(notice.android.collapse_key, "missed-order-1");
});

test("an order alert keeps its data even when a caller hands it a display block", () => {
  const alert = message(
    { type: "order_created", orderId: "order-1" },
    {
      data: { type: "order_created", orderId: "order-1" },
      display: { title: "New order", body: "Someone is hungry", channelId: "vendor_new_order", tag: "order-1" },
    },
  );
  assert.equal(alert.notification, undefined, "the alarm is raised by the app, never by the tray");
  assert.equal(alert.android.notification, undefined);
});

test("the default TTL is five minutes: long enough for the re-alert cron to overlap", () => {
  assert.equal(DEFAULT_TTL, "300s");
  assert.equal(ttlSeconds("300s"), 300);
  assert.equal(ttlSeconds("5m"), 300);
  assert.equal(ttlSeconds("1h"), 3600);
  assert.equal(ttlSeconds("2d"), 172800);
  assert.equal(ttlSeconds("0s"), 0);
  assert.equal(ttlSeconds(undefined), 0);
  assert.equal(ttlSeconds("soon"), 0);
});

test("a routine message is not dressed up as an emergency", () => {
  const routine = message({ type: "presence" }, { data: { type: "presence" }, highPriority: false });
  assert.equal(routine.android.priority, "NORMAL");
  assert.equal(routine.apns.headers["apns-priority"], "5");
});

test("a customer status push is allowed to be visible, on its own channel", () => {
  const status = statusPush();
  assert.deepEqual(status.notification, { title: "Order accepted", body: "The kitchen is cooking." });
  assert.equal(status.android.notification.channel_id, "customer_orders", "the customer's channel, not the kitchen's alarm");
  assert.equal(status.android.notification.tag, "order-1", "one notification per order, replaced as it moves");
  assert.equal(status.android.notification.visibility, "PRIVATE");
});

test("iOS is told how urgent each shape of push is, at a priority Apple permits", () => {
  const alert = orderAlert();
  assert.equal(alert.apns.headers["apns-push-type"], "background");
  assert.equal(
    alert.apns.headers["apns-priority"],
    "5",
    "a background push at 10 is refused by APNs with BadPriority, so 5 is the urgent-and-legal value",
  );
  assert.equal(alert.apns.payload.aps["content-available"], 1, "the iOS equivalent of a data message");

  const status = statusPush();
  assert.equal(status.apns.headers["apns-priority"], "10", "a visible push may demand immediate delivery");
  assert.equal(status.apns.payload, undefined, "FCM builds the aps alert from the notification block itself");
});

test("both platforms stop chasing a message at the same moment", () => {
  const before = Math.floor(Date.now() / 1000);
  const alert = orderAlert();
  const after = Math.floor(Date.now() / 1000);
  const expiration = Number(alert.apns.headers["apns-expiration"]);
  assert.ok(
    expiration >= before + 300 && expiration <= after + 300,
    `apns-expiration should be the TTL from now, got ${expiration}`,
  );

  const immediate = message({ type: "order_created" }, { data: { type: "order_created" }, highPriority: true, ttl: "0s" });
  assert.equal(immediate.android.ttl, "0s");
  const zero = Number(immediate.apns.headers["apns-expiration"]);
  assert.ok(zero >= before && zero <= after, "a zero TTL expires now on both platforms");
});

test("a number that reaches the payload is stringified rather than losing the message", () => {
  const alert = message(
    { type: "order_created", totalPaisa: 45000, retry: 2 } as unknown as Record<string, string>,
    { data: { type: "order_created", totalPaisa: 45000, retry: 2 }, highPriority: true },
  );
  for (const [key, value] of Object.entries(alert.data)) {
    assert.equal(typeof value, "string", `FCM v1 refuses a non-string data value (${key})`);
  }
  assert.equal(alert.data.totalPaisa, "45000");
  assert.equal(alert.data.retry, "2");
  assert.equal(alert.notification, undefined, "coercion does not change what kind of message this is");
});

// -------------------------------------------------------------- the ladder

/** A kitchen with two registered phones and a vendor document to find them by. */
async function armedKitchen(backend: FakeFirebase): Promise<void> {
  backend.setDocument("vendor/kitchen-1", { role: "vendor", name: "Undal HomeKitchen" });
  backend.setDocument("fcmTokens/kitchen-1/tokens/token-one-0123456789abcdef", { platform: "android" });
  backend.setDocument("fcmTokens/kitchen-1/tokens/token-two-0123456789abcdef", { platform: "android" });
}

test("one new order reaches every kitchen device and the topic, data-only and urgent", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    await armedKitchen(backend);
    const outcome = await raiseKitchenAlert(backend.env, {
      orderId: "order-1",
      shortId: "UND-1042",
      title: "New order UND-1042",
      body: "Tap to review and accept",
    });

    assert.equal(outcome.tokens, 2);
    assert.equal(outcome.tokenSent, 2);
    assert.equal(outcome.tokenFailed, 0);
    assert.equal(outcome.topic, KITCHEN_TOPIC);
    assert.equal(outcome.topicSent, true);
    assert.equal(outcome.delivered, 3, "two devices plus one topic fan-out");
    assert.equal(outcome.warning, undefined);
    assert.equal(backend.fcmSends.length, 3);

    for (const send of backend.fcmSends) {
      assert.equal(send.message.notification, undefined, "no route to the kitchen may carry a notification block");
      assert.equal(send.message.android.priority, "HIGH");
      assert.equal(send.message.android.ttl, DEFAULT_TTL);
      assert.equal(send.message.data.type, "order_created");
      assert.equal(send.message.data.orderId, "order-1");
      assert.equal(send.message.data.channelId, "vendor_new_order");
    }
    assert.deepEqual(
      backend.fcmSends.map((send) => send.message.token ?? send.message.topic).sort(),
      ["token-one-0123456789abcdef", "token-two-0123456789abcdef", KITCHEN_TOPIC].sort(),
    );
  } finally {
    restore();
  }
});

test("a kitchen with no registered device still gets the topic, and the outcome says why", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    backend.setDocument("vendor/kitchen-1", { role: "vendor", name: "Undal HomeKitchen" });
    const outcome = await raiseKitchenAlert(backend.env, { orderId: "o", shortId: "UND-1", title: "t", body: "b" });
    assert.equal(outcome.tokens, 0);
    assert.equal(outcome.topicSent, true, "the topic route needs no account lookup at all");
    assert.match(outcome.warning ?? "", /no registered device token/);
    assert.equal(backend.fcmSends.length, 1);
    assert.equal(backend.fcmSends[0].message.topic, KITCHEN_TOPIC);
  } finally {
    restore();
  }
});

test("a missing vendor document is reported rather than swallowed into silence", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const outcome = await raiseKitchenAlert(backend.env, { orderId: "o", shortId: "UND-1", title: "t", body: "b" });
    assert.match(outcome.warning ?? "", /no vendor account document/);
    assert.equal(outcome.topicSent, true, "the order still rings every subscribed kitchen phone");
  } finally {
    restore();
  }
});

test("a token FCM reports as dead is removed from the device list", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    await armedKitchen(backend);
    backend.fcmRejectTokens = new Set(["token-one-0123456789abcdef", "token-two-0123456789abcdef"]);
    backend.fcmStatus = 404;
    backend.fcmErrorCode = "UNREGISTERED";
    const outcome = await raiseKitchenAlert(backend.env, { orderId: "o", shortId: "UND-1", title: "t", body: "b" });
    assert.equal(outcome.tokenFailed, 2);
    assert.equal(backend.docs.has("fcmTokens/kitchen-1/tokens/token-one-0123456789abcdef"), false);
    assert.equal(backend.docs.has("fcmTokens/kitchen-1/tokens/token-two-0123456789abcdef"), false);
    assert.equal(outcome.topicSent, true, "a dead token list must not silence the topic route");
  } finally {
    restore();
  }
});

test("a re-alert is labelled as one, so the phone does not file it twice", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    await armedKitchen(backend);
    await raiseKitchenAlert(backend.env, {
      orderId: "order-1",
      shortId: "UND-1042",
      title: "Still waiting on UND-1042",
      body: "Nobody has answered yet",
      extra: { repeated: "true" },
    });
    assert.equal(backend.fcmSends[0].message.data.repeated, "true");
  } finally {
    restore();
  }
});
