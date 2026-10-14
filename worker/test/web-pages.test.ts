import assert from "node:assert/strict";
import { test } from "node:test";
import { renderPage } from "../src/web/layout";
import {
  handleAboutPage,
  handleCheckout,
  handleOrderTracking,
  handleStoreFront,
  handleStoreItem,
} from "../src/web/store-pages";
import { handleAccountPage, handleLoginPage } from "../src/web/account-page";
import type { Env } from "../src/env";
import type { WebMenu, WebStoreConfig } from "../src/web/store-data";

const menu: WebMenu = {
  fetchedAt: Date.now(), store: null, sections: [], subsections: [], campaigns: [], combos: [],
  dishes: [{ id: "rice", kind: "dish", name: "Rice <script>alert(1)</script>",
    description: "Home cooked", amountPaisa: 12000, payablePaisa: 12000,
    discountAmountPaisa: 0, available: true, veg: true, spiceLevel: 0,
    tags: [], orderCount: 1, categoryId: "rice", subcategoryId: "", imageUrl: "",
    prepMinutes: 20, featured: true }],
};
const env = { UNDAL_KV: { get: async () => menu } } as unknown as Env;

test("shared shell escapes metadata, provides accessible navigation and a light responsive theme", async () => {
  const response = renderPage({ title: '<script>"bad"</script>', description: "Food & care", body: "<p>Hello</p>", cacheSeconds: 0 });
  const html = await response.text();
  assert.match(html, /<title>&lt;script&gt;/);
  assert.match(html, /href="#main-content"/);
  assert.match(html, /id="main-content"/);
  assert.match(html, /prefers-reduced-motion/);
  assert.match(html, /grid-template-columns:repeat\(auto-fill,minmax\(min\(100%,250px\),1fr\)\)/);
  assert.match(html, /\.button,a\.button/);
  assert.equal(response.headers.get("cache-control"), "no-store");
});

test("storefront renders cached catalogue safely without external services", async () => {
  const response = await handleStoreFront(env, new Request("https://undal.test/store"));
  const html = await response.text();
  assert.equal(response.status, 200);
  assert.match(html, /A little taste/);
  assert.match(html, /aria-current="page"/);
  assert.match(html, /Rice &lt;script&gt;/);
  assert.doesNotMatch(html, /Rice <script>/);
  assert.match(html, /food-placeholder/);
  assert.match(html, /href="\/item\/rice"/);
});

test("search retains the query and offers an empty-state recovery link", async () => {
  const html = await (await handleStoreFront(env, new Request("https://undal.test/store?q=not-a-dish"))).text();
  assert.match(html, /value="not-a-dish"/);
  assert.match(html, /Nothing matches that search/);
  assert.match(html, /Show the whole menu/);
});

test("tracking uses the actual taka symbol rather than a literal unicode escape", async () => {
  const html = await (await handleOrderTracking("abc123")).text();
  assert.match(html, /replace\(\/\^\/, "৳ "\)/);
});

// ------------------------------------------------------- a kitchen with a pin

const storeConfig: WebStoreConfig = {
  storeName: "Undal HomeKitchen",
  open: true,
  autoClosed: false,
  manualOverrideNote: "",
  weekly: { friday: [{ open: "12:00", close: "15:00" }, { open: "18:00", close: "22:30" }] },
  specialDates: [],
  deliveryPerKmAmountPaisa: 2500,
  radiusKm: 8,
  minOrderAmountPaisa: 15000,
  storeLat: 24.8951,
  storeLng: 91.8682,
  storeAddressText: "Road 4, Zindabazar, Sylhet",
  bannerUrl: "",
  logoUrl: "",
};

const fullMenu: WebMenu = {
  ...menu,
  fetchedAt: Date.now(),
  store: storeConfig,
  sections: [{ id: "rice", name: "Rice plates", order: 1, active: true, iconUrl: "" }],
  combos: [{
    id: "family-combo", kind: "combo", name: "Family combo", description: "Feeds four",
    amountPaisa: 120000, payablePaisa: 120000, discountAmountPaisa: 0, regularAmountPaisa: 140000,
    available: true, imageUrl: "", itemRefs: [{ menuItemId: "rice", qty: 2 }], featured: false,
  }],
};
fullMenu.dishes[0]!.categoryId = "rice";

const withKey = (catalogue: WebMenu): Env =>
  ({ UNDAL_KV: { get: async () => catalogue }, FIREBASE_WEB_API_KEY: "fake-web-api-key" }) as unknown as Env;
