/**
 * The web store itself (P-013 · P-016 · P-017).
 *
 * Four pages: the menu, one dish, the basket and the checkout, plus the order
 * tracking page. They are server-rendered so the first paint arrives with the
 * food already on screen — no spinner, no JavaScript framework, and a page that
 * still reads with the script switched off. Search and section filters are
 * server-side for the same reason.
 */
import type { Env } from "../env";
import { escapeHtml } from "../util/http";
import { formatPaisa } from "../util/money";
import { dhakaClock, storeStatus } from "../util/store-hours";
import { CART_SCRIPT } from "./cart-client";
import { MAP_SCRIPT } from "./map-client";
import { renderPage } from "./layout";
import {
  breadcrumbJsonLd,
  canonicalUrl,
  faqEntries,
  faqJsonLd,
  itemJsonLd,
  jsonLdScript,
  menuJsonLd,
  organizationJsonLd,
  websiteJsonLd,
} from "./seo";
import {
  populatedSections,
  queryMenu,
  toStoreConfig,
  webMenu,
  type WebItem,
  type WebMenu,
  type WebStoreConfig,
} from "./store-data";

interface ShellOptions {
  title: string;
  description: string;
  body: string;
  active: "menu" | "basket" | "download" | "track" | "account" | "about";
  cacheSeconds?: number;
  /** Page-specific script, appended after the shared cart script. */
  script?: string;
  /** Open Graph URL, when it is not the canonical one. */
  ogUrl?: string;
  /** Structured data, one object per entry, serialised into the head. */
  jsonLd?: unknown[];
  /** Absolute canonical URL for this page. */
  canonical?: string;
  /** `noindex,follow` for a page that is a key rather than a destination. */
  robots?: string;
  ogImage?: string;
}

function nav(active: ShellOptions["active"]): string {
  const link = (href: string, label: string, key: ShellOptions["active"]): string =>
    `<a class="${active === key ? "active" : ""}" ${active === key ? 'aria-current="page"' : ""} href="${href}">${label}</a>`;
  return `<nav class="store" aria-label="Main navigation"><div class="inner">
${link("/store", "Menu", "menu")}
${link("/about", "The kitchen", "about")}
${link("/download", "Install", "download")}
${link("/track", "Track an order", "track")}
${link("/account", "Account", "account")}
<a class="cart" href="/basket">Basket <span id="undal-cart-count" style="display:none"></span></a>
</div></nav>`;
}

function page(options: ShellOptions & { catalogue: Record<string, unknown>; store: WebStoreConfig | null }): Response {
  const catalogue = JSON.stringify(options.catalogue).replace(/</g, "\\u003c");
  const store = JSON.stringify({
    perKmPaisa: options.store?.deliveryPerKmAmountPaisa ?? 0,
    radiusKm: options.store?.radiusKm ?? 0,
    minOrderPaisa: options.store?.minOrderAmountPaisa ?? 0,
  }).replace(/</g, "\\u003c");

  const body = `${options.body}
<div id="undal-flash" class="notice" style="display:none"></div>
<script>
window.UNDAL_CATALOGUE = ${catalogue};
window.UNDAL_STORE = ${store};
${CART_SCRIPT}
${options.script ?? ""}
</script>`;

  return renderPage({
    title: options.title,
    description: options.description,
    body,
    nav: nav(options.active),
    cacheSeconds: options.cacheSeconds ?? 30,
    ogUrl: options.ogUrl ?? options.canonical,
    ogImage: options.ogImage,
    canonical: options.canonical,
    robots: options.robots,
    jsonLd: (options.jsonLd ?? []).map(jsonLdScript).join("\n"),
  });
}

/** The public origin of this deployment, for canonical URLs and structured data. */
function originOf(request: Request): string {
  return new URL(request.url).origin.replace(/\/+$/, "");
}

/** The catalogue blob every page embeds so the basket can show names and prices. */
function catalogueFor(menu: WebMenu): Record<string, unknown> {
  const catalogue: Record<string, unknown> = {};
  for (const dish of menu.dishes) {
    catalogue[dish.id] = { name: dish.name, price: dish.payablePaisa, kind: "dish", maxQty: 50 };
  }
  for (const combo of menu.combos) {
    catalogue[combo.id] = { name: combo.name, price: combo.payablePaisa, kind: "combo", maxQty: 20 };
  }
  return catalogue;
}

function statusBanner(store: WebStoreConfig | null): string {
  if (!store) {
    return `<div class="notice">The kitchen has not published its store document yet. Browsing works; ordering will be refused until it does.</div>`;
  }
  const open = store.open && !store.autoClosed;
  return `<div class="card">
  <span class="pill ${open ? "open" : "closed"}">${open ? "Open now" : "Closed right now"}</span>
  <h2 style="margin-top:10px">${escapeHtml(store.storeName)}</h2>
  <p class="muted" style="margin-top:0">${escapeHtml(store.storeAddressText)}</p>
  ${
    store.manualOverrideNote
      ? `<p>${escapeHtml(store.manualOverrideNote)}</p>`
      : `<p>${open ? "The kitchen is taking orders." : "The kitchen is not taking orders at this moment. You can still browse the menu."}</p>`
  }
  <dl>
    <dt>Delivery</dt><dd>${formatPaisa(store.deliveryPerKmAmountPaisa)} per km, rounded up</dd>
    <dt>Radius</dt><dd>${store.radiusKm} km from the kitchen</dd>
    <dt>Minimum order</dt><dd>${formatPaisa(store.minOrderAmountPaisa)}</dd>
    <dt>Payment</dt><dd>Cash on delivery only</dd>
  </dl>
</div>`;
}

