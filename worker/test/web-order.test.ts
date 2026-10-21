import assert from "node:assert/strict";
import { test } from "node:test";
import { FakeFirebase } from "./helpers/fake-firebase";
import { createWebOrder, type WebOrderInput } from "../src/routes/store";
import { runPresenceSweep } from "../src/crons/presence";
import { dhakaClock } from "../src/util/store-hours";
import { HttpError } from "../src/util/http";

/**
 * The web checkout, end to end against a fake Firebase: the two reported
 * failures that live on the server.
 *
 *   1. "The store is open 24 hours." The order route never asked whether the
 *      kitchen was taking orders — a closed page, an open checkout.
 *   2. "Every active coupon says expired." The code path a typed coupon takes is
 *      pinned here with the document exactly as the Android editor writes it.
 *
 * And the presence cron, which is what makes a store left switched on close at
 * closing time — and open at opening time — on phones running older builds.
 */

const ALL_DAY = [{ open: "00:00", close: "23:59" }];
const EVERY_DAY = { sun: ALL_DAY, mon: ALL_DAY, tue: ALL_DAY, wed: ALL_DAY, thu: ALL_DAY, fri: ALL_DAY, sat: ALL_DAY };
const today = (): string => dhakaClock().dateKey;
const DAY_OFF = (): Array<Record<string, unknown>> => [{ date: today(), closed: true, note: "Eid", slots: [] }];

function kitchen(store: Record<string, unknown> = {}): FakeFirebase {
  return new FakeFirebase()
    .setDocument("config/store", {
      storeName: "Undal HomeKitchen",
      open: true,
      acceptingOrders: true,
      openSynced: true,
      autoClosed: false,
      weekly: EVERY_DAY,
      specialDates: [],
      deliveryPerKmAmountPaisa: 2000,
      radiusKm: 5,
      minOrderAmountPaisa: 100,
      storeLat: 24.8949,
      storeLng: 91.8687,
      ...store,
    })
    .setDocument("menuItems/d1", { name: "Kacchi", amountPaisa: 50_000, available: true })
    .setDocument("counters/orders", { value: 1000 });
}

/** A coupon document exactly as `CouponDraft.writeFields()` writes it. */
function appCoupon(over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    type: "percent",
    value: 10,
    active: true,
    minSubtotalAmountPaisa: 0,
    maxDiscountPaisa: 0,
    startsAt: "",
    endsAt: "",
    daysOfWeek: [],
    autoApply: false,
    showOnWeb: true,
    usageLimit: 0,
    usedCount: 0,
    expiresAt: null,
    description: "",
    ...over,
  };
}

const order = (couponCode = ""): WebOrderInput => ({
  uid: "guest-1",
  customerName: "Rahim Uddin",
  phone: "01712345678",
  lat: 24.896,
  lng: 91.87,
  addressTitle: "Zindabazar",
  addressDesc: "House 1, Road 2",
  addressNote: "Near the mosque",
  lines: [{ id: "d1", qty: 1 }],
  couponCode,
});

async function place(backend: FakeFirebase, input: WebOrderInput) {
  const restore = backend.install();
  try {
    return await createWebOrder(backend.env, input);
  } finally {
    restore();
  }
}

async function refusal(backend: FakeFirebase, input: WebOrderInput): Promise<HttpError> {
  try {
    await place(backend, input);
  } catch (error) {
    assert.ok(error instanceof HttpError, `expected an HttpError, got ${String(error)}`);
    return error;
  }
  return assert.fail("the order went through");
}

// ------------------------------------------------------------ the schedule

test("an open kitchen takes the order", async () => {
  const result = await place(kitchen(), order());
  assert.equal(result.order.status, "placed");
  assert.equal(result.subtotalPaisa, 50_000);
});

test("a kitchen outside its hours refuses the order, and says when it opens", async () => {
  const error = await refusal(kitchen({ specialDates: DAY_OFF() }), order());
  assert.equal(error.status, 409);
  assert.equal(error.code, "store-closed");
  assert.match(error.message, /closed right now — it opens tomorrow at 12:00 AM/);
});

test("a paused kitchen refuses the order, with the note the owner wrote", async () => {
  const error = await refusal(
    kitchen({ acceptingOrders: false, open: false, openSynced: false, manualOverrideNote: "Back at 4 pm." }),
    order(),
  );
  assert.equal(error.code, "store-closed");
  assert.match(error.message, /Back at 4 pm\./);
});

test("a kitchen the watchdog paused refuses the order", async () => {
  const error = await refusal(kitchen({ autoClosed: true, open: false, openSynced: false }), order());
  assert.equal(error.code, "store-closed");
  assert.match(error.message, /phone is offline/);
});