const withoutKey = (catalogue: WebMenu): Env => ({ UNDAL_KV: { get: async () => catalogue } }) as unknown as Env;
const brokenEnv = {
  UNDAL_KV: { get: async () => { throw new Error("kv unavailable"); } },
  FIREBASE_SA_JSON: "{ not json",
  FIREBASE_WEB_API_KEY: "fake-web-api-key",
} as unknown as Env;

const checkoutRequest = (): Request => new Request("https://undal.test/checkout");

// ------------------------------------------------------------------ checkout

test("the checkout asks for a pin, prices it live and will not order without a tick", async () => {
  const response = await handleCheckout(withKey(fullMenu), checkoutRequest());
  const html = await response.text();
  assert.equal(response.status, 200);

  assert.match(html, /<div id="undal-map"><\/div>/, "the map has somewhere to mount");
  assert.match(html, /id="undal-radius"/, "and somewhere to say you are outside the radius");
  assert.match(html, /id="undal-delivery"/, "the fee has a live home");
  assert.match(html, /id="undal-place"/, "one button places the order");

  assert.match(html, /window\.undalMap\(\{/, "the page builds a map, it does not reimplement one");
  assert.equal(html.split("function haversine").length - 1, 1, "the distance rule exists exactly once, in the map script");
  assert.match(html, /onConfirm:/, "a pin only counts once it is ticked");
  assert.match(html, /var ticked = false;/);
  assert.match(html, /window\.UNDAL_DISTANCE_KM = km;/, "the distance the customer saw is the distance sent");
  assert.match(html, /if \(!ticked\)/, "the button refuses an unticked pin");
  assert.match(html, /window\.undalUsablePhone\(/, "the phone rule is the shared one, not a local copy");
  assert.match(html, /tile\.openstreetmap\.org/, "the tiles come from the origin the CSP already allows");
});

test("the checkout pre-fills a signed-in customer and pre-ticks their saved pin", async () => {
  const html = await (await handleCheckout(withKey(fullMenu), checkoutRequest())).text();
  assert.match(html, /window\.undalProfile\(\)/, "the saved profile is read before anything is typed");
  assert.match(html, /map\.setPin\(/, "a saved address arrives with its pin already on the map");
  assert.match(html, /map\.confirm\(\)/, "and already ticked, so reordering is one tap");
});

test("the checkout parks honestly when the web sign-in key is missing", async () => {
  const html = await (await handleCheckout(withoutKey(fullMenu), checkoutRequest())).text();
  assert.match(html, /Ordering on the web is switched off right now/);
  assert.match(html, /Firebase web sign-in key/);
  assert.match(html, /Install the Undal app/);
  assert.doesNotMatch(html, /id="undal-place"/, "no button that cannot work");
});

test("the checkout refuses to price an order when the kitchen has published no store", async () => {
  const html = await (await handleCheckout(withKey({ ...fullMenu, store: null }), checkoutRequest())).text();
  assert.match(html, /The kitchen is not ready to take orders/);
  assert.doesNotMatch(html, /window\.undalMap\(/);
});

test("every page tells a crawler its one true address", async () => {
  const pages: Array<[string, Response]> = [
    ["menu", await handleStoreFront(withKey(fullMenu), new Request("https://undal.test/store"))],
    ["about", await handleAboutPage(withKey(fullMenu), new Request("https://undal.test/about"))],
    ["item", await handleStoreItem(withKey(fullMenu), new Request("https://undal.test/item/rice"), "rice")],
    ["checkout", await handleCheckout(withKey(fullMenu), checkoutRequest())],
  ];
  for (const [name, response] of pages) {
    const html = await response.clone().text();
    assert.match(html, /<link rel="canonical" href="https:\/\/undal\.test\//, `${name} declares a canonical`);
    // The menu's share card deliberately points at the site root rather than at
    // /store, so the origin is asserted, not the full path.
    assert.match(html, /<meta property="og:url" content="https:\/\/undal\.test/, `${name} declares an og:url`);
    assert.match(html, /<html lang="en-GB">/, `${name} says which English it is written in`);
  }
});

// --------------------------------------------------------------- item page

test("a dish page carries its price and availability as structured data", async () => {
  const html = await (await handleStoreItem(withKey(fullMenu), new Request("https://undal.test/item/rice"), "rice")).text();
  assert.match(html, /application\/ld\+json/);
  assert.match(html, /"@type":\["Product","MenuItem"\]/);
  assert.match(html, /"price":"120\.00"/, "the price a crawler reads is what the customer pays");
  assert.match(html, /"priceCurrency":"BDT"/);
  assert.match(html, /https:\/\/undal\.test\/item\/rice/);
});

test("a dish that is gone says so instead of rendering an empty card", async () => {
  const html = await (await handleStoreItem(withKey(fullMenu), new Request("https://undal.test/item/nope"), "nope")).text();
  assert.match(html, /That dish is gone from the menu/);
  assert.match(html, /See the whole menu/);
});

// --------------------------------------------------------------------- about

test("the about page renders the same FAQ the structured data carries", async () => {
  const response = await handleAboutPage(withKey(fullMenu), new Request("https://undal.test/about"));
  const html = await response.text();
  assert.equal(response.status, 200);
  assert.match(html, /aria-current="page"[^>]*href="\/about"|href="\/about"[^>]*aria-current="page"/, "the nav knows where it is");
  assert.match(html, /id="faq"/);
  assert.match(html, /<details class="faq-item">/);
  assert.match(html, /How do I pay for an Undal order\?/);
  assert.match(html, /Cash on delivery/, "the answer is the rule the code enforces");
  assert.match(html, /"@id":"https:\/\/undal\.test\/about#faq"/, "and the crawler is pointed at this very list");
  assert.match(html, /"@type":"BreadcrumbList"/);
  assert.match(html, /"@type":"Restaurant"/);
  assert.match(html, /Today \(/, "the live schedule is on the page, not only in the data");
  assert.match(html, /12:00/, "a two-service Friday shows both services");
});

test("the about page survives a catalogue it cannot read", async () => {
  const response = await handleAboutPage(brokenEnv, new Request("https://undal.test/about"));
  assert.equal(response.status, 503);
  assert.match(await response.text(), /We couldn’t reach the kitchen/);
});

// --------------------------------------------------------- login and account

test("the sign-in page offers sign-up and a reset, and never a plaintext password", async () => {
  const response = await handleLoginPage(withKey(fullMenu), new Request("https://undal.test/login"));
  const html = await response.text();
  assert.equal(response.status, 200);
  assert.match(html, /role="tablist"/);
  for (const tab of ["Sign in", "Create an account", "Forgotten password"]) {
    assert.ok(html.includes(`>${tab}</button>`), `${tab} has its own tab`);
  }
  assert.equal(
    html.split('type="password"').length - 1,
    3,
    "one to sign in with, and a new one typed twice so a thumb cannot set a password nobody meant",
  );
  assert.equal(html.split('autocomplete="current-password"').length - 1, 1);
  assert.equal(html.split('autocomplete="new-password"').length - 1, 2);
  assert.match(html, /id="signup-confirm"/, "the confirmation has its own field");
  assert.match(html, /window\.undalSaveAccount\(/, "the session layer is the shared one from the cart script");
  assert.match(html, /novalidate|aria-live/, "a refusal is announced, not swallowed");
  assert.doesNotMatch(html, /fake-web-api-key/, "the key stays in the Worker");
});

test("the account page holds the profile, the address book, the history and the password", async () => {
  const response = await handleAccountPage(withKey(fullMenu), new Request("https://undal.test/account"));
  const html = await response.text();
  assert.equal(response.status, 200);
  for (const tab of ["Profile", "Addresses", "Orders", "Security"]) {
    assert.ok(html.includes(`>${tab}</button>`), `${tab} has its own tab`);
  }
  assert.match(html, /id="undal-map"/, "an address is saved from a pin, not a typed line");
  assert.match(html, /window\.undalApi\(/, "every write goes through the session-aware helper");
  assert.match(html, /CLOSE_MY_UNDAL_ACCOUNT/, "closing the account needs the confirmation word");
  assert.match(html, /<meta name="robots" content="noindex/, "an account page is never indexed");
  assert.doesNotMatch(html, /fake-web-api-key/);
});

test("the account pages park honestly when accounts are switched off", async () => {
  for (const handler of [handleLoginPage, handleAccountPage]) {
    const html = await (await handler(withoutKey(fullMenu), new Request("https://undal.test/x"))).text();
    assert.match(html, /Accounts on the web are switched off right now/);
    assert.match(html, /a guest order here works without/, "and says what still works");
    assert.doesNotMatch(html, /role="tablist"/, "no form that cannot submit");
  }
});
