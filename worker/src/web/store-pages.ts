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
import { CART_SCRIPT, SESSION_STORAGE_KEY, PROFILE_STORAGE_KEY } from "./cart-client";
import { renderPage } from "./layout";
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
  active: "menu" | "basket" | "download" | "track" | "account";
  cacheSeconds?: number;
}

function nav(active: ShellOptions["active"]): string {
  const link = (href: string, label: string, key: ShellOptions["active"]): string =>
    `<a class="${active === key ? "active" : ""}" href="${href}">${label}</a>`;
  return `<nav class="store"><div class="inner">
${link("/store", "Menu", "menu")}
${link("/download", "Install", "download")}
${link("/track", "Track an order", "track")}
${link("/login", "Sign in", "account")}
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
</script>`;

  return renderPage({
    title: options.title,
    description: options.description,
    body,
    nav: nav(options.active),
    cacheSeconds: options.cacheSeconds ?? 30,
    ogUrl: undefined,
  });
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
    : `<div style="aspect-ratio:4/3;background:#f2f4f0"></div>`;
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
  const menu = await webMenu(env);
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
${statusBanner(menu.store)}
${campaignCard}
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

  return page({
    title: `${menu.store?.storeName ?? "Undal HomeKitchen"} — order home-cooked food in Sylhet`,
    description:
      "Browse the live Undal menu and order from a phone browser with cash on delivery. Delivery inside our Sylhet radius, fee per kilometre.",
    body,
    active: "menu",
    catalogue: catalogueFor(menu),
    store: menu.store,
  });
}

// ---------------------------------------------------------------------- item

export async function handleStoreItem(env: Env, id: string): Promise<Response> {
  const menu = await webMenu(env);
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
  });
}

// ------------------------------------------------------------------ checkout