function itemCard(item: WebItem, sectionName: string): string {
  const image = item.imageUrl
    ? `<img src="${escapeHtml(item.imageUrl)}" alt="" loading="lazy" width="480" height="360">`
    : `<div class="food-placeholder" aria-hidden="true"><span>♨</span><small>From our kitchen</small></div>`;
  const tags: string[] = [];
  if (item.kind === "dish" && item.veg) tags.push(`<span class="tag">Veg</span>`);
  if (item.discountAmountPaisa > 0) tags.push(`<span class="tag off">Save ${formatPaisa(item.discountAmountPaisa)}</span>`);
  if (item.kind === "combo") tags.push(`<span class="tag">Combo</span>`);
  if (!item.available) tags.push(`<span class="tag warn">Sold out</span>`);
  const was = item.kind === "dish" && item.discountAmountPaisa > 0
    ? `<span class="was">${formatPaisa(item.amountPaisa)}</span>`
    : "";

  return `<a class="item" href="/item/${item.id}">
${image}
<div class="body">
  <h3>${escapeHtml(item.name)}</h3>
  <p>${escapeHtml(item.description.slice(0, 90))}</p>
  <div>${tags.join(" ")}</div>
  <div style="margin-top:auto;display:flex;justify-content:space-between;align-items:center">
    <span class="price">${formatPaisa(item.payablePaisa)}${was}</span>
    <span class="smallprint">${escapeHtml(sectionName)}</span>
  </div>
</div>
</a>`;
}

// --------------------------------------------------------------------- store

export async function handleStoreFront(env: Env, request: Request): Promise<Response> {
  const url = new URL(request.url);
  let menu: WebMenu;
  try {
    menu = await webMenu(env);
  } catch {
    return renderPage({
      title: "Kitchen temporarily unavailable — Undal",
      description: "Please try the Undal menu again shortly.",
      nav: nav("menu"), status: 503, cacheSeconds: 0,
      body: `<section class="card" role="status"><span class="eyebrow">A small pause</span>
        <h2>We couldn’t reach the kitchen.</h2>
        <p>Your saved basket is still on this device. Please try again shortly.</p>
        <a class="button" href="/store">Try the menu again</a>
        <a class="button secondary" href="/track">Track an existing order</a></section>`,
    });
  }
  const sectionId = url.searchParams.get("section") ?? "";
  const subsectionId = url.searchParams.get("sub") ?? "";
  const search = (url.searchParams.get("q") ?? "").slice(0, 60);
  const sortParam = url.searchParams.get("sort") ?? "recommended";
  const sort = (["recommended", "price-asc", "price-desc", "popular"] as const).includes(
    sortParam as "recommended",
  )
    ? (sortParam as "recommended" | "price-asc" | "price-desc" | "popular")
    : "recommended";

  const items = queryMenu(menu, { sectionId, subsectionId, search, sort });
  const sections = populatedSections(menu);
  const sectionNames = new Map(menu.sections.map((section) => [section.id, section.name]));

  const chips = sections
    .map(
      (section) =>
        `<a class="${sectionId === section.id ? "active" : ""}" href="/store?section=${encodeURIComponent(section.id)}">` +
        `${escapeHtml(section.name)} · ${section.count}</a>`,
    )
    .join("");
  const comboChip = menu.combos.length
    ? `<a class="${sectionId === "combos" ? "active" : ""}" href="/store?section=combos">Combos · ${menu.combos.length}</a>`
    : "";

  const liveCampaign = menu.campaigns.find(
    (campaign) => campaign.active && (!campaign.endAt || Date.parse(campaign.endAt) > Date.now()),
  );
  const campaignCard = liveCampaign
    ? `<section class="card" style="border-color:#f0d9a8;background:#fff8ec">
  <h2>${escapeHtml(liveCampaign.title)}</h2>
  <p class="muted">${escapeHtml(liveCampaign.subtitle)}${
        liveCampaign.code ? ` · code <strong>${escapeHtml(liveCampaign.code)}</strong>` : ""
      }</p>
</section>`
    : "";

  const grid = items.length
    ? `<div class="grid">${items
        .map((item) => itemCard(item, sectionNames.get(item.kind === "dish" ? item.categoryId : "") ?? "Combos"))
        .join("")}</div>`
    : `<div class="notice">Nothing matches that search. <a href="/store">Show the whole menu</a>.</div>`;

  const sortLinks = (
    [
      ["recommended", "Recommended"],
      ["price-asc", "Cheapest first"],
      ["price-desc", "Dearest first"],
      ["popular", "Most ordered"],
    ] as const
  )
    .map(([key, label]) => {
      const query = new URLSearchParams({ sort: key });
      if (sectionId) query.set("section", sectionId);
      if (search) query.set("q", search);
      return `<a class="${sort === key ? "active" : ""}" href="/store?${query.toString()}">${label}</a>`;
    })
    .join("");

  const body = `
<section class="store-hero" aria-labelledby="welcome-title">
  <div><span class="eyebrow">Made with care · Sylhet, Bangladesh</span>
    <h2 id="welcome-title">A little taste<br>of <em>home.</em></h2>
    <p>Comforting meals from our kitchen to your table. Find your favourites, choose your delivery spot, and leave the cooking to us.</p>
    <a class="button" href="#menu">Explore the menu ↓</a>
  </div>
  <aside class="hero-note"><span class="hero-mark" aria-hidden="true">♨</span>
    <h3>Good food. A simpler day.</h3>
    <p>One local kitchen. Food made with care.</p><hr>
    <p><strong>Cash on delivery</strong><br>Order here, pay when your meal arrives. Delivery availability is checked at checkout.</p>
  </aside>
</section>
${statusBanner(menu.store)}
${campaignCard}
<div class="menu-heading" id="menu"><h2>What sounds good today?</h2><p class="muted">${items.length} menu choices · Find your favourite</p></div>
<form class="search" action="/store" method="get" role="search">
  <input type="search" name="q" value="${escapeHtml(search)}" placeholder="Search the menu — bhorta, chicken, tea…" aria-label="Search the menu">
  ${sectionId ? `<input type="hidden" name="section" value="${escapeHtml(sectionId)}">` : ""}
  <button class="button" type="submit" style="margin:0;padding:12px 18px">Search</button>
</form>
<div class="chips">
  ${chips}
  ${comboChip}
</div>
<div class="chips">${sortLinks}</div>
${grid}
<p class="smallprint" style="margin-top:16px">Prices are per dish and include nothing else. The delivery fee is added at checkout from your pinned location, rounded up to whole kilometres.</p>`;

  const origin = originOf(request);
  return page({
    title: `${menu.store?.storeName ?? "Undal HomeKitchen"} — order home-cooked food in Sylhet`,
    description:
      "Browse the live Undal menu and order from a phone browser with cash on delivery. Delivery inside our Sylhet radius, fee per kilometre.",
    body,
    active: "menu",
    catalogue: catalogueFor(menu),
    store: menu.store,
    // Every filter and search permutation points at one canonical URL: a crawler
    // that indexes ?section=rice and ?sort=popular as separate pages dilutes the
    // menu, and the menu is the page that has to win.
    canonical: canonicalUrl(origin, "/store"),
    ogUrl: origin,
    ogImage: menu.store?.bannerUrl || menu.store?.logoUrl || undefined,
    jsonLd: [
      organizationJsonLd(origin, menu.store),
      websiteJsonLd(origin),
      breadcrumbJsonLd(origin, [
        { name: "Home", path: "/" },
        { name: "Menu", path: "/store" },
      ]),
      menuJsonLd(origin, menu.sections, [
        ...menu.dishes.map((dish) => ({ ...dish, categoryId: dish.categoryId })),
        ...menu.combos.map((combo) => ({ ...combo, categoryId: "" })),
      ]),
    ],
  });
}

