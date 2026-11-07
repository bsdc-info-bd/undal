import assert from "node:assert/strict";
import { test } from "node:test";
import { Window } from "happy-dom";
import type { Env } from "../src/env";
import { handleAccountPage, handleLoginPage } from "../src/web/account-page";
import { handleCheckout, handleOrderTracking, handleStoreFront } from "../src/web/store-pages";
import { PROFILE_STORAGE_KEY, SESSION_STORAGE_KEY } from "../src/web/cart-client";
import type { WebMenu, WebStoreConfig } from "../src/web/store-data";

const menu: WebMenu = {
  fetchedAt: Date.now(), store: null, sections: [], subsections: [], campaigns: [], combos: [], coupons: [],
  dishes: [{
    id: "rice", kind: "dish", name: "Undal kacchi", description: "Home cooked",
    amountPaisa: 12000, payablePaisa: 12000, discountAmountPaisa: 0,
    available: true, veg: false, spiceLevel: 1, tags: [], orderCount: 1,
    categoryId: "rice", subcategoryId: "", imageUrl: "", prepMinutes: 20, featured: true,
  }],
};
const env = { UNDAL_KV: { get: async () => menu } } as unknown as Env;

const store: WebStoreConfig = {
  storeName: "Undal HomeKitchen", open: true, autoClosed: false, manualOverrideNote: "",
  deliveryPerKmAmountPaisa: 2500, radiusKm: 8, minOrderAmountPaisa: 0,
  storeLat: 24.8951, storeLng: 91.8682, storeAddressText: "Zindabazar, Sylhet",
  bannerUrl: "", logoUrl: "",
};
const configuredMenu: WebMenu = { ...menu, store };
const configuredEnv = {
  UNDAL_KV: { get: async () => configuredMenu }, FIREBASE_WEB_API_KEY: "fake-web-api-key",
} as unknown as Env;

function executeInlineScripts(window: Window): string[] {
  const errors: string[] = [];
  const scripts = window.document.querySelectorAll("script");
  for (let index = 0; index < scripts.length; index += 1) {
    const script = scripts[index];
    const type = script.getAttribute("type");
    if (type && type !== "text/javascript" && type !== "application/javascript") continue;
    try { window.eval(script.textContent ?? ""); }
    catch (error) { errors.push(`script ${index}: ${String(error)}`); }
  }
  return errors;
}

test("rendered storefront starts every script and add-to-basket responds", async (context) => {
  const html = await (await handleStoreFront(env, new Request("https://undal.test/store"))).text();
  const window = new Window({ url: "https://undal.test/store" });
  context.after(() => window.happyDOM.close());
  window.document.write(html);
  assert.deepEqual(executeInlineScripts(window), [], "every inline startup script must run without an exception");

  const form = window.document.querySelector('form[data-undal-form="add"]') as HTMLFormElement;
  assert.ok(form, "the server-rendered add form exists");
  const submit = new window.Event("submit", { bubbles: true, cancelable: true });
  form.dispatchEvent(submit);
  assert.equal(submit.defaultPrevented, true, "the live cart intercepts the form instead of leaving a stuck tap");
  assert.deepEqual(JSON.parse(window.localStorage.getItem("undal_cart_v1") ?? "[]"), [{ id: "rice", qty: 1, kind: "dish" }]);
  assert.equal(window.document.getElementById("undal-cart-count")?.textContent, "1");

  const theme = window.document.querySelector("[data-undal-theme-button]") as HTMLButtonElement;
  Object.defineProperty(window.Element.prototype, "closest", { configurable: true, value: undefined });
  const themeText = theme.querySelector(".undal-theme-icon")?.firstChild;
  assert.ok(themeText, "tap the icon's text, not just the button's padding");
  themeText.dispatchEvent(new window.MouseEvent("click", { bubbles: true, cancelable: true }));
  assert.equal(window.document.documentElement.getAttribute("data-undal-theme"), "dark");
});