test("a store document written before the switch existed still keeps the schedule", async () => {
  // Only `open: true` — what every document looked like before this change —
  // and a day off. The switch is on; the hours say no.
  const legacy = kitchen({ acceptingOrders: undefined, openSynced: undefined, specialDates: DAY_OFF() });
  assert.equal((await refusal(legacy, order())).code, "store-closed");
});

// --------------------------------------------------------------- coupons

test("a typed code with a future expiry, as the app writes it, is honoured", async () => {
  const backend = kitchen().setDocument("coupons/SAVE10", appCoupon({ expiresAt: new Date(Date.now() + 5 * 86_400_000) }));
  const result = await place(backend, order("save10"));
  assert.equal(result.couponCode, "SAVE10");
  assert.equal(result.discountPaisa, 5_000);
});

test("a typed code with no expiry and no usage limit is honoured", async () => {
  const backend = kitchen().setDocument("coupons/SAVE10", appCoupon());
  const result = await place(backend, order("SAVE10"));
  assert.equal(result.discountPaisa, 5_000);
});

test("a code whose expiry was written as epoch seconds is not mistaken for 1970", async () => {
  const inFiveDays = Math.floor(Date.now() / 1000) + 5 * 86_400;
  const backend = kitchen().setDocument("coupons/SAVE10", appCoupon({ expiresAt: inFiveDays }));
  assert.equal((await place(backend, order("SAVE10"))).discountPaisa, 5_000);
});

test("an expired code is refused with the date and the minute it ended", async () => {
  const backend = kitchen().setDocument("coupons/OLD10", appCoupon({ expiresAt: new Date("2026-01-10T17:59:59Z") }));
  const error = await refusal(backend, order("OLD10"));
  assert.equal(error.code, "coupon-expired");
  assert.equal(error.message, "OLD10 expired on 10 Jan 2026, 11:59 PM.");
});

test("a code that cannot exist is refused out loud, not dropped from the bill", async () => {
  const error = await refusal(kitchen(), order("EID 20"));
  assert.equal(error.code, "coupon-invalid");
  assert.match(error.message, /is not a coupon code/);
});

// -------------------------------------------------- the presence cron

const MINUTE = 60_000;

async function sweep(backend: FakeFirebase) {
  const restore = backend.install();
  try {
    return await runPresenceSweep(backend.env);
  } finally {
    restore();
  }
}

const storeDoc = (backend: FakeFirebase): Record<string, unknown> => backend.docs.get("config/store") ?? {};
// The fake keeps the Realtime Database as leaves: `presence/vendor/lastSeen`.

test("a switched-on store closes itself when its hours end, even for builds that read only `open`", async () => {
  const backend = kitchen({ specialDates: DAY_OFF() }).setRtdb("presence/vendor/lastSeen", Date.now() - MINUTE);
  const result = await sweep(backend);
  assert.equal(result.action, "none");
  assert.equal(result.schedule, "closed");
  assert.equal(storeDoc(backend).open, false);
  assert.equal(storeDoc(backend).acceptingOrders, true, "the switch stays on: the store reopens by itself");
});

test("and opens itself when its hours begin", async () => {
  const backend = kitchen({ open: false, openSynced: false }).setRtdb("presence/vendor/lastSeen", Date.now() - MINUTE);
  const result = await sweep(backend);
  assert.equal(result.schedule, "opened");
  assert.equal(storeDoc(backend).open, true);
  assert.equal(storeDoc(backend).openSynced, true);
});

test("a phone left off overnight does not pause a store that is closed by its hours", async () => {
  const backend = kitchen({ specialDates: DAY_OFF() }).setRtdb("presence/vendor/lastSeen", Date.now() - 8 * 60 * MINUTE);
  const result = await sweep(backend);
  assert.notEqual(result.action, "auto-offline");
  assert.notEqual(storeDoc(backend).autoClosed, true);
});

test("a silent phone during service still pauses the store", async () => {
  const backend = kitchen().setRtdb("presence/vendor/lastSeen", Date.now() - 15 * MINUTE);
  const result = await sweep(backend);
  assert.equal(result.action, "auto-offline");
  assert.equal(storeDoc(backend).autoClosed, true);
  assert.equal(storeDoc(backend).open, false);
  assert.equal(storeDoc(backend).acceptingOrders, true);
});

test("an older vendor build's Close is the owner's word, and the cron never reopens over it", async () => {
  // The schedule-aware fields say "on", but `open` was since set to false by a
  // build that does not know them.
  const backend = kitchen({ open: false, openSynced: true }).setRtdb("presence/vendor/lastSeen", Date.now() - MINUTE);
  await sweep(backend);
  assert.equal(storeDoc(backend).acceptingOrders, false);
  assert.equal(storeDoc(backend).open, false);
  await sweep(backend);
  assert.equal(storeDoc(backend).open, false, "a second sweep must not reopen it either");
});
