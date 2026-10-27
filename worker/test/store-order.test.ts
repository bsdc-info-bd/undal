/**
 * The order status route and the page that reads it.
 *
 * A customer who orders from a browser has no app to open: `/order/{id}` is the
 * whole of their order, and `/api/store/order/{id}` is the only thing that keeps
 * it honest, twenty seconds at a time. Two things therefore have to hold, and
 * neither is checked anywhere else:
 *
 *   1. the route shows the order, and only the order — never the customer's
 *      name, phone number or street, because the link is the key and links get
 *      shared;
 *   2. the page's JavaScript actually runs. It is a string inside a template
 *      literal, so TypeScript never reads it, the linter never sees it and a
 *      typo in it would reach production looking like a page that never updates.
 */
import assert from "node:assert/strict";
import { test } from "node:test";
import { FakeFirebase } from "./helpers/fake-firebase";
import { runInlineScript, settle } from "./helpers/mini-dom";
import { handleStoreOrderStatus } from "../src/routes/store";
import { handleOrderTracking } from "../src/web/store-pages";
import type { Env } from "../src/env";

const ORDER_ID = "abc123def456";

/** An order as `createWebOrder` writes it, plus the private fields a receipt must not carry. */
function webOrder(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    shortId: "UND-1001",
    customerId: "customer-1",
    customerName: "Nadia Rahman",
    phone: "01711111111",
    channel: "web",
    items: [
      { id: "rice", name: "Kacchi biryani", qty: 2, unitAmountPaisa: 32000, lineTotalAmountPaisa: 64000, kind: "dish" },
      { id: "borhani", name: "Borhani", qty: 1, unitAmountPaisa: 6000, lineTotalAmountPaisa: 6000, kind: "dish" },
    ],
    subtotalAmountPaisa: 70000,
    deliveryAmountPaisa: 5000,
    discountAmountPaisa: 7000,
    couponCode: "WELCOME50",
    totalAmountPaisa: 68000,
    payment: "COD",
    address: { lat: 24.9, lng: 91.87, title: "Home", desc: "Road 3, Sylhet", note: "", geohashLabel: "wh0" },
    distanceKm: 2.5,
    status: "placed",
    timeline: [],
    chatId: "customer-1",
    createdAt: "2026-10-15T12:00:00.000Z",
    updatedAt: "2026-10-15T12:00:05.000Z",
    ...overrides,
  };
}

test("the status route hands the page a bill and nothing private", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    backend.setDocument(`orders/${ORDER_ID}`, webOrder());
    const response = await handleStoreOrderStatus(backend.env, ORDER_ID);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("cache-control"), "no-store");
    const payload = (await response.json()) as { ok: boolean; order: Record<string, unknown> };

    // The rows of the bill, in the order they were ordered.
    assert.deepEqual(payload.order.items, [
      { name: "Kacchi biryani", qty: 2, unitPaisa: 32000, lineTotalPaisa: 64000 },
      { name: "Borhani", qty: 1, unitPaisa: 6000, lineTotalPaisa: 6000 },
    ]);
    assert.equal(payload.order.subtotalPaisa, 70000);
    assert.equal(payload.order.deliveryPaisa, 5000);
    assert.equal(payload.order.discountPaisa, 7000);
    assert.equal(payload.order.couponCode, "WELCOME50");
    assert.equal(payload.order.totalPaisa, 68000);
    assert.equal(payload.order.distanceKm, 2.5);

    // The link is the key, so the response may not carry who is behind it.
    const body = JSON.stringify(payload);
    for (const secret of ["Nadia", "01711111111", "Road 3", "customer-1"]) {
      assert.ok(!body.includes(secret), `the status route leaked ${secret}`);
    }
  } finally {
    restore();
  }
});

test("a cancellation that reached the live mirror first is shown at once", async () => {
  // The kitchen's phone writes the mirror the instant it taps cancel; the order
  // document is committed a moment later. A tracking page polled in that gap used
  // to keep saying "Cooking", which is the one wrong answer a customer will act on.
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    backend.setDocument(`orders/${ORDER_ID}`, webOrder({ status: "accepted" }));
    backend.setRtdb(`orderLive/${ORDER_ID}`, {
      status: "cancelled",
      vendorNote: "We ran out of fish for this order.",
      updatedAt: Date.now(),
    });
    const payload = (await (await handleStoreOrderStatus(backend.env, ORDER_ID)).json()) as {
      order: { status: string; cancelNote: string };
    };
    assert.equal(payload.order.status, "cancelled");
    assert.equal(payload.order.cancelNote, "We ran out of fish for this order.");
  } finally {
    restore();
  }
});