test("an automatic coupon cannot kill the cart on a WebView without Math.trunc", async (context) => {
  const promoted: WebMenu = {
    ...menu,
    coupons: [{
      code: "WELCOME10", type: "percent", value: 10, description: "Welcome offer",
      minSubtotalAmountPaisa: 0, maxDiscountPaisa: 0, expiresAtMillis: 0,
      startsAt: "", endsAt: "", daysOfWeek: [], bannerUrl: "", autoApply: true, showOnWeb: true,
    }],
  };
  const promotedEnv = { UNDAL_KV: { get: async () => promoted } } as unknown as Env;
  const html = await (await handleStoreFront(promotedEnv, new Request("https://undal.test/store"))).text();
  const window = new Window({ url: "https://undal.test/store" });
  context.after(() => window.happyDOM.close());
  window.document.write(html);
  window.localStorage.setItem("undal_cart_v1", JSON.stringify([{ id: "rice", qty: 1, kind: "dish" }]));
  // Chrome/WebView before 38 has the rest of the DOM and storage APIs used here,
  // but not Math.trunc. Deleting just that method reproduces the device boundary.
  Object.defineProperty(window.Math, "trunc", { configurable: true, value: undefined });
  assert.deepEqual(executeInlineScripts(window), []);
  const readTotals = () => (window as unknown as { undalTotals: () => { discount: number; total: number } }).undalTotals();
  assert.equal(readTotals().discount, 1200);
  assert.equal(readTotals().total, 10800);

  const form = window.document.querySelector('form[data-undal-form="add"]') as HTMLFormElement;
  const submit = new window.Event("submit", { bubbles: true, cancelable: true });
  assert.doesNotThrow(() => form.dispatchEvent(submit));
  assert.equal(submit.defaultPrevented, true);
  assert.equal(window.document.getElementById("undal-cart-count")?.textContent, "2");
  assert.equal(readTotals().discount, 2400);
  assert.equal(readTotals().total, 21600);
});

test("login tabs switch panels and keyboard selection stays live", async (context) => {
  const html = await (await handleLoginPage(configuredEnv, new Request("https://undal.test/login"))).text();
  const window = new Window({ url: "https://undal.test/login" });
  context.after(() => window.happyDOM.close());
  window.document.write(html);
  assert.deepEqual(executeInlineScripts(window), []);

  const signup = window.document.getElementById("tab-signup") as HTMLButtonElement;
  signup.dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
  assert.equal(signup.getAttribute("aria-selected"), "true");
  assert.equal(window.document.getElementById("panel-signup")?.hasAttribute("hidden"), false);
  assert.equal(window.document.getElementById("panel-signin")?.hasAttribute("hidden"), true);

  signup.dispatchEvent(new window.KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true, cancelable: true }));
  assert.equal(window.document.getElementById("tab-reset")?.getAttribute("aria-selected"), "true");
  assert.equal(window.document.getElementById("panel-reset")?.hasAttribute("hidden"), false);
});

test("login forms keep their native POST when fetch or Promise is unavailable", async (context) => {
  const html = await (await handleLoginPage(configuredEnv, new Request("https://undal.test/login"))).text();
  const window = new Window({ url: "https://undal.test/login" });
  context.after(() => window.happyDOM.close());
  window.document.write(html);
  Object.defineProperty(window, "fetch", { configurable: true, value: undefined });
  Object.defineProperty(window, "Promise", { configurable: true, value: undefined });
  assert.deepEqual(executeInlineScripts(window), []);

  for (const panel of ["signin", "signup", "reset"]) {
    const form = window.document.querySelector(`#panel-${panel} form`) as HTMLFormElement;
    const submit = new window.Event("submit", { bubbles: true, cancelable: true });
    form.dispatchEvent(submit);
    assert.equal(submit.defaultPrevented, false, `${panel} must reach the server's plain-form endpoint`);
  }
});