export async function handleCheckout(env: Env): Promise<Response> {
  const menu = await webMenu(env);
  const store = menu.store;
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

  const body = `
<section class="card">
  <h2>Where should the rider go?</h2>
  <p class="muted">Tap the button, then check the three boxes below — the landmark is the part riders actually use.</p>
  <button class="button secondary" id="undal-locate" type="button">Use my current location</button>
  <div id="undal-locate-result" class="smallprint"></div>
  <label for="name">Name the kitchen should call out</label>
  <input id="name" autocomplete="name" placeholder="Rahim" required>
  <label for="phone">Mobile number the rider will call</label>
  <input id="phone" inputmode="numeric" autocomplete="tel" placeholder="01712345678" required>
  <label for="title">Area or neighbourhood</label>
  <input id="title" placeholder="Zindabazar, Sylhet" required>
  <label for="desc">House, road and floor</label>
  <input id="desc" placeholder="House 14, Road 3, first floor, blue gate" required>
  <label for="note">One landmark the rider can ask for</label>
  <input id="note" placeholder="Beside the mosque, opposite the tea stall" required>
  <label for="cartnote">Anything the kitchen should know (optional)</label>
  <textarea id="cartnote" rows="2" placeholder="Less chilli on the bhorta, please."></textarea>
  <label for="coupon">Coupon code (optional)</label>
  <input id="coupon" autocapitalize="characters" placeholder="WELCOME10">
  <div class="row"><span>Food</span><span id="undal-subtotal">৳ 0</span></div>
  <div class="row"><span>Delivery</span><span id="undal-delivery">measured once you pin the location</span></div>
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

<script>
(function () {
  var position = null;
  var locateButton = document.getElementById("undal-locate");
  var result = document.getElementById("undal-locate-result");

  locateButton.addEventListener("click", function () {
    if (!navigator.geolocation) {
      result.textContent = "This browser cannot read your location. Please type the address by hand.";
      return;
    }
    result.textContent = "Reading your position…";
    navigator.geolocation.getCurrentPosition(function (fix) {
      position = { lat: fix.coords.latitude, lng: fix.coords.longitude };
      result.textContent = "Pinned at " + position.lat.toFixed(5) + ", " + position.lng.toFixed(5) + ". Filling the address…";
      // Reverse geocoding happens from this browser directly to OpenStreetMap,
      // so the visitor's position is never routed through the kitchen's server.
      fetch("https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=" + position.lat + "&lon=" + position.lng, {
        headers: { "Accept-Language": "en" }
      })
        .then(function (response) { return response.json(); })
        .then(function (data) {
          var address = data.address || {};
          var area = address.suburb || address.neighbourhood || address.village || address.town || address.city || "";
          var road = [address.house_number, address.road].filter(Boolean).join(" ");
          if (area && !document.getElementById("title").value) document.getElementById("title").value = area + (address.city && address.city !== area ? ", " + address.city : "");
          if (road && !document.getElementById("desc").value) document.getElementById("desc").value = road;
          result.textContent = "Address filled from the map. Check it, and add the landmark the rider should ask for.";
          updateDeliveryPreview();
        })
        .catch(function () {
          result.textContent = "Location pinned. The address lookup did not answer — please type the three lines by hand.";
          updateDeliveryPreview();
        });
    }, function (error) {
      result.textContent = "Location refused (" + error.message + "). Type the address and place the order by phone if you prefer.";
    }, { enableHighAccuracy: true, timeout: 12000, maximumAge: 60000 });
  });

  function haversine(a, b) {
    var toRad = function (degrees) { return degrees * Math.PI / 180; };
    var dLat = toRad(b.lat - a.lat), dLng = toRad(b.lng - a.lng);
    var h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return 6371.0088 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
  }

  function updateDeliveryPreview() {
    var store = window.UNDAL_STORE || {};
    var pinned = ${JSON.stringify({ lat: store.storeLat, lng: store.storeLng })};
    if (!position) { window.undalRender(); return; }
    if (!pinned.lat || !pinned.lng) {
      var unpinned = document.getElementById("undal-radius");
      if (!unpinned) {
        unpinned = document.createElement("div");
        unpinned.id = "undal-radius";
        unpinned.className = "notice";
        var anchor = document.getElementById("undal-place");
        anchor.parentNode.insertBefore(unpinned, anchor);
      }
      unpinned.style.display = "block";
      unpinned.textContent = "The kitchen has not published its exact location yet, so the delivery fee cannot be measured here. " +
        "Please call the kitchen — they will tell you the honest price to your door.";
      window.undalRender();
      return;
    }
    var km = haversine(pinned, position);
    window.UNDAL_DISTANCE_KM = km;
    window.undalRender();
    var host = document.getElementById("undal-delivery");
    if (host) {
      var fee = Math.ceil(km) * (store.perKmPaisa || 0);
      host.textContent = window.undalMoney(fee) + " (" + km.toFixed(1) + " km, rounded up)";
    }
    var notice = document.getElementById("undal-radius");
    if (!notice) {
      notice = document.createElement("div");
      notice.id = "undal-radius";
      notice.className = "notice";
      var anchor = document.getElementById("undal-place");
      anchor.parentNode.insertBefore(notice, anchor);
    }
    if (store.radiusKm && km > store.radiusKm) {
      notice.style.display = "block";
      notice.textContent = "That address is " + km.toFixed(1) + " km from the kitchen, outside the " +
        store.radiusKm + " km delivery radius. Please call the kitchen — they will tell you honestly whether they can get there.";
    } else {
      notice.style.display = "none";
    }
  }

  document.getElementById("undal-place").addEventListener("click", function () {
    var button = this;
    var resultHost = document.getElementById("undal-order-result");
    var lines = window.undalLines();
    if (lines.length === 0) {
      resultHost.innerHTML = '<div class="notice error">Your basket is empty.</div>';
      return;
    }
    if (!position) {
      resultHost.innerHTML = '<div class="notice error">Tap "Use my current location" first — the kitchen measures the delivery fee from it.</div>';
      return;
    }
    var name = document.getElementById("name").value.trim();
    var phone = document.getElementById("phone").value.trim();
    var title = document.getElementById("title").value.trim();
    var desc = document.getElementById("desc").value.trim();
    var note = document.getElementById("note").value.trim();
    var localPhone = usablePhone(phone);
    if (!usableName(name) || !localPhone || !usableText(title) || !usableText(desc) || !usableText(note)) {
      resultHost.innerHTML = '<div class="notice error">Check these please: your name (letters, three or more), a Bangladeshi mobile like 01712345678, the area, the house and a landmark they can ask for.</div>';
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
            customerName: name.trim(),
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

  // A signed-in customer should not retype what the kitchen already knows.
  var savedProfile = window.undalProfile ? window.undalProfile() : null;
  if (savedProfile) {
    if (!document.getElementById("name").value && savedProfile.name) {
      document.getElementById("name").value = savedProfile.name;
    }
    if (!document.getElementById("phone").value && savedProfile.phone) {
      document.getElementById("phone").value = savedProfile.phone;
    }
  }

  window.undalRender();
})();
</script>`;

  return page({
    title: "Delivery details — Undal HomeKitchen",
    description: "Enter the delivery details and place a cash-on-delivery order.",
    body,
    active: "basket",
    catalogue: catalogueFor(menu),
    store: menu.store,
    cacheSeconds: 0,
  });
}

// -------------------------------------------------------------------- tracking

// -------------------------------------------------------------------- account

/**
 * `GET /login` — the same account the app uses, on the web (P-013).
 *
 * Signing in is optional: a guest can order without it. What it buys is a
 * checkout form that already knows the name and the mobile number, and a web
 * order that lands in the app's own order history. The password goes to
 * Firebase Authentication through the Worker, which holds the web API key; the
 * browser never sees the key and the Worker never stores a credential.
 */