// ---------------------------------------------------------------------- item

export async function handleStoreItem(env: Env, request: Request, id: string): Promise<Response> {
  const menu = await webMenu(env);
  const origin = originOf(request);
  const item = menu.dishes.find((dish) => dish.id === id) ?? menu.combos.find((combo) => combo.id === id);
  if (!item) {
    return renderPage({
      title: "That dish is gone",
      description: "The dish you followed is no longer on the Undal menu.",
      body: `<section class="card"><h2>That dish is gone from the menu</h2>
<p class="muted">Either the kitchen removed it or the link is old. The rest of the menu is one tap away.</p>
<a class="button" href="/store">See the whole menu</a></section>`,
      nav: nav("menu"),
      cacheSeconds: 60,
    });
  }

  const section = menu.sections.find((entry) => entry.id === (item.kind === "dish" ? item.categoryId : ""));
  const comboContents =
    item.kind === "combo"
      ? `${item.itemRefs.length} dish(es) · normally ${formatPaisa(item.regularAmountPaisa)}${
          item.regularAmountPaisa > item.payablePaisa
            ? ` · you save ${formatPaisa(item.regularAmountPaisa - item.payablePaisa)}`
            : ""
        }`
      : "";

  const body = `
<section class="card">
  ${item.imageUrl ? `<img class="hero" src="${escapeHtml(item.imageUrl)}" alt="">` : ""}
  <h2>${escapeHtml(item.name)}</h2>
  <p class="muted">${escapeHtml(section?.name ?? (item.kind === "combo" ? "Combo" : "Menu"))}${
    item.kind === "dish" ? ` · ready in about ${item.prepMinutes} minutes` : ""
  }</p>
  <p>${escapeHtml(item.description)}</p>
  ${comboContents ? `<p class="muted">${escapeHtml(comboContents)}</p>` : ""}
  ${item.kind === "dish" && item.tags.length ? `<p class="muted">${escapeHtml(item.tags.join(" · "))}</p>` : ""}
  <p class="price" style="font-size:1.3rem">${formatPaisa(item.payablePaisa)}${
    item.kind === "dish" && item.discountAmountPaisa > 0
      ? `<span class="was">${formatPaisa(item.amountPaisa)}</span>`
      : ""
  }</p>
  ${
    item.available
      ? `<button class="button" type="button" onclick="undalAdd('${escapeHtml(item.id)}', 1); window.location.href='/basket';">Add to basket</button>
         <button class="button secondary" type="button" onclick="undalAdd('${escapeHtml(item.id)}', 1);">Keep browsing</button>`
      : `<div class="notice">This dish is finished for today. The kitchen keeps it on the menu so you know what they cook — it is not orderable right now.</div>`
  }
</section>
<section class="card">
  <h2>Prefer the app?</h2>
  <p class="muted">Live order tracking, chat with the kitchen and the Undal order sound are app-only, and the app keeps working when the browser tab is closed.</p>
  <a class="button secondary" href="/download">Install the Undal app</a>
</section>`;

  return page({
    title: `${item.name} — ${menu.store?.storeName ?? "Undal HomeKitchen"}`,
    description: `${item.name}: ${item.description.slice(0, 140)}`,
    body,
    active: "menu",
    catalogue: catalogueFor(menu),
    store: menu.store,
    cacheSeconds: 60,
    canonical: canonicalUrl(origin, `/item/${item.id}`),
    ogUrl: canonicalUrl(origin, `/item/${item.id}`),
    ogImage: item.imageUrl || menu.store?.bannerUrl || undefined,
    jsonLd: [
      organizationJsonLd(origin, menu.store),
      breadcrumbJsonLd(origin, [
        { name: "Home", path: "/" },
        { name: "Menu", path: "/store" },
        { name: item.name, path: `/item/${item.id}` },
      ]),
      itemJsonLd(origin, item, section?.name ?? (item.kind === "combo" ? "Combos" : undefined)),
    ],
  });
}