test("a stale mirror never drags a cancelled order back to life", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    backend.setDocument(`orders/${ORDER_ID}`, webOrder({ status: "cancelled", cancelNote: "Kitchen closed." }));
    backend.setRtdb(`orderLive/${ORDER_ID}`, { status: "accepted", updatedAt: Date.now() });
    const payload = (await (await handleStoreOrderStatus(backend.env, ORDER_ID)).json()) as {
      order: { status: string; cancelNote: string };
    };
    assert.equal(payload.order.status, "cancelled");
    assert.equal(payload.order.cancelNote, "Kitchen closed.");
  } finally {
    restore();
  }
});

test("a hostile order document produces a small bill, not a broken page", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    backend.setDocument(
      `orders/${ORDER_ID}`,
      webOrder({
        items: [
          { name: "Rice", qty: -4, unitAmountPaisa: "free", lineTotalAmountPaisa: null },
          { qty: 3, unitAmountPaisa: 1000, lineTotalAmountPaisa: 3000 },
          { name: "   ", qty: 1, unitAmountPaisa: 100, lineTotalAmountPaisa: 100 },
          "not an object",
        ],
        subtotalAmountPaisa: undefined,
        deliveryAmountPaisa: undefined,
        discountAmountPaisa: undefined,
        distanceKm: undefined,
      }),
    );
    const payload = (await (await handleStoreOrderStatus(backend.env, ORDER_ID)).json()) as {
      order: { items: unknown[]; subtotalPaisa: number; deliveryPaisa: number; discountPaisa: number };
    };
    // One line survives: the one with a name. Everything else is repaired to a
    // number rather than passed on as a string the page would print as "NaN".
    assert.equal(payload.order.items.length, 1);
    assert.deepEqual(payload.order.items[0], { name: "Rice", qty: 1, unitPaisa: 0, lineTotalPaisa: 0 });
    assert.equal(payload.order.subtotalPaisa, 0);
    assert.equal(payload.order.deliveryPaisa, 0);
    assert.equal(payload.order.discountPaisa, 0);
  } finally {
    restore();
  }
});

test("a basket longer than the receipt stops saying so, it does not go quiet", async () => {
  const lines = (count: number) =>
    Array.from({ length: count }, (_, index) => ({
      name: `Dish ${index + 1}`,
      qty: 1,
      unitAmountPaisa: 10000,
      lineTotalAmountPaisa: 10000,
    }));

  // Forty rows fit. The bill is complete, and nothing claims otherwise.
  const fits = new FakeFirebase();
  const restoreFits = fits.install();
  try {
    fits.setDocument(`orders/${ORDER_ID}`, webOrder({ items: lines(40) }));
    const payload = (await (await handleStoreOrderStatus(fits.env, ORDER_ID)).json()) as {
      order: { items: unknown[]; itemsOmitted: number };
    };
    assert.equal(payload.order.items.length, 40);
    assert.equal(payload.order.itemsOmitted, 0);
  } finally {
    restoreFits();
  }

  // Forty-one do not — one row is past the page's limit, and the receipt says
  // how many are behind it instead of leaving the customer to add up rows that
  // do not match the total.
  const over = new FakeFirebase();
  const restoreOver = over.install();
  try {
    over.setDocument(`orders/${ORDER_ID}`, webOrder({ items: lines(41) }));
    const payload = (await (await handleStoreOrderStatus(over.env, ORDER_ID)).json()) as {
      order: { items: unknown[]; itemsOmitted: number };
    };
    assert.equal(payload.order.items.length, 40);
    assert.equal(payload.order.itemsOmitted, 1);
  } finally {
    restoreOver();
  }
});

