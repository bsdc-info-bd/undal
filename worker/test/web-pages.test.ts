import assert from "node:assert/strict";
import { test } from "node:test";
import { renderPage } from "../src/web/layout";
import {
  handleAboutPage,
  handleBasket,
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
    prepMinutes: 20, featured: true, priceFrom: true }],
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
  assert.match(html, /From ৳ 120/);
  assert.match(html, /href="\/item\/rice"/);
});

test("source-listed starting prices are labeled and never become orderable", async () => {
  const sourceMenu = { ...menu, dishes: menu.dishes.map((dish) => ({ ...dish, available: false })) };
  const sourceEnv = { UNDAL_KV: { get: async () => sourceMenu } } as unknown as Env;
  const storefront = await (await handleStoreFront(sourceEnv, new Request("https://undal.test/store"))).text();
  const detail = await (await handleStoreItem(sourceEnv, new Request("https://undal.test/item/rice"), "rice")).text();
  assert.match(storefront, /From ৳ 120/);
  assert.match(storefront, /Price to be confirmed/);
  assert.match(detail, /Starting price only/);
  assert.doesNotMatch(detail, /class="undal-add/);
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

// ----------------------------------------------------- the schedule, on the page

const allDay = [{ open: "00:00", close: "23:59" }];
const everyDay = { sun: allDay, mon: allDay, tue: allDay, wed: allDay, thu: allDay, fri: allDay, sat: allDay };
const dhakaToday = (): string => new Date(Date.now() + 6 * 3_600_000).toISOString().slice(0, 10);

test("the menu says Closed outside the hours, whatever the switch says", async () => {
  // The switch is on — the state every store was left in — and today is a day
  // off. The banner used to read the switch alone and say "Open now".
  const closedToday: WebMenu = {
    ...fullMenu,
    store: { ...storeConfig, open: true, weekly: everyDay, specialDates: [{ date: dhakaToday(), closed: true, slots: [] }] },
  };
  const html = await (await handleStoreFront(withKey(closedToday), new Request("https://undal.test/store"))).text();
  assert.match(html, /<span class="pill closed">Closed now<\/span>/);
  assert.match(html, /You can still browse the menu and fill your basket\./);

  const openAllDay: WebMenu = { ...fullMenu, store: { ...storeConfig, open: true, weekly: everyDay, specialDates: [] } };
  const openHtml = await (await handleStoreFront(withKey(openAllDay), new Request("https://undal.test/store"))).text();
  assert.match(openHtml, /<span class="pill open">Open now<\/span>/);
});

test("the checkout says the kitchen is closed before the customer types an address", async () => {
  const closedToday: WebMenu = {
    ...fullMenu,
    store: { ...storeConfig, open: true, weekly: everyDay, specialDates: [{ date: dhakaToday(), closed: true, slots: [] }] },
  };
  const html = await (await handleCheckout(withKey(closedToday), checkoutRequest())).text();
  assert.match(html, /id="undal-store-closed"/);
  assert.match(html, /The kitchen is closed right now — it opens tomorrow at 12:00 AM\./);
  assert.ok(html.indexOf("undal-store-closed") < html.indexOf("Where should the rider go?"), "before the form, not after it");

  const openAllDay: WebMenu = { ...fullMenu, store: { ...storeConfig, open: true, weekly: everyDay, specialDates: [] } };
  const openHtml = await (await handleCheckout(withKey(openAllDay), checkoutRequest())).text();
  assert.doesNotMatch(openHtml, /id="undal-store-closed"/);
});

test("an expired sign-in at the order button is renewed once, not shown as a bare code", async () => {
  const html = await (await handleCheckout(withKey(fullMenu), checkoutRequest())).text();
  assert.match(html, /response\.status === 401 && !retried && window\.undalRenewSession/);
  assert.match(html, /window\.undalRenewSession = function/);
  assert.match(html, /Your sign-in on this page ran out\./);
});

// ---------------------------------------------------------- redesign structure

test("item detail pairs imagery and information without replacing its real add form", async () => {
  const html = await (await handleStoreItem(env, new Request("https://undal.test/item/rice"), "rice")).text();
  assert.match(html, /<article class="item-detail" aria-labelledby="dish-title">/);
  assert.match(html, /<h2 id="dish-title">Rice &lt;script&gt;/);
  assert.match(html, /class="card item-information"/);
  assert.match(html, /class="undal-add undal-add-wide" method="post" action="\/basket\/add"/);
  assert.match(html, /prep guide: 20 minutes, confirmed when accepted/);
  assert.match(html, /class="back-link" href="\/store"/);
});

test("sold-out detail offers recovery without exposing an add form", async () => {
  const soldOutEnv = { UNDAL_KV: { get: async () => ({ ...menu, dishes: menu.dishes.map(dish => ({ ...dish, available: false, priceFrom: false })) }) } } as unknown as Env;
  const html = await (await handleStoreItem(soldOutEnv, new Request("https://undal.test/item/rice"), "rice")).text();
  const article = html.slice(html.indexOf('<article class="item-detail"'), html.indexOf('</article>'));
  assert.match(article, /not orderable right now/);
  assert.doesNotMatch(article, /<form/);
});

test("basket uses dedicated review and guidance panels with a server checkout link", async () => {
  const html = await (await handleBasket(env, new Request("https://undal.test/basket"))).text();
  assert.match(html, /class="basket-layout"/);
  assert.match(html, /class="card basket-review"/);
  assert.match(html, /class="card basket-guidance"/);
  assert.match(html, /href="\/checkout">Continue to delivery details/);
});

test("checkout details and receipt stay within one POST form with the original input IDs", async () => {
  const configured = { UNDAL_KV: { get: async () => ({ ...menu, store: storeConfig }) }, FIREBASE_WEB_API_KEY: "sample-key" } as unknown as Env;
  const html = await (await handleCheckout(configured, new Request("https://undal.test/checkout"))).text();
  const form = html.slice(html.indexOf('<form method="post" action="/checkout/place"'), html.indexOf('</form>', html.indexOf('id="undal-checkout-form"')));
  assert.match(form, /class="checkout-details"/);
  assert.match(form, /<aside class="checkout-summary" aria-labelledby="summary-heading">/);
  for (const id of ["name", "phone", "title", "desc", "note", "undal-lat", "undal-lng", "undal-lines", "undal-place"]) {
    assert.equal((form.match(new RegExp('id="' + id + '"', 'g')) ?? []).length, 1, id);
  }
  assert.equal((form.match(/type="submit"/g) ?? []).length, 1);
  assert.match(form, /id="undal-order-result" role="status" aria-live="polite"/);
  assert.match(form, /Delivery check needed/);
});

test("menu category and sort controls have distinct navigation labels", async () => {
  const html = await (await handleStoreFront(env, new Request("https://undal.test/store"))).text();
  assert.match(html, /<nav class="chips" aria-label="Menu categories">/);
  assert.match(html, /<nav class="chips" aria-label="Sort menu">/);
});

test("shared design supports rem-based panels, coupon cards and reduced-motion transforms", async () => {
  const { DESIGN } = await import("../src/web/design");
  assert.match(DESIGN, /@media\(min-width:60rem\)/);
  assert.match(DESIGN, /minmax\(min\(100%,15.5rem\),1fr\)/);
  assert.match(DESIGN, /\.coupon-banner\{display:flex;flex-direction:column/);
  assert.match(DESIGN, /\.item:hover,\.item:hover img,\.button:active,\.mini-button:active\{transform:none!important\}/);
});

test("shipping web tokens maintain text and control contrast on warm surfaces", async () => {
  const { DESIGN } = await import("../src/web/design");
  const tokens: Record<string, string> = {};
  for (const root of DESIGN.matchAll(/:root\{([^}]+)\}/g)) {
    for (const token of root[1].matchAll(/--([\w-]+):#([\da-f]{6})/gi)) tokens[token[1]] = token[2];
  }
  const luminance = (hex: string): number => {
    const rgb = [0, 2, 4].map(offset => parseInt(hex.slice(offset, offset + 2), 16) / 255)
      .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
    return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722;
  };
  const pairs: [string, string, number][] = [
    [tokens.ink, tokens.paper, 4.5], [tokens.muted, tokens.paper, 4.5],
    [tokens.brand, "ffffff", 4.5], ["ffffff", tokens.brand, 4.5],
    [tokens["accent-ink"], "fff8e9", 4.5], [tokens.muted, "fff8e9", 4.5],
    [tokens.control, "fffdf9", 3], [tokens["accent-ink"], tokens.paper, 3],
  ];
  for (const [foreground, background, minimum] of pairs) {
    assert.ok(foreground && background, "theme token should resolve");
    const values = [luminance(foreground), luminance(background)].sort((a, b) => a - b);
    assert.ok((values[1] + .05) / (values[0] + .05) >= minimum, `${foreground} on ${background} needs ${minimum}:1`);
  }
});


test("tracking uses paired receipt/progress panels and truthful live/error states", async () => {
  const html = await (await handleOrderTracking("sampleOrder")).text();
  assert.match(html, /class="tracking-layout"/);
  assert.match(html, /class="card tracking-status" aria-label="Order progress"/);
  assert.match(html, /id="undal-status-pill" role="status" aria-live="polite"/);
  assert.match(html, /Status updates need JavaScript/);
  assert.match(html, /Could not refresh/);
  assert.doesNotMatch(html, /is looking at your order now/);
});

test("account shell offers an explicit no-script path and roving keyboard tabs", async () => {
  const html = await (await handleAccountPage({ ...env, FIREBASE_WEB_API_KEY: "sample-key" } as Env,
    new Request("https://undal.test/account"))).text();
  assert.match(html, /Your account needs JavaScript here/);
  assert.match(html, /class="account-workspace" hidden/);
  assert.match(html, /event.key === "ArrowRight"/);
  assert.match(html, /setAttribute\("tabindex", active/);
});