// -------------------------------------------------------------------- basket

export async function handleBasket(env: Env): Promise<Response> {
  const menu = await webMenu(env);
  const store = menu.store ?? toStoreConfig(null);
  const body = `
<section class="card">
  <h2>Your basket</h2>
  <div id="undal-cart-lines"></div>
  <div id="undal-minimum" class="notice" style="display:none"></div>
  <div class="row"><span>Food</span><span id="undal-subtotal">৳ 0</span></div>
  <div class="row"><span>Delivery</span><span id="undal-delivery">measured at checkout</span></div>
  <div class="row"><span class="total">Total</span><span class="total" id="undal-total">৳ 0</span></div>
  <button class="button" id="undal-checkout" type="button" onclick="window.location.href='/checkout'">Continue to delivery details</button>
  <a class="button secondary" href="/store">Add something else</a>
</section>
<section class="card">
  <h2>How payment works</h2>
  <p class="muted">Cash on delivery only. You pay the rider in taka when the food arrives; there is no card, no wallet and no online payment anywhere in Undal.</p>
  <p class="smallprint">Delivery is ${formatPaisa(store?.deliveryPerKmAmountPaisa ?? 0)} per whole kilometre inside ${
    store?.radiusKm ?? 0
  } km of the kitchen. If your address is outside that radius the checkout will say so plainly instead of taking an order the kitchen cannot serve.</p>
</section>`;

  return page({
    title: "Your basket — Undal HomeKitchen",
    description: "Review your Undal basket before entering delivery details.",
    body,
    active: "basket",
    catalogue: catalogueFor(menu),
    store: menu.store,
    cacheSeconds: 0,
    // A basket is a step, not a destination: indexed it would only compete with
    // the menu for the same words.
    robots: "noindex,follow",
  });
}

// ------------------------------------------------------------------ checkout