export async function handleLoginPage(env: Env, request: Request): Promise<Response> {
  void request;
  if (!(env.FIREBASE_WEB_API_KEY ?? "").trim().length) {
    return renderPage({
      title: "Sign in — Undal HomeKitchen",
      description: "Web sign-in is switched off; the Undal app holds the account.",
      body: `<section class="card"><h2>Signing in on the web is switched off right now</h2>
<p class="muted">The accounts live in Firebase and this site has not been given the key that talks to it yet. The Undal app signs in as usual, and a guest order here works without an account at all.</p>
<a class="button" href="/checkout">Order as a guest</a>
<a class="button secondary" href="/store">Back to the menu</a></section>`,
      nav: nav("account"),
      cacheSeconds: 0,
    });
  }

  const body = `
<section class="card">
  <h2>Sign in to your Undal account</h2>
  <p class="muted">The same email and password you use in the Undal app. Signing in fills in your name and number at the checkout and keeps this order in your history — a guest order works just as well.</p>
  <label for="email">Email address</label>
  <input id="email" type="email" autocomplete="email" inputmode="email" placeholder="you@example.com">
  <label for="password">Password</label>
  <input id="password" type="password" autocomplete="current-password" placeholder="Your Undal password">
  <div id="undal-account-state" class="notice" style="display:none"></div>
  <div id="undal-signin-result"></div>
  <button class="button" id="undal-signin" type="button">Sign in</button>
  <button class="button secondary" id="undal-signout" type="button" style="display:none">Sign out on this phone</button>
  <a class="button secondary" href="/checkout">Continue as a guest</a>
  <p class="smallprint">Your password is sent once, over HTTPS, to Firebase Authentication. This site does not keep it, and never asks for it twice.</p>
</section>
<section class="card">
  <h2>What signing in changes</h2>
  <ol class="steps">
    <li class="done"><span class="dot"></span><span>Your name and mobile number come from your Undal profile, so the checkout is two taps shorter.</span></li>
    <li><span class="dot"></span><span>The order appears in the app's order history under the same account.</span></li>
    <li><span class="dot"></span><span>Everything else is unchanged: cash on delivery, one kitchen, the same food.</span></li>
  </ol>
</section>
<script>
(function () {
  var SESSION = ${JSON.stringify(SESSION_STORAGE_KEY)};
  var PROFILE = ${JSON.stringify(PROFILE_STORAGE_KEY)};
  var result = document.getElementById("undal-signin-result");
  var state = document.getElementById("undal-account-state");
  var signOut = document.getElementById("undal-signout");
  var emailBox = document.getElementById("email");
  var passwordBox = document.getElementById("password");

  function read(key) {
    try { return JSON.parse(sessionStorage.getItem(key) || "null"); } catch (error) { return null; }
  }

  var session = read(SESSION);
  if (session && session.idToken && (session.expiresAt || 0) > Date.now()) {
    var profile = read(PROFILE) || {};
    state.style.display = "block";
    state.textContent = "Signed in" + (profile.name ? " as " + profile.name : "") +
      ". Your next order is saved to your Undal account.";
    signOut.style.display = "inline-flex";
  }

  document.getElementById("undal-signin").addEventListener("click", function () {
    var button = this;
    var email = emailBox.value.trim();
    var password = passwordBox.value;
    if (email.length < 5 || password.length < 6) {
      result.innerHTML = '<div class="notice error">Enter the email address and password you use in the Undal app.</div>';
      return;
    }
    button.disabled = true;
    button.textContent = "Signing in\u2026";
    result.innerHTML = "";
    fetch("/api/store/signin", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email: email, password: password })
    })
      .then(function (response) {
        return response.json().then(function (payload) { return { ok: response.ok, payload: payload }; });
      })
      .then(function (outcome) {
        if (!outcome.ok || !outcome.payload.ok) {
          throw new Error(outcome.payload.message || "The sign-in did not go through.");
        }
        try {
          sessionStorage.setItem(SESSION, JSON.stringify({
            idToken: outcome.payload.idToken,
            expiresAt: Date.now() + (outcome.payload.expiresIn || 3600) * 1000
          }));
          sessionStorage.setItem(PROFILE, JSON.stringify(outcome.payload.profile || { name: "", phone: "" }));
        } catch (error) { /* private mode: the checkout makes a fresh session */ }
        result.innerHTML = '<div class="notice ok">Signed in. Taking you to the checkout\u2026</div>';
        window.location.href = "/checkout";
      })
      .catch(function (error) {
        button.disabled = false;
        button.textContent = "Sign in";
        result.innerHTML = '<div class="notice error">' + String(error.message || error) + "</div>";
      });
  });

  signOut.addEventListener("click", function () {
    try {
      sessionStorage.removeItem(SESSION);
      sessionStorage.removeItem(PROFILE);
    } catch (error) { /* nothing to clear */ }
    window.location.reload();
  });
})();
</script>`;

  return renderPage({
    title: "Sign in — Undal HomeKitchen",
    description: "Sign in with the Undal account you use in the app, or order as a guest.",
    body,
    nav: nav("account"),
    cacheSeconds: 0,
  });
}

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
      (order.totalPaisa / 100).toLocaleString("en-US", { minimumFractionDigits: order.totalPaisa % 100 ? 2 : 0, maximumFractionDigits: 2 }).replace(/^/, "\\u09F3 ");
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