test("the receipt prints how many rows the page left out", async () => {
  const html = await (await handleOrderTracking(ORDER_ID)).text();
  const order = {
    id: ORDER_ID,
    shortId: "UND-1001",
    status: "accepted",
    note: "",
    cancelNote: "",
    prepEndsAt: "",
    items: [{ name: "Kacchi biryani", qty: 1, unitPaisa: 32000, lineTotalPaisa: 32000 }],
    itemsOmitted: 3,
    subtotalPaisa: 64000,
    deliveryPaisa: 4000,
    discountPaisa: 0,
    couponCode: "",
    distanceKm: 2,
    createdAt: "2026-10-15T12:00:00.000Z",
    totalPaisa: 68000,
    updatedAt: "2026-10-15T12:05:00.000Z",
  };
  const page = runInlineScript(html, { fetch: async () => ({ json: { ok: true, order } }) });
  await settle();

  const rows = page.ids("undal-receipt-rows").innerHTML;
  assert.match(rows, /3 more dishes on this order are not listed here/);
  // The total is still the whole basket, which is the point of saying so.
  assert.equal(page.ids("undal-order-total").textContent, "৳ 680");
});

test("the tracking page prints the bill it is handed, escaping what comes from the kitchen", async () => {
  const html = await (await handleOrderTracking(ORDER_ID)).text();
  const order = {
    id: ORDER_ID,
    shortId: "UND-1001",
    status: "accepted",
    note: "",
    cancelNote: "",
    prepEndsAt: "",
    items: [
      { name: 'Fish <img src=x onerror="alert(1)">', qty: 2, unitPaisa: 12500, lineTotalPaisa: 25000 },
      { name: "Rice", qty: 1, unitPaisa: 9000, lineTotalPaisa: 9000 },
    ],
    subtotalPaisa: 34000,
    deliveryPaisa: 0,
    discountPaisa: 4000,
    couponCode: "EID40",
    distanceKm: 3,
    createdAt: "2026-10-15T12:00:00.000Z",
    totalPaisa: 30000,
    updatedAt: "2026-10-15T12:05:00.000Z",
  };
  const page = runInlineScript(html, { fetch: async () => ({ json: { ok: true, order } }) });
  await settle();

  // The dish is printed, and its markup is not.
  const rows = page.ids("undal-receipt-rows").innerHTML;
  assert.match(rows, /2 × Fish &lt;img src=x onerror="alert\(1\)"&gt;/);
  assert.ok(!rows.includes("<img src=x"), "an item name reached the page as markup");
  assert.match(rows, /2 × Fish/);
  assert.match(rows, /Delivery · 3 km/);
  assert.match(rows, /Free/);
  assert.match(rows, /Coupon EID40/);
  assert.match(rows, /− ৳ 40/);
  assert.match(rows, /undal-receipt-rows|receipt-row/);

  // The total is the amount the kitchen will collect at the door.
  assert.equal(page.ids("undal-order-total").textContent, "৳ 300");
  assert.equal(page.ids("undal-status-pill").textContent, "Cooking");
  assert.equal(page.ids("undal-order-title").textContent, "Order UND-1001");
  // 12:05 UTC is 6:05 in the evening in Sylhet, whatever clock the reader's
  // phone keeps: the arithmetic is done in UTC against a fixed +6, so this
  // string is the same on a runner in UTC and on a phone in Dubai.
  assert.equal(page.ids("undal-updated").textContent, "Updated 6:05 pm, Dhaka time");

  // Accepted, so "we're on it" has stopped being the news: the tick steps aside
  // and the bill stays.
  assert.ok(page.ids("undal-receipt-hero").hasAttribute("hidden"));
  assert.equal(page.window.intervals.length, 1);
  // Eight seconds: the gap between the kitchen's tap and the customer's screen
  // is the one thing a tracking page cannot be slow about.
  assert.equal(page.window.intervals[0]?.ms, 8000);
});

test("a placed order leads with the tick, and delivery stops the poll", async () => {
  const html = await (await handleOrderTracking(ORDER_ID)).text();
  const order = {
    id: ORDER_ID,
    shortId: "UND-1002",
    status: "placed",
    note: "",
    cancelNote: "",
    prepEndsAt: "",
    items: [{ name: "Kacchi biryani", qty: 1, unitPaisa: 32000, lineTotalPaisa: 32000 }],
    subtotalPaisa: 32000,
    deliveryPaisa: 1000,
    discountPaisa: 0,
    couponCode: "",
    distanceKm: 1,
    createdAt: "2026-10-15T12:00:00.000Z",
    totalPaisa: 33000,
    updatedAt: "2026-10-15T12:00:05.000Z",
  };
  const page = runInlineScript(html, { fetch: async () => ({ json: { ok: true, order } }) });
  await settle();
  assert.ok(!page.ids("undal-receipt-hero").hasAttribute("hidden"));

  // Placement confirms the order was sent, not that a phone audibly rang.
  assert.match(page.ids("undal-receipt-hero").textContent, /Your order was sent to the kitchen/i);
  assert.equal(page.ids("undal-receipt-sent").textContent, "Sent at 6:00 pm, Dhaka time");
  assert.equal(page.ids("undal-status-pill").textContent, "Placed");
  assert.deepEqual(page.window.cleared, []);
});