export async function handleCheckout(env: Env, request: Request): Promise<Response> {
  const menu = await webMenu(env);
  const store = menu.store;
  const origin = originOf(request);
  if (!store) {
    return renderPage({
      title: "The kitchen is not ready",
      description: "The Undal store document has not been published yet.",
      body: `<section class="card"><h2>The kitchen is not ready to take orders</h2>
<p class="muted">Its store document has not been published, so the delivery fee and radius cannot be checked. Please call the kitchen and order on the phone today.</p>
<a class="button secondary" href="/store">Back to the menu</a></section>`,
      nav: nav("basket"),
      cacheSeconds: 0,
    });
  }

  if (!(env.FIREBASE_WEB_API_KEY ?? "").length) {
    // Web ordering needs Firebase's anonymous sign-in key. Until the owner
    // adds it, the checkout page says so plainly instead of failing at the
    // last tap.
    return renderPage({
      title: "Order in the Undal app — Undal HomeKitchen",
      description: "Web checkout is switched off; ordering happens in the Undal app.",
      body: `<section class="card"><h2>Ordering on the web is switched off right now</h2>
<p class="muted">The shop is open and the menu above is live, but this checkout needs the Firebase web sign-in key before it can take an order. The kitchen's own app takes orders right now, and so does a phone call.</p>
<a class="button" href="/download">Install the Undal app</a>
<a class="button secondary" href="/store">Back to the menu</a>
<p class="smallprint">Your basket is safe on this phone — install the app, tap the same dishes, and the kitchen still gets the order in seconds.</p></section>`,
      nav: nav("basket"),
      cacheSeconds: 0,
    });
  }

  const storePin = { lat: store.storeLat, lng: store.storeLng, radiusKm: store.radiusKm };

  const body = `
<section class="card">
  <h2>Where should the rider go?</h2>
  <p class="muted">Move the map until the green pin is on your door, then <strong>tick it</strong>. The delivery fee is
  measured from that exact point and the rider is sent to it — so the pin is worth ten seconds of your time.</p>
  <div id="undal-map"></div>
  <p class="undal-map-attribution">Tiles © OpenStreetMap contributors · the pin is placed by you, never guessed from your IP address.</p>
  <div id="undal-radius" class="notice" style="display:none"></div>
  <div class="auth-split">
    <div>
      <label for="name">Name the kitchen should call out</label>
      <input id="name" autocomplete="name" placeholder="Rahim" required>
      <label for="phone">Mobile number the rider will call</label>
      <input id="phone" inputmode="numeric" autocomplete="tel" placeholder="01712345678" required>
    </div>
    <div>
      <label for="title">Area or neighbourhood</label>
      <input id="title" placeholder="Zindabazar, Sylhet" required>
      <label for="desc">House, road and floor</label>
      <input id="desc" placeholder="House 14, Road 3, first floor, blue gate" required>
    </div>
  </div>
  <label for="note">One landmark the rider can ask for</label>
  <input id="note" placeholder="Beside the mosque, opposite the tea stall" required>
  <label for="cartnote">Anything the kitchen should know (optional)</label>
  <textarea id="cartnote" rows="2" placeholder="Less chilli on the bhorta, please."></textarea>
  <label for="coupon">Coupon code (optional)</label>
  <input id="coupon" autocapitalize="characters" placeholder="WELCOME10">
  <div class="row"><span>Food</span><span id="undal-subtotal">৳ 0</span></div>
  <div class="row"><span>Delivery</span><span id="undal-delivery">measured once you tick the pin</span></div>
  <div class="row"><span class="total">Cash on delivery</span><span class="total" id="undal-total">৳ 0</span></div>
  <div id="undal-cart-lines" class="smallprint" style="display:none"></div>
  <div id="undal-minimum" class="notice" style="display:none"></div>
  <div id="undal-order-result"></div>
  <button class="button" id="undal-place" type="button">Place the order — pay cash on delivery</button>
  <p class="smallprint">Placing the order tells the kitchen immediately. The order number and the tracking page appear as soon as it is written. A customer cannot cancel from the web — call the kitchen if something changes.</p>
</section>
<section class="card">
  <h2>What happens after you tap</h2>
  <ol class="steps">
    <li class="done"><span class="dot"></span><span>Your order gets a number like <strong>UND-1042</strong> and rings the kitchen phone.</span></li>
    <li><span class="dot"></span><span>The kitchen accepts it with a preparation time, or cancels it with a written reason.</span></li>
    <li><span class="dot"></span><span>You watch the status on the tracking page — no account needed, the link is the key.</span></li>
    <li><span class="dot"></span><span>You pay the rider in cash when the food arrives.</span></li>
  </ol>
</section>
<section class="card">
  <h2>The three questions everybody asks</h2>
  <dl>
    <dt>How do I pay?</dt><dd>Cash on delivery. There is no card, no wallet and no online payment anywhere in Undal.</dd>
    <dt>How much is delivery?</dt><dd>${escapeHtml(formatPaisa(store.deliveryPerKmAmountPaisa))} for every whole kilometre from the kitchen to your pin, rounded up. The number is on this page before you order.</dd>
    <dt>Do I need the app?</dt><dd>No. The app adds live status pushes, chat with the kitchen and reorders — everything else, including this checkout, works in the browser.</dd>
  </dl>
</section>`;

  const script = `${MAP_SCRIPT}
(function () {
  var store = window.UNDAL_STORE || {};
  var storePin = ${JSON.stringify(storePin)};
  var nameBox = document.getElementById("name");
  var phoneBox = document.getElementById("phone");
  var titleBox = document.getElementById("title");
  var descBox = document.getElementById("desc");
  var noteBox = document.getElementById("note");
  var resultHost = document.getElementById("undal-order-result");
  var placeButton = document.getElementById("undal-place");
  var notice = document.getElementById("undal-radius");

  var position = null;
  var ticked = false;
  var info = {};

  function showNotice(text, kind) {
    if (!notice) return;
    notice.style.display = text ? "block" : "none";
    notice.className = "notice" + (kind ? " " + kind : "");
    notice.textContent = text || "";
  }

  function preview() {
    if (!position) { window.undalRender(); return; }
    if (!storePin.lat || !storePin.lng) {
      showNotice("The kitchen has not published its exact location yet, so the delivery fee cannot be measured here. " +
        "Please call the kitchen — they will tell you the honest price to your door.");
      window.undalRender();
      return;
    }
    var km = window.undalHaversine(storePin, position);
    window.UNDAL_DISTANCE_KM = km;
    window.undalRender();
    var host = document.getElementById("undal-delivery");
    if (host) {
      var fee = Math.ceil(km) * (store.perKmPaisa || 0);
      host.textContent = window.undalMoney(fee) + " (" + km.toFixed(1) + " km, rounded up)";
    }
    if (store.radiusKm && km > store.radiusKm) {
      showNotice("That pin is " + km.toFixed(1) + " km from the kitchen, outside the " + store.radiusKm +
        " km delivery radius. Please call the kitchen — they will tell you honestly whether they can get there.", "error");
    } else if (!ticked) {
      showNotice("Move the map until the pin is on your door, then tick it below the map.");
    } else {
      showNotice("");
    }
  }

  /** The map's own name for the place, written into the empty fields only. */
  function fillAddress() {
    if (info.area && !titleBox.value) {
      titleBox.value = info.city && info.city !== info.area ? info.area + ", " + info.city : info.area;
    }
    if (info.road && !descBox.value) descBox.value = info.road;
  }

  var map = window.undalMap({
    mount: "undal-map",
    store: storePin.lat && storePin.lng ? storePin : null,
    perKmPaisa: store.perKmPaisa || 0,
    confirmLabel: "Tick this pin as my delivery address",
    onPin: function (point, next) { position = point; info = next || info; preview(); },
    onConfirm: function (point, next) { position = point; info = next || info; ticked = true; fillAddress(); preview(); }
  });

  placeButton.addEventListener("click", function () {
    var button = this;
    var lines = window.undalLines();
    if (lines.length === 0) {
      resultHost.innerHTML = '<div class="notice error">Your basket is empty. <a href="/store">Back to the menu</a>.</div>';
      return;
    }
    if (!position) {
      resultHost.innerHTML = '<div class="notice error">Move the map so the pin is on your door — the kitchen measures the delivery fee from it.</div>';
      return;
    }
    if (!ticked) {
      resultHost.innerHTML = '<div class="notice error">Tick the pin to confirm that is your address. The rider is sent to exactly that point.</div>';
      return;
    }
    var name = nameBox.value.trim();
    var phone = phoneBox.value.trim();
    var title = titleBox.value.trim();
    var desc = descBox.value.trim();
    var note = noteBox.value.trim();
    var localPhone = window.undalUsablePhone(phone);
    var missing = [];
    if (!window.undalUsableName(name)) missing.push("your name (letters, three or more)");
    if (!localPhone) missing.push("a Bangladeshi mobile like 01712345678");
    if (!window.undalUsableText(title)) missing.push("the area");
    if (!window.undalUsableText(desc)) missing.push("the house and road");
    if (!window.undalUsableText(note)) missing.push("one landmark the rider can ask for");
    if (missing.length > 0) {
      resultHost.innerHTML = '<div class="notice error">Still needed: ' + missing.join(", ") + ".</div>";
      return;
    }
    button.disabled = true;
    button.textContent = "Telling the kitchen…";
    resultHost.innerHTML = "";

    window.undalSession()
      .then(function (session) {
        return fetch("/api/store/order", {
          method: "POST",
          headers: { "content-type": "application/json", authorization: "Bearer " + session.idToken },
          body: JSON.stringify({
            lines: lines,
            couponCode: document.getElementById("coupon").value.trim(),
            note: document.getElementById("cartnote").value.trim(),
            customerName: name,
            phone: localPhone,
            address: { lat: position.lat, lng: position.lng, title: title, desc: desc, note: note }
          })
        });
      })
      .then(function (response) {
        return response.json().then(function (payload) { return { ok: response.ok, payload: payload }; });
      })
      .then(function (outcome) {
        if (!outcome.ok || !outcome.payload.ok) {
          throw new Error(outcome.payload.message || "The order did not go through.");
        }
        window.undalClear();
        resultHost.innerHTML = '<div class="notice ok">Order <strong>' + outcome.payload.order.shortId +
          "</strong> is with the kitchen. Taking you to the tracking page…</div>";
        window.location.href = "/order/" + outcome.payload.order.id + "?new=1";
      })
      .catch(function (error) {
        button.disabled = false;
        button.textContent = "Place the order — pay cash on delivery";
        resultHost.innerHTML = '<div class="notice error">' + String(error.message || error) + "</div>";
      });
  });

  // A signed-in customer should not retype what the kitchen already knows, and a
  // saved address arrives with its pin already ticked.
  var savedProfile = window.undalProfile ? window.undalProfile() : null;
  if (savedProfile) {
    if (!nameBox.value && savedProfile.name) nameBox.value = savedProfile.name;
    if (!phoneBox.value && savedProfile.phone) phoneBox.value = savedProfile.phone;
    var saved = savedProfile.address;
    if (saved && saved.lat && saved.lng && map) {
      if (saved.title) titleBox.value = saved.title;
      if (saved.desc) descBox.value = saved.desc;
      if (saved.note) noteBox.value = saved.note;
      map.setPin(saved.lat, saved.lng, 16);
      map.confirm();
    }
  }

  window.undalRender();
})();`;

  return page({
    title: "Delivery details — Undal HomeKitchen",
    description: "Drop a pin on the map, enter the delivery details and place a cash-on-delivery order.",
    body,
    script,
    active: "basket",
    catalogue: catalogueFor(menu),
    store: menu.store,
    cacheSeconds: 0,
    robots: "noindex,follow",
    canonical: canonicalUrl(origin, "/checkout"),
    // The questions on this page are the questions Google is asked about the
    // kitchen, so the same answers are published as structured data.
    jsonLd: [
      organizationJsonLd(origin, store),
      faqJsonLd(origin, store),
      breadcrumbJsonLd(origin, [
        { name: "Home", path: "/" },
        { name: "Menu", path: "/store" },
        { name: "Basket", path: "/basket" },
        { name: "Delivery details", path: "/checkout" },
      ]),
    ],
  });
}