test("account tabs respond and invalid profile input gets an actionable answer", async (context) => {
  const html = await (await handleAccountPage(configuredEnv, new Request("https://undal.test/account"))).text();
  const window = new Window({ url: "https://undal.test/account" });
  context.after(() => window.happyDOM.close());
  window.document.write(html);
  window.sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({
    idToken: "test-id-token", refreshToken: "test-refresh-token", expiresAt: Date.now() + 3_600_000,
    uid: "customer-1", email: "customer@example.com", guest: false,
  }));
  window.fetch = (async () => new Response(JSON.stringify({
    ok: true, profile: { name: "Rahim Uddin", phone: "01712345678", address: null },
    addresses: [], email: "customer@example.com", emailVerified: true,
  }), { status: 200, headers: { "content-type": "application/json" } })) as typeof window.fetch;
  assert.deepEqual(executeInlineScripts(window), []);

  const addresses = window.document.getElementById("tab-addresses") as HTMLButtonElement;
  addresses.dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
  assert.equal(addresses.getAttribute("aria-selected"), "true");
  assert.equal(window.document.getElementById("panel-addresses")?.hasAttribute("hidden"), false);
  assert.equal(window.document.getElementById("panel-profile")?.hasAttribute("hidden"), true);

  const name = window.document.getElementById("profile-name") as HTMLInputElement;
  name.value = "!";
  window.document.getElementById("profile-save")?.dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.match(window.document.getElementById("profile-result")?.textContent ?? "", /full name/i);
});

test("sign-out clears the current tab's session and cached profile, including legacy local copies", async (context) => {
  const html = await (await handleStoreFront(configuredEnv, new Request("https://undal.test/store"))).text();
  const window = new Window({ url: "https://undal.test/store" });
  context.after(() => window.happyDOM.close());
  window.document.write(html);
  assert.deepEqual(executeInlineScripts(window), []);

  for (const key of [SESSION_STORAGE_KEY, PROFILE_STORAGE_KEY]) {
    window.sessionStorage.setItem(key, "tab-private-copy");
    window.localStorage.setItem(key, "legacy-local-copy");
  }
  (window as unknown as { undalDropAccount: () => void }).undalDropAccount();
  for (const key of [SESSION_STORAGE_KEY, PROFILE_STORAGE_KEY]) {
    assert.equal(window.sessionStorage.getItem(key), null, `${key} must leave this tab`);
    assert.equal(window.localStorage.getItem(key), null, `${key} must not survive in legacy storage`);
  }
});

test("checkout validation responds and the typed-address native post remains available", async (context) => {
  const html = await (await handleCheckout(configuredEnv, new Request("https://undal.test/checkout"))).text();
  const window = new Window({ url: "https://undal.test/checkout" });
  context.after(() => window.happyDOM.close());
  window.document.write(html);
  window.localStorage.setItem("undal_cart_v1", JSON.stringify([{ id: "rice", qty: 2, kind: "dish" }]));
  // Removing only the mount simulates a map that cannot initialise. The order
  // listener is deliberately attached before the map and must still be live.
  window.document.getElementById("undal-map")?.remove();
  assert.deepEqual(executeInlineScripts(window), []);

  const form = window.document.getElementById("undal-checkout-form") as HTMLFormElement;
  const blankSubmit = new window.Event("submit", { bubbles: true, cancelable: true });
  form.dispatchEvent(blankSubmit);
  assert.equal(blankSubmit.defaultPrevented, true);
  assert.match(window.document.getElementById("undal-order-result")?.textContent ?? "", /Write the area and the house/i);

  const values: Record<string, string> = {
    name: "Rahim Uddin", phone: "01712345678", title: "Zindabazar, Sylhet",
    desc: "House 14, Road 3, first floor", note: "Beside the mosque",
  };
  for (const [id, value] of Object.entries(values)) (window.document.getElementById(id) as HTMLInputElement).value = value;
  const validSubmit = new window.Event("submit", { bubbles: true, cancelable: true });
  form.dispatchEvent(validSubmit);
  assert.equal(validSubmit.defaultPrevented, false, "without a map the real POST is not cancelled");
  assert.deepEqual(JSON.parse((window.document.getElementById("undal-lines") as HTMLInputElement).value), [
    { id: "rice", qty: 2, kind: "dish" },
  ]);
});