test("a cancelled order shows the reason and stops polling", async () => {
  const html = await (await handleOrderTracking(ORDER_ID)).text();
  const order = {
    id: ORDER_ID,
    shortId: "UND-1003",
    status: "cancelled",
    note: "",
    cancelNote: "The kitchen ran out of mutton <today>",
    prepEndsAt: "",
    items: [],
    subtotalPaisa: 0,
    deliveryPaisa: 0,
    discountPaisa: 0,
    couponCode: "",
    distanceKm: 0,
    createdAt: "2026-10-15T12:00:00.000Z",
    totalPaisa: 0,
    updatedAt: "2026-10-15T12:20:00.000Z",
  };
  const page = runInlineScript(html, { fetch: async () => ({ json: { ok: true, order } }) });
  await settle();
  assert.match(page.ids("undal-note").innerHTML, /Cancelled:/);
  assert.match(page.ids("undal-note").innerHTML, /ran out of mutton &lt;today&gt;/);
  assert.ok(!page.ids("undal-note").innerHTML.includes("<today>"));
  assert.equal(page.ids("undal-status-pill").textContent, "Cancelled");
  // A finished order has nothing left to poll for, and this is the phone paying
  // for the data plan.
  assert.deepEqual(page.window.cleared, [1]);
  // No lines to print, so the bill says so instead of showing an empty well.
  assert.match(page.ids("undal-receipt-rows").innerHTML, /on the kitchen\u2019s copy of the bill/);
});

test("an order the customer shared nowhere still cannot be found", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    await assert.rejects(() => handleStoreOrderStatus(backend.env as Env, "missing0order"));
  } finally {
    restore();
  }
});

test("a link that resolves to nothing says so instead of loading forever", async () => {
  const html = await (await handleOrderTracking(ORDER_ID)).text();
  const page = runInlineScript(html, { fetch: async () => ({ status: 404, json: { ok: false, error: "order-not-found" } }) });
  await settle();
  assert.match(page.ids("undal-receipt-rows").innerHTML, /could not find an order/);
  assert.equal(page.ids("undal-status-pill").textContent, "Not found");
  assert.equal(page.ids("undal-order-total").textContent, "—");
  assert.ok(page.ids("undal-receipt-hero").hasAttribute("hidden"));
  // An id the server has refused will not become good on the next tick.
  assert.deepEqual(page.window.cleared, [1]);
});

test("a dropped signal is retried on the next tick, not given up on", async () => {
  const html = await (await handleOrderTracking(ORDER_ID)).text();
  const order = {
    id: ORDER_ID, shortId: "UND-1004", status: "placed", note: "", cancelNote: "", prepEndsAt: "",
    items: [{ name: "Kacchi biryani", qty: 1, unitPaisa: 32000, lineTotalPaisa: 32000 }],
    subtotalPaisa: 32000, deliveryPaisa: 0, discountPaisa: 0, couponCode: "", distanceKm: 1,
    createdAt: "2026-10-15T12:00:00.000Z", totalPaisa: 32000, updatedAt: "2026-10-15T12:00:30.000Z",
  };
  let attempts = 0;
  const page = runInlineScript(html, {
    fetch: async () => {
      attempts += 1;
      if (attempts === 1) throw new Error("the phone went into a tunnel");
      return { json: { ok: true, order } };
    },
  });
  await settle();
  assert.equal(attempts, 1);
  // Nothing was thrown away: the customer waits, the poll is still armed.
  assert.equal(page.ids("undal-receipt-rows").textContent, "Reading your bill…");
  assert.deepEqual(page.window.cleared, []);
  assert.equal(page.window.intervals.length, 1);

  // Twenty seconds later the interval fires the same function again, and this
  // time the signal is back.
  await page.window.intervals[0]!.handler();
  await settle();
  assert.equal(attempts, 2);
  assert.ok(!page.ids("undal-receipt-hero").hasAttribute("hidden"));
  assert.match(page.ids("undal-receipt-rows").innerHTML, /Kacchi biryani/);
  assert.equal(page.ids("undal-order-total").textContent, "৳ 320");
});