// -------------------------------------------------------------------- tracking

// -------------------------------------------------------------------- account

// `/login` and `/account` live in ./account-page.ts: sign-in, sign-up, the
// profile, the address book, the order history, the password and the account's
// own end are one feature and are kept in one file.

export async function handleTrackPage(orderId: string): Promise<Response> {
  const body = `<section class="card">
  <h2>Track an Undal order</h2>
  <p class="muted">Paste the order link the kitchen gave you, or the order id from the tracking page.</p>
  <form action="/track" method="get">
    <label for="id">Order id</label>
    <input id="id" name="id" placeholder="the long id from the tracking link" value="${escapeHtml(orderId)}">
    <button class="button" type="submit">Open the tracking page</button>
  </form>
  <p class="smallprint">The tracking link is the key: anybody holding it can watch that one order's status, and it never shows your phone number or address.</p>
</section>`;
  return renderPage({
    title: "Track an order — Undal HomeKitchen",
    description: "Follow an Undal order's status from accepted to delivered.",
    body,
    nav: nav("track"),
    cacheSeconds: 0,
    robots: "noindex,follow",
  });
}

export async function handleOrderTracking(orderId: string): Promise<Response> {
  return renderPage({
    title: "Order status — Undal HomeKitchen",
    description: "Live status of an Undal HomeKitchen order.",
    body: `<section class="card">
  <span class="pill" id="undal-status-pill">Loading…</span>
  <h2 style="margin-top:10px" id="undal-order-title">Order</h2>
  <ul class="steps" id="undal-steps">
    <li data-status="placed"><span class="dot"></span><span>Placed — the kitchen has your order</span></li>
    <li data-status="accepted"><span class="dot"></span><span>Accepted — the kitchen is cooking</span></li>
    <li data-status="ready"><span class="dot"></span><span>Ready — packed and waiting for the rider</span></li>
    <li data-status="in_delivery"><span class="dot"></span><span>On the way — keep the cash ready</span></li>
    <li data-status="delivered"><span class="dot"></span><span>Delivered — thank you</span></li>
  </ul>
  <div id="undal-note"></div>
  <div class="row"><span>Cash to pay the rider</span><span class="total" id="undal-order-total">—</span></div>
  <p class="smallprint" id="undal-updated"></p>
  <a class="button secondary" href="/store">Order something else</a>
  <a class="button secondary" href="/download">Install the app for live pushes</a>
</section>
<script>
(function () {
  var orderId = ${JSON.stringify(orderId.replace(/[^A-Za-z0-9]/g, ""))};
  var labels = {
    placed: ["Placed", "The kitchen has your order and is looking at it now."],
    accepted: ["Cooking", "Accepted — the prep timer is running in the kitchen."],
    ready: ["Ready", "Packed and waiting for the rider."],
    in_delivery: ["On the way", "The rider has left the kitchen. Keep the cash ready."],
    delivered: ["Delivered", "Delivered and paid. Thank you for ordering from Undal."],
    cancelled: ["Cancelled", "The kitchen cancelled this order. Their reason is below."]
  };

  function paint(order) {
    var pair = labels[order.status] || ["Updated", "The order status changed."];
    document.getElementById("undal-status-pill").textContent = pair[0];
    document.getElementById("undal-status-pill").className = "pill " + (order.status === "cancelled" ? "closed" : "open");
    document.getElementById("undal-order-title").textContent = "Order " + (order.shortId || "");
    document.querySelectorAll("#undal-steps li").forEach(function (node) {
      var status = node.getAttribute("data-status");
      node.className = status === order.status ? "now" : "";
      var order_ = ["placed", "accepted", "ready", "in_delivery", "delivered"];
      if (order_.indexOf(order.status) > order_.indexOf(status)) node.className = "done";
    });
    var noteHost = document.getElementById("undal-note");
    if (order.status === "cancelled") {
      noteHost.innerHTML = '<div class="notice error"><strong>Cancelled:</strong> ' +
        String(order.cancelNote || "no reason written").replace(/</g, "&lt;") + "</div>";
    } else if (order.note) {
      noteHost.innerHTML = '<div class="notice">Kitchen note: ' + String(order.note).replace(/</g, "&lt;") + "</div>";
    } else {
      noteHost.innerHTML = '<p class="muted">' + pair[1] + "</p>";
    }
    document.getElementById("undal-order-total").textContent =
      (order.totalPaisa / 100).toLocaleString("en-US", { minimumFractionDigits: order.totalPaisa % 100 ? 2 : 0, maximumFractionDigits: 2 }).replace(/^/, "৳ ");
    document.getElementById("undal-updated").textContent = order.updatedAt ? "Last update: " + order.updatedAt : "";
    if (order.status === "delivered" || order.status === "cancelled") {
      window.clearInterval(window._undalPoll);
    }
  }

  function poll() {
    fetch("/api/store/order/" + encodeURIComponent(orderId))
      .then(function (response) { return response.json(); })
      .then(function (payload) { if (payload && payload.ok) paint(payload.order); })
      .catch(function () { /* the next poll tries again */ });
  }
  poll();
  window._undalPoll = window.setInterval(poll, 20000);
})();
</script>`,
    nav: nav("track"),
    cacheSeconds: 0,
  });
}