test("a confirmed map pin still submits natively without fetch", async (context) => {
  const html = await (await handleCheckout(configuredEnv, new Request("https://undal.test/checkout"))).text();
  const window = new Window({ url: "https://undal.test/checkout" });
  context.after(() => window.happyDOM.close());
  window.document.write(html);
  window.localStorage.setItem("undal_cart_v1", JSON.stringify([{ id: "rice", qty: 1, kind: "dish" }]));
  Object.defineProperty(window, "fetch", { configurable: true, value: undefined });
  // The real map has its own pointer/touch suite. Replace only its constructor
  // here: Happy DOM's layout/tile simulation can exhaust memory, while this test
  // is about the checkout's confirmed-pin submission contract.
  let confirmMap = () => assert.fail("the map was not initialised");
  (window as unknown as { testMap: (options: {
    onPin: (point: { lat: number; lng: number }) => void;
    onConfirm: (point: { lat: number; lng: number }) => void;
  }) => unknown }).testMap = (options) => {
    const point = { lat: 24.8951, lng: 91.8682 };
    options.onPin(point);
    confirmMap = () => options.onConfirm(point);
    return { redraw() {}, setPin() {}, confirm: confirmMap };
  };
  for (const script of window.document.querySelectorAll("script")) {
    if (script.textContent?.includes("map = window.undalMap({")) {
      script.textContent = script.textContent.replace("map = window.undalMap({", "map = window.testMap({");
    }
  }
  assert.deepEqual(executeInlineScripts(window), []);
  confirmMap();
  const values: Record<string, string> = {
    name: "Rahim Uddin", phone: "01712345678", title: "Zindabazar, Sylhet",
    desc: "House 14, Road 3, first floor", note: "Beside the mosque",
  };
  for (const [id, value] of Object.entries(values)) (window.document.getElementById(id) as HTMLInputElement).value = value;
  const form = window.document.getElementById("undal-checkout-form") as HTMLFormElement;
  const submit = new window.Event("submit", { bubbles: true, cancelable: true });
  form.dispatchEvent(submit);
  assert.equal(submit.defaultPrevented, false);
  assert.notEqual((window.document.getElementById("undal-lat") as HTMLInputElement).value, "0");
  assert.notEqual((window.document.getElementById("undal-lng") as HTMLInputElement).value, "0");
  assert.equal(window.document.getElementById("undal-order-result")?.textContent, "");
});

test("tracking paints the fetched order instead of leaving a loading screen", async (context) => {
  const html = await (await handleOrderTracking("order123")).text();
  const window = new Window({ url: "https://undal.test/order/order123" });
  context.after(() => window.happyDOM.close());
  window.document.write(html);
  const requests: string[] = [];
  window.fetch = (async (input: RequestInfo | URL) => {
    requests.push(String(input));
    return new Response(JSON.stringify({ ok: true, order: {
      id: "order123", shortId: "U-123", status: "accepted", note: "",
      items: [{ name: "Undal kacchi", qty: 1, unitPaisa: 12000, lineTotalPaisa: 12000 }], itemsOmitted: 0,
      subtotalPaisa: 12000, deliveryFeePaisa: 0, discountPaisa: 0, totalPaisa: 12000,
      createdAt: "2026-11-06T10:00:00.000Z", updatedAt: "2026-11-06T10:02:00.000Z",
    } }), { status: 200, headers: { "content-type": "application/json" } });
  }) as typeof window.fetch;
  assert.deepEqual(executeInlineScripts(window), []);
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.deepEqual(requests, ["/api/store/order/order123"]);
  assert.equal(window.document.getElementById("undal-status-pill")?.textContent, "Cooking");
  assert.equal(window.document.getElementById("undal-order-title")?.textContent, "Order U-123");
  assert.match(window.document.getElementById("undal-receipt-rows")?.textContent ?? "", /Undal kacchi/);
  assert.equal(window.document.getElementById("undal-connection")?.textContent, "");
});

test("tracking without fetch explains how to refresh instead of throwing", async (context) => {
  const html = await (await handleOrderTracking("order123")).text();
  const window = new Window({ url: "https://undal.test/order/order123" });
  context.after(() => window.happyDOM.close());
  window.document.write(html);
  Object.defineProperty(window, "fetch", { configurable: true, value: undefined });
  assert.deepEqual(executeInlineScripts(window), []);
  assert.match(window.document.getElementById("undal-connection")?.textContent ?? "", /Reload this page/);
  assert.equal((window as unknown as { _undalPoll?: number })._undalPoll, undefined);
});