// --------------------------------------------------------------------- about

/**
 * `GET /about` — what Undal is, how an order travels from this website into a
 * home kitchen, and the questions people ask before ordering. The FAQ is the
 * very list the structured data carries, so a crawler and a reader are told the
 * same thing from one place.
 */
export async function handleAboutPage(env: Env, request: Request): Promise<Response> {
  const origin = originOf(request);
  let menu: WebMenu;
  try {
    menu = await webMenu(env);
  } catch {
    return renderPage({
      title: "About the kitchen — Undal",
      description: "Please try the Undal about page again shortly.",
      nav: nav("about"),
      status: 503,
      cacheSeconds: 0,
      body: `<section class="card" role="status"><span class="eyebrow">A small pause</span>
        <h2>We couldn’t reach the kitchen.</h2>
        <p>Please try again in a minute.</p>
        <a class="button" href="/store">Go to the menu</a></section>`,
    });
  }

  const store = menu.store;
  const status = storeStatus(store);
  const clock = dhakaClock();
  const items = queryMenu(menu, { sectionId: "", subsectionId: "", search: "", sort: "recommended" });
  const available = items.filter((item) => item.available);
  const faqs = faqEntries(store);
  const name = store?.storeName || "Undal HomeKitchen";
  const radius = store?.radiusKm ?? 0;
  const perKm = store?.deliveryPerKmAmountPaisa ?? 0;

  const steps: Array<[string, string]> = [
    ["Pick your food", `Every dish on the menu is cooked to order — ${available.length} of the ${items.length} on the board today. Tap one to read what is in it, how long it takes and what it costs.`],
    ["Drop your pin", "On the checkout the map shows exactly where we will deliver. Drag the pin, read the address it worked out and tick it. The fee is your distance from the kitchen multiplied by the store's own rate, rounded up to the whole kilometre."],
    ["Send the order", "The kitchen is alerted the moment you order — a loud looping alarm on the merchant app, even when that phone is asleep, locked or in a pocket. That is why the app asks for the battery exemption."],
    ["Follow it live", "Your tracking page updates itself. Confirmed, accepted, preparing, ready, dispatched, delivered: each step is written the second the kitchen taps it."]
  ];

  const todayLine = status.todaySlots.length === 0
    ? "Closed today"
    : status.todaySlots.map((slot) => `${escapeHtml(slot.open)} \u2013 ${escapeHtml(slot.close)}`).join(" \u00b7 ");

  const facts: Array<[string, string]> = [
    ["Kitchen", escapeHtml(store?.storeName || name)],
    [`Today (${clock.weekday.toUpperCase()})`, todayLine],
    ["Address", escapeHtml(store?.storeAddressText || "Sylhet, Bangladesh")],
    ["Delivery radius", radius > 0 ? `${radius} km from the kitchen` : "A fixed radius set by the kitchen"],
    ["Per kilometre", perKm > 0 ? `${formatPaisa(perKm)} per whole km, rounded up` : "Charged per whole kilometre"],
    ["Minimum order", (store?.minOrderAmountPaisa ?? 0) > 0 ? formatPaisa(store!.minOrderAmountPaisa) : "None"],
    ["Payment", "Cash on delivery"],
    ["On the menu today", `${available.length} available of ${items.length} listed`],
    ["Right now", escapeHtml(status.headline)]
  ];

  const body = `<section class="hero">
  <span class="pill ${status.openNow ? "open" : "closed"}">${escapeHtml(status.headline)}</span>
  <span class="eyebrow">About the kitchen</span>
  <h1>One home kitchen. A short menu. A pin you drop yourself.</h1>
  <p class="lead">Undal is not a cloud kitchen and not an aggregator listing. It is one cook in Sylhet, a menu that changes with what is fresh, and a delivery radius small enough that the food arrives hot. You order from a phone browser or from the app; the kitchen hears the order ring whether its phone is awake or not.</p>
  <p class="note">${escapeHtml(status.detail)}</p>
  <div class="actions">
    <a class="button" href="/store">See today’s menu</a>
    <a class="button secondary" href="/account">My account</a>
  </div>
</section>

<section class="card">
  <h2>How an order travels</h2>
  <ol class="steps">
    ${steps.map(([title, text], index) => `<li><span class="step-number">${index + 1}</span><div><h3>${escapeHtml(title)}</h3><p>${escapeHtml(text)}</p></div></li>`).join("\n    ")}
  </ol>
</section>

<section class="split">
  <div class="card">
    <h2>The kitchen at a glance</h2>
    <dl class="facts">
      ${facts.map(([label, value]) => `<div><dt>${escapeHtml(label)}</dt><dd>${value}</dd></div>`).join("\n      ")}
    </dl>
    ${store?.manualOverrideNote ? `<p class="note">${escapeHtml(store.manualOverrideNote)}</p>` : ""}
  </div>
  <div class="card">
    <h2>For the cook</h2>
    <p>The merchant side is the Android app: the ringing order alert that wakes a sleeping phone, the status ladder, the day's history and the menu editor. One vendor, one kitchen, one device list — no dashboard to learn.</p>
    <div class="actions">
      <a class="button secondary" href="/download">Download the app</a>
      <a class="button ghost" href="/app">Open an app link</a>
    </div>
  </div>
</section>

<section class="card" id="faq">
  <h2>Questions before you order</h2>
  ${faqs.map((entry) => `<details class="faq-item"><summary>${escapeHtml(entry.question)}</summary><p>${escapeHtml(entry.answer)}</p></details>`).join("\n  ")}
</section>

<section class="card">
  <h2>Share the kitchen</h2>
  <p class="lead">Send this link to anyone inside the delivery radius. The menu, the prices and the cash-on-delivery rule are the same on every device.</p>
  <p><code>${escapeHtml(origin)}</code></p>
  <div class="actions">
    <a class="button secondary" href="https://wa.me/?text=${encodeURIComponent(`Undal HomeKitchen \u2014 home-cooked food delivered in Sylhet: ${origin}`)}">Share on WhatsApp</a>
    <a class="button ghost" href="/track">Track an existing order</a>
  </div>
</section>

<section class="card">
  <h2>The small print, plainly</h2>
  <ul class="plain-list">
    <li>Money is kept in whole paisa and rounded once, at the end. A split bill never loses a fraction and a discount never turns a price negative.</li>
    <li>The distance to your pin is measured as the crow flies, and the fee follows that number — not the traffic and not the route the rider chooses.</li>
    <li>An order without a ticked location cannot be placed: a pin beats a typed address, and it is what the rider is given.</li>
    <li>Your tracking link is the key to that order. Anyone holding it can watch it; it never shows your phone number or your address.</li>
    <li>An account is optional. It only keeps your addresses and your order history on this device and in your own profile, so reordering is two taps.</li>
  </ul>
</section>`;

  return page({
    catalogue: catalogueFor(menu),
    store,
    title: `About ${name} — a home kitchen in Sylhet`,
    description: `How ${name} works: ${available.length} dishes cooked to order, a delivery pin you tick yourself, ${radius > 0 ? `${radius} km reach` : "a fixed delivery radius"} and a kitchen that is alerted the moment you order.`,
    active: "about",
    cacheSeconds: 60,
    canonical: canonicalUrl(origin, "/about"),
    jsonLd: [
      organizationJsonLd(origin, store),
      faqJsonLd(origin, store),
      breadcrumbJsonLd(origin, [
        { name: "Home", path: "/" },
        { name: "About the kitchen", path: "/about" },
      ]),
    ],
    body,
  });
}
