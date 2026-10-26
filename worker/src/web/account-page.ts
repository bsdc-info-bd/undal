/**
 * The customer's account on the web (P-047 … P-052).
 *
 * Two pages. `/login` is the door: sign in with the account the app uses, create
 * one here, or have a password-reset link emailed. `/account` is the room behind
 * it: the profile, an address book whose entries are chosen on a map, the order
 * history that the same account sees in the app, a password change and the
 * account's own end.
 *
 * Both are server-rendered shells with a small amount of plain JavaScript behind
 * them, in the same style as the rest of this site: no framework, no build step,
 * and a page that still says something true with the script switched off.
 *
 * Nothing here can be reached without the project's web API key, and both pages
 * say so plainly rather than showing buttons that fail.
 */
import type { Env } from "../env";
import { escapeHtml } from "../util/http";
import { CART_SCRIPT, PROFILE_STORAGE_KEY, SESSION_STORAGE_KEY } from "./cart-client";
import type { CouponBoardEntry } from "./coupons";
import { readCookieCart, readCookieSession, type CookieCart } from "./forms";
import { MAP_SCRIPT } from "./map-client";
import { renderPage } from "./layout";
import { canonicalUrl, faqJsonLd, jsonLdScript, organizationJsonLd } from "./seo";
import type { WebStoreConfig } from "./store-data";
import { webMenu } from "./store-data";

type Tab = "menu" | "basket" | "download" | "track" | "account" | "about";

function nav(active: Tab): string {
  const link = (href: string, label: string, key: Tab): string =>
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

/** Every page here carries the cart script, so the basket badge is never wrong. */
function shell(options: {
  title: string;
  description: string;
  body: string;
  script?: string;
  origin: string;
  path: string;
  store?: WebStoreConfig | null;
  jsonLd?: unknown[];
  /** The kitchen's automatic coupons: the basket applies the best one by itself. */
  coupons?: CouponBoardEntry[];
  /** The basket a customer built without a script, from its cookie. */
  serverCart?: CookieCart | null;
}): Response {
  return renderPage({
    title: options.title,
    description: options.description,
    nav: nav("account"),
    cacheSeconds: 0,
    robots: "noindex,follow",
    canonical: canonicalUrl(options.origin, options.path),
    jsonLd: (options.jsonLd ?? []).map(jsonLdScript).join("\n"),
    body: `${options.body}
<div id="undal-flash" class="notice" role="status" aria-live="polite" style="display:none"></div>
<script>
window.UNDAL_CATALOGUE = window.UNDAL_CATALOGUE || {};
window.UNDAL_STORE = ${JSON.stringify({
      perKmPaisa: options.store?.deliveryPerKmAmountPaisa ?? 0,
      radiusKm: options.store?.radiusKm ?? 0,
      minOrderPaisa: options.store?.minOrderAmountPaisa ?? 0,
      lat: options.store?.storeLat ?? 0,
      lng: options.store?.storeLng ?? 0,
      name: options.store?.storeName ?? "",
    }).replace(/</g, "\\u003c")};
window.UNDAL_COUPONS = ${JSON.stringify(options.coupons ?? []).replace(/</g, "\\u003c")};
window.UNDAL_SERVER_CART = ${JSON.stringify(options.serverCart ?? null).replace(/</g, "\\u003c")};
${CART_SCRIPT}
${options.script ?? ""}
</script>`,
  });
}

/** The honest parked state, shown when the project has no web API key. */
function parkedPage(origin: string, heading: string, detail: string): Response {
  return renderPage({
    title: `${heading} — Undal HomeKitchen`,
    description: "Web accounts are switched off; the Undal app holds the account.",
    nav: nav("account"),
    cacheSeconds: 0,
    robots: "noindex,follow",
    canonical: canonicalUrl(origin, "/login"),
    body: `<section class="card"><h2>${escapeHtml(heading)}</h2>
<p class="muted">${escapeHtml(detail)}</p>
<a class="button" href="/checkout">Order as a guest</a>
<a class="button secondary" href="/download">Install the Undal app</a>
<a class="button secondary" href="/store">Back to the menu</a></section>`,
  });
}

// --------------------------------------------------------------------- /login

export async function handleLoginPage(env: Env, request: Request): Promise<Response> {
  const origin = new URL(request.url).origin;
  if (!(env.FIREBASE_WEB_API_KEY ?? "").trim().length) {
    return parkedPage(
      origin,
      "Accounts on the web are switched off right now",
      "The accounts live in Firebase and this site has not been given the key that talks to it yet. The Undal app signs in as usual, and a guest order here works without an account at all.",
    );
  }
  const store = (await webMenu(env).then((menu) => menu.store).catch(() => null)) ?? null;
  const menu = await webMenu(env).catch(() => null);
  const carts = readCookieCart(request);

  // What a form post came back with, and what it had in its fields: a phone with
  // no script gets an answer on the page it lands on, with its own typing kept.
  const url = new URL(request.url);
  const notice = (url.searchParams.get("notice") ?? "").slice(0, 240);
  const noticeKind = url.searchParams.get("kind") === "ok" ? "ok" : "error";
  const values = {
    email: (url.searchParams.get("v_email") ?? "").slice(0, 160),
    name: (url.searchParams.get("v_name") ?? "").slice(0, 80),
    phone: (url.searchParams.get("v_phone") ?? "").slice(0, 24),
  };
  const noticeBlock = notice
    ? `<div class="notice ${noticeKind}" role="status">${escapeHtml(notice)}</div>`
    : "";
  const cookieSession = readCookieSession(request);
  const signedInLine = cookieSession
    ? `<div class="notice ok">This browser is signed in as ${escapeHtml(cookieSession.email || "your Undal account")}. <a href="/account">Open your account</a>.</div>`
    : "";

  const body = `
${noticeBlock}
${signedInLine}
<section class="card account-entry">
  <span class="eyebrow">One account, two doors</span>
  <h2>Your Undal account</h2>
  <p class="muted">The same email and password works in the Undal app and here. An account is optional —
  a guest can order without one — but it is what keeps your name, your number and your pinned address
  from being typed again, and what puts a web order into the history the app shows.</p>
  <div class="tabs js" style="display:none" role="tablist" aria-label="Account">
    <button type="button" role="tab" id="tab-signin" aria-controls="panel-signin" aria-selected="true" class="active" data-panel="signin">Sign in</button>
    <button type="button" role="tab" id="tab-signup" aria-controls="panel-signup" aria-selected="false" data-panel="signup">Create an account</button>
    <button type="button" role="tab" id="tab-reset" aria-controls="panel-reset" aria-selected="false" data-panel="reset">Forgotten password</button>
  </div>

  <div id="panel-signin" role="tabpanel" aria-labelledby="tab-signin" data-panel="signin">
    <h3>Sign in</h3>
    <form method="post" action="/login">
      <input type="hidden" name="mode" value="signin">
      <input type="hidden" name="next" value="/account">
      <label for="signin-email">Email address</label>
    <input id="signin-email" name="email" type="email" autocomplete="email" inputmode="email" placeholder="you@example.com" value="${escapeHtml(values.email)}">
    <label for="signin-password">Password</label>
    <input id="signin-password" name="password" type="password" autocomplete="current-password" placeholder="Your Undal password">
      <div id="signin-result" aria-live="polite"></div>
      <button class="button" id="signin-button" type="submit">Sign in</button>
    </form>
    <a class="button secondary" href="/checkout">Continue as a guest</a>
  </div>

  <div id="panel-signup" role="tabpanel" aria-labelledby="tab-signup" data-panel="signup">
    <h3>Create an account</h3>
    <form method="post" action="/login">
      <input type="hidden" name="mode" value="signup">
      <input type="hidden" name="next" value="/checkout">
      <label for="signup-name">Full name</label>
    <input id="signup-name" name="name" autocomplete="name" placeholder="Rahim Uddin" value="${escapeHtml(values.name)}">
    <p class="field-note">Letters, three or more — this is the name the kitchen calls out.</p>
    <label for="signup-phone">Mobile number</label>
    <input id="signup-phone" name="phone" inputmode="numeric" autocomplete="tel" placeholder="01712345678" value="${escapeHtml(values.phone)}">
    <p class="field-note">A Bangladeshi mobile. The rider calls it; it is never shown to anybody else.</p>
    <label for="signup-email">Email address</label>
    <input id="signup-email" name="email" type="email" autocomplete="email" inputmode="email" placeholder="you@example.com" value="${escapeHtml(values.email)}">
    <label for="signup-password">Password</label>
    <input id="signup-password" name="password" type="password" autocomplete="new-password" placeholder="At least six characters">
    <div class="password-meter" aria-hidden="true"><span id="signup-meter"></span></div>
    <p class="field-note" id="signup-strength">Six characters at least, and not only numbers.</p>
    <label for="signup-confirm">Type the password again</label>
    <input id="signup-confirm" name="confirm" type="password" autocomplete="new-password" placeholder="The same password">
      <div id="signup-result" aria-live="polite"></div>
      <button class="button" id="signup-button" type="submit">Create my Undal account</button>
    </form>
    <p class="smallprint">Your password goes once, over HTTPS, to Firebase Authentication. This site never stores it and never asks for it twice.</p>
  </div>

  <div id="panel-reset" role="tabpanel" aria-labelledby="tab-reset" data-panel="reset">
    <h3>Forgotten password</h3>
    <form method="post" action="/login">
      <input type="hidden" name="mode" value="reset">
      <label for="reset-email">The email address on your Undal account</label>
    <input id="reset-email" name="email" type="email" autocomplete="email" inputmode="email" placeholder="you@example.com" value="${escapeHtml(values.email)}">
      <div id="reset-result" aria-live="polite"></div>
      <button class="button" id="reset-button" type="submit">Email me a reset link</button>
    </form>
    <p class="smallprint">The link comes from Firebase and expires in about an hour. If the address has no account, nothing arrives and nothing is revealed about it.</p>
  </div>

  <div id="undal-account-state" class="notice" style="display:none"></div>
</section>
<section class="card">
  <h2>What an account changes</h2>
  <ol class="steps">
    <li class="done"><span class="dot"></span><span>Your name, your number and your saved addresses come with you, so the checkout is three taps shorter.</span></li>
    <li><span class="dot"></span><span>An order placed here appears in the Undal app's history under the same account.</span></li>
    <li><span class="dot"></span><span>You can change your password and close the account yourself, from this site.</span></li>
    <li><span class="dot"></span><span>Everything else is unchanged: one kitchen, cash on delivery, the same food at the same price.</span></li>
  </ol>
</section>`;

  const script = `
(function () {
  var SESSION = ${JSON.stringify(SESSION_STORAGE_KEY)};
  var PROFILE = ${JSON.stringify(PROFILE_STORAGE_KEY)};

  function select(name) {
    if (!name) name = "signin";
    for (var i = 0; i < tabs.length; i++) {
      var active = tabs[i].getAttribute("data-panel") === name;
      tabs[i].setAttribute("aria-selected", active ? "true" : "false");
      tabs[i].setAttribute("tabindex", active ? "0" : "-1");
      tabs[i].className = active ? "active" : "";
    }
    for (var j = 0; j < panels.length; j++) {
      if (panels[j].getAttribute("data-panel") === name) panels[j].removeAttribute("hidden");
      else panels[j].setAttribute("hidden", "");
    }
  }
  // The tabs are a convenience on top of three panels that read perfectly well
  // without a script: with no JavaScript every form below is simply on the page,
  // in order, and each of them posts.
  try {
    var tabs = document.querySelectorAll(".tabs [role=tab]");
    for (var i = 0; i < tabs.length; i++) {
      tabs[i].addEventListener("click", function () { select(this.getAttribute("data-panel")); });
      tabs[i].addEventListener("keydown", function (event) {
        var list = document.querySelectorAll(".tabs [role=tab]");
        var index = 0;
        for (var k = 0; k < list.length; k++) if (list[k] === this) index = k;
        var next = index;
        if (event.key === "ArrowRight") next = (index + 1) % list.length;
        else if (event.key === "ArrowLeft") next = (index + list.length - 1) % list.length;
        else if (event.key === "Home") next = 0;
        else if (event.key === "End") next = list.length - 1;
        else if (event.key !== "Enter" && event.key !== " ") return;
        event.preventDefault();
        select(list[next].getAttribute("data-panel"));
        list[next].focus();

      });
    }
    select("signin");
    if (window.location.hash === "#create") select("signup");
    if (window.location.hash === "#reset") select("reset");
    // A form that came back with an error reopens the tab it was posted from.
    var wanted = /[?&]tab=([a-z]+)/.exec(window.location.search || "");
    if (wanted) select(wanted[1]);
  } catch (tabError) {
    // No tabs, no problem: every panel below is on the page and submittable.
  }

  /** A field's text, or "" — one missing box must never stop the other two forms. */
  function field(id) {
    var box = document.getElementById(id);
    return box && typeof box.value === "string" ? box.value.trim() : "";
  }
  function say(host, text, kind) {
    var notice = document.createElement("div");
    notice.className = "notice " + (kind || "");
    notice.setAttribute("role", kind === "error" ? "alert" : "status");
    notice.textContent = text;
    // Emptying a node without replaceChildren, which an older WebView misses.
    while (host.firstChild) host.removeChild(host.firstChild);
    host.appendChild(notice);
  }
  function busy(button, on, label) {
    button.disabled = !!on;
    if (on) { button.dataset.label = button.textContent; button.textContent = label; }
    else if (button.dataset.label) { button.textContent = button.dataset.label; }
  }

  var state = document.getElementById("undal-account-state");
  var account = window.undalAccount ? window.undalAccount() : null;
  if (account) {
    state.style.display = "block";
    state.className = "notice ok";
    state.textContent = "This tab is signed in" + (account.email ? " as " + account.email : "") + ". ";
    var link = document.createElement("a");
    link.href = "/account";
    link.textContent = "Open your account";
    state.appendChild(link);
  }

  // ---- sign in -------------------------------------------------------------
  var signIn = document.getElementById("signin-button");
  // The form element is the mechanism and fetch is the upgrade: the button sits
  // inside a real form that posts to /login, so the tap signs in even if this
  // whole block never runs; it is intercepted only to keep the answer on page.
  var signInForm = signIn ? signIn.form : null;
  if (signInForm) signInForm.addEventListener("submit", function (event) {
    event.preventDefault();
    var host = document.getElementById("signin-result");
    var email = field("signin-email");
    var password = field("signin-password");
    if (email.length < 5 || password.length < 6) {
      say(host, "Enter the email address and password you use in the Undal app.", "error");
      return;
    }
    busy(signIn, true, "Signing in…");
    fetch("/api/store/signin", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email: email, password: password })
    })
      .then(function (response) {
        return response.json().then(function (payload) { return { ok: response.ok, payload: payload }; });
      })
      .then(function (outcome) {
        if (!outcome.ok || !outcome.payload.ok) throw new Error(outcome.payload.message || "The sign-in did not go through.");
        window.undalSaveAccount({
          idToken: outcome.payload.idToken,
          refreshToken: outcome.payload.refreshToken,
          expiresIn: outcome.payload.expiresIn,
          uid: outcome.payload.uid,
          email: outcome.payload.email || email
        });
        if (outcome.payload.profile) window.undalSaveProfile(outcome.payload.profile);
        say(host, "Signed in. Taking you to your account…", "ok");
        window.location.href = "/account";
      })
      .catch(function (error) {
        busy(signIn, false);
        say(host, String(error.message || error), "error");
      });
  });

  // ---- create --------------------------------------------------------------
  var strength = function (password) {
    var score = 0;
    if (password.length >= 6) score += 1;
    if (password.length >= 10) score += 1;
    if (/[0-9]/.test(password) && /[A-Za-z]/.test(password)) score += 1;
    if (/[^A-Za-z0-9]/.test(password)) score += 1;
    return score;
  };
  var meter = document.getElementById("signup-meter");
  var strengthNote = document.getElementById("signup-strength");
  var words = ["Too short", "Six characters at least, and not only numbers.", "That works.", "Strong.", "Very strong."];
  var signupPassword = document.getElementById("signup-password");
  if (signupPassword) signupPassword.addEventListener("input", function () {
    var score = strength(this.value);
    meter.style.width = (score * 25) + "%";
    meter.style.background = score <= 1 ? "#b3261e" : score === 2 ? "#d97930" : "#165b3b";
    strengthNote.textContent = words[score];
  });

  var signUp = document.getElementById("signup-button");
  var signUpForm = signUp ? signUp.form : null;
  if (signUpForm) signUpForm.addEventListener("submit", function (event) {
    event.preventDefault();
    var host = document.getElementById("signup-result");
    var name = field("signup-name");
    var phone = field("signup-phone");
    var email = field("signup-email");
    var password = field("signup-password");
    var confirm = field("signup-confirm");
    if (!window.undalUsableName(name)) { say(host, "Please write your full name — letters, three or more.", "error"); return; }
    if (!window.undalUsablePhone(phone)) { say(host, "Write a Bangladeshi mobile number like 01712345678.", "error"); return; }
    if (email.length < 5 || email.indexOf("@") < 1) { say(host, "That email address is missing something.", "error"); return; }
    if (password.length < 6 || /^[0-9]+$/.test(password)) { say(host, strengthNote.textContent, "error"); return; }
    if (password !== confirm) { say(host, "The two passwords do not match.", "error"); return; }
    busy(signUp, true, "Creating your account…");
    fetch("/api/account/signup", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ name: name, phone: phone, email: email, password: password })
    })
      .then(function (response) {
        return response.json().then(function (payload) { return { ok: response.ok, payload: payload }; });
      })
      .then(function (outcome) {
        if (!outcome.ok || !outcome.payload.ok) throw new Error(outcome.payload.message || "That did not go through.");
        window.undalSaveAccount({
          idToken: outcome.payload.idToken,
          refreshToken: outcome.payload.refreshToken,
          expiresIn: outcome.payload.expiresIn,
          uid: outcome.payload.uid,
          email: outcome.payload.email || email
        });
        window.undalSaveProfile(outcome.payload.profile || { name: name, phone: window.undalUsablePhone(phone), address: null });
        say(host, "Your account is created. Taking you to it…", "ok");
        window.location.href = "/account";
      })
      .catch(function (error) {
        busy(signUp, false);
        say(host, String(error.message || error), "error");
      });
  });

  // ---- reset ---------------------------------------------------------------
  var reset = document.getElementById("reset-button");
  var resetForm = reset ? reset.form : null;
  if (resetForm) resetForm.addEventListener("submit", function (event) {
    event.preventDefault();
    var host = document.getElementById("reset-result");
    var email = field("reset-email");
    if (email.length < 5) { say(host, "Write the email address on your Undal account.", "error"); return; }
    busy(reset, true, "Sending the link…");
    fetch("/api/account/reset", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email: email })
    })
      .then(function (response) {
        return response.json().then(function (payload) { return { ok: response.ok, payload: payload }; });
      })
      .then(function (outcome) {
        busy(reset, false);
        if (!outcome.ok || !outcome.payload.ok) throw new Error(outcome.payload.message || "That did not go through.");
        say(host, outcome.payload.message || "The reset link is on its way.", "ok");
      })
      .catch(function (error) {
        busy(reset, false);
        say(host, String(error.message || error), "error");
      });
  });
})();`;

  return shell({
    title: "Sign in or create your account — Undal HomeKitchen",
    description:
      "Sign in with the Undal account you use in the app, create one, or have a password-reset link emailed. Guest checkout works without an account.",
    body,
    script,
    origin,
    path: "/login",
    store,
    coupons: menu?.coupons ?? [],
    serverCart: carts,
    jsonLd: [organizationJsonLd(origin, store), faqJsonLd(origin, store)],
  });
}

// ------------------------------------------------------------------- /account

export async function handleAccountPage(env: Env, request: Request): Promise<Response> {
  const origin = new URL(request.url).origin;
  if (!(env.FIREBASE_WEB_API_KEY ?? "").trim().length) {
    return parkedPage(
      origin,
      "Accounts on the web are switched off right now",
      "This site has not been given the Firebase key that talks to your account yet. The Undal app manages it as usual, and a guest order here works without one.",
    );
  }
  const menu = await webMenu(env).catch(() => null);
  const store = menu?.store ?? null;
  const carts = readCookieCart(request);
  const storePin = { lat: store?.storeLat ?? 0, lng: store?.storeLng ?? 0, radiusKm: store?.radiusKm ?? 0 };

  const body = `
<noscript><section class="card"><h2>Your account needs JavaScript here</h2>
<p>Private profile and order-history panels need JavaScript. You can still sign in with the plain forms and order as a guest.</p>
<a class="button" href="/login">Open sign-in forms</a><a class="button secondary" href="/store">Browse the menu</a></section></noscript>
<section class="card account-summary" id="account-hero" role="status" aria-live="polite">
  <span class="eyebrow" id="account-eyebrow">Your account</span>
  <h2 id="account-heading">One moment…</h2>
  <p class="muted" id="account-subheading">Reading the session on this phone.</p>
  <div id="account-actions"></div>
</section>

<div id="account-body" class="account-workspace" hidden>
  <div class="tabs" role="tablist" aria-label="Your account">
    <button type="button" role="tab" id="tab-profile" aria-controls="panel-profile" aria-selected="true" class="active" data-panel="profile">Profile</button>
    <button type="button" role="tab" id="tab-addresses" aria-controls="panel-addresses" aria-selected="false" data-panel="addresses">Addresses</button>
    <button type="button" role="tab" id="tab-orders" aria-controls="panel-orders" aria-selected="false" data-panel="orders">Orders</button>
    <button type="button" role="tab" id="tab-security" aria-controls="panel-security" aria-selected="false" data-panel="security">Security</button>
  </div>

  <section class="card" id="panel-profile" role="tabpanel" aria-labelledby="tab-profile" data-panel="profile">
    <h2>Who the kitchen calls out</h2>
    <p class="muted">The same profile the Undal app uses. Change it here and it changes there.</p>
    <label for="profile-name">Full name</label>
    <input id="profile-name" autocomplete="name" placeholder="Rahim Uddin">
    <label for="profile-phone">Mobile number</label>
    <input id="profile-phone" inputmode="numeric" autocomplete="tel" placeholder="01712345678">
    <p class="field-note" id="profile-email"></p>
    <div id="profile-result" aria-live="polite"></div>
    <button class="button" id="profile-save" type="button">Save my profile</button>
    <p class="smallprint">Your Undal profile is created the first time you save it with a pinned address. Until then your name
    and number are kept on this phone and sent with each order.</p>
  </section>

  <section class="card" id="panel-addresses" role="tabpanel" aria-labelledby="tab-addresses" data-panel="addresses" hidden>
    <h2>Where the rider goes</h2>
    <p class="muted">Each address is a pin you ticked on the map, not a guess from a GPS fix. Choose one at the checkout and the form arrives filled.</p>
    <ul class="saved-addresses" id="address-list"></ul>
    <div id="address-result" aria-live="polite"></div>
    <button class="button secondary" id="address-new" type="button">Add a new address</button>

    <div id="address-editor" hidden>
      <h3 style="margin-top:22px">Drop the pin</h3>
      <p class="muted">Move the map until the green pin is on your door, tick it, then check the three lines below — the landmark is the part riders actually use.</p>
      <div id="undal-map"></div>
      <p class="undal-map-attribution">Tiles © OpenStreetMap contributors.</p>
      <label for="address-label">A name for it</label>
      <input id="address-label" placeholder="Home, office, ammu's house">
      <div class="auth-split">
        <div>
          <label for="address-title">Area or neighbourhood</label>
          <input id="address-title" placeholder="Zindabazar, Sylhet">
        </div>
        <div>
          <label for="address-desc">House, road and floor</label>
          <input id="address-desc" placeholder="House 14, Road 3, first floor">
        </div>
      </div>
      <label for="address-note">One landmark the rider can ask for</label>
      <input id="address-note" placeholder="Beside the mosque, opposite the tea stall">
      <button class="button" id="address-save" type="button">Save this address</button>
      <button class="button secondary" id="address-cancel" type="button">Cancel</button>
    </div>
  </section>

  <section class="card" id="panel-orders" role="tabpanel" aria-labelledby="tab-orders" data-panel="orders" hidden>
    <h2>Your orders</h2>
    <p class="muted">Every order this account has placed, from the web and from the app. The tracking link is the key: anybody holding it can watch that one order.</p>
    <ul class="history-list" id="order-list"></ul>
    <div id="order-result" aria-live="polite"></div>
    <a class="button secondary" href="/store">Order something else</a>
  </section>

  <section class="card" id="panel-security" role="tabpanel" aria-labelledby="tab-security" data-panel="security" hidden>
    <h2>Password</h2>
    <p class="muted">Changing it here changes it in the app. Firebase asks for a fresh sign-in if this tab has been open a long while.</p>
    <label for="password-new">New password</label>
    <input id="password-new" type="password" autocomplete="new-password" placeholder="At least six characters">
    <label for="password-confirm">Type it again</label>
    <input id="password-confirm" type="password" autocomplete="new-password" placeholder="The same password">
    <div id="password-result" aria-live="polite"></div>
    <button class="button" id="password-save" type="button">Change my password</button>

    <h2 style="margin-top:28px">This device</h2>
    <p class="muted">Signing out clears the session and the cached profile from this browser only. Your account and your orders stay exactly as they are.</p>
    <button class="button secondary" id="signout-button" type="button">Sign out on this phone</button>

    <h2 style="margin-top:28px">Close the account</h2>
    <p class="muted">This deletes your Firebase login and your Undal profile — your name, your number and your saved addresses.
    Orders you have already placed stay with the kitchen, because they are its record of food it cooked and delivered. It cannot be undone.</p>
    <label for="close-word">Type <code id="close-word-shown"></code> to confirm</label>
    <input id="close-word" autocomplete="off" spellcheck="false">
    <div id="close-result" aria-live="polite"></div>
    <button class="button secondary" id="close-button" type="button" style="color:#8c1d18;border-color:#f3c6c2">Close my Undal account</button>
  </section>
</div>

<section class="card" id="account-signed-out" hidden>
  <h2>You are not signed in on this phone</h2>
  <p class="muted">Nothing is broken — a guest can order from Undal without an account at all. Signing in keeps your name, your
  number and your pinned addresses from being typed again, and puts your web orders into the history the app shows.</p>
  <a class="button" href="/login">Sign in or create an account</a>
  <a class="button secondary" href="/store">Order as a guest</a>
</section>`;

  const script = `${MAP_SCRIPT}
(function () {
  var CLOSE_WORD = ${JSON.stringify("CLOSE_MY_UNDAL_ACCOUNT")};
  var storePin = ${JSON.stringify(storePin)};
  var money = window.undalMoney;
  var map = null;
  var mapPin = null;
  var mapInfo = {};
  var editingId = "";
  var profile = { name: "", phone: "", address: null };

  function say(host, text, kind) {
    if (!host) return;
    while (host.firstChild) host.removeChild(host.firstChild);
    if (!text) return;
    var notice = document.createElement("div");
    notice.className = "notice " + (kind || "");
    notice.setAttribute("role", kind === "error" ? "alert" : "status");
    notice.textContent = text;
    host.appendChild(notice);
  }
  function busy(button, on, label) {
    if (!button) return;
    button.disabled = !!on;
    if (on) { button.dataset.label = button.textContent; button.textContent = label; }
    else if (button.dataset.label) { button.textContent = button.dataset.label; }
  }
  function escapeText(value) {
    return String(value == null ? "" : value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }
  function select(name) {
    var tabs = document.querySelectorAll(".tabs [role=tab]");
    var panels = document.querySelectorAll("[role=tabpanel]");
    for (var i = 0; i < tabs.length; i++) {
      var active = tabs[i].getAttribute("data-panel") === name;
      tabs[i].setAttribute("aria-selected", active ? "true" : "false");
      tabs[i].setAttribute("tabindex", active ? "0" : "-1");
      tabs[i].className = active ? "active" : "";
    }
    for (var j = 0; j < panels.length; j++) {
      if (panels[j].getAttribute("data-panel") === name) panels[j].removeAttribute("hidden");
      else panels[j].setAttribute("hidden", "");
    }
  }
  var tabButtons = document.querySelectorAll(".tabs [role=tab]");
  for (var t = 0; t < tabButtons.length; t++) {
    tabButtons[t].addEventListener("click", function () {
      select(this.getAttribute("data-panel"));
      if (this.getAttribute("data-panel") === "addresses" && map) map.redraw();
    });
    tabButtons[t].addEventListener("keydown", function (event) {
        var list = document.querySelectorAll(".tabs [role=tab]");
        var index = 0;
        for (var k = 0; k < list.length; k++) if (list[k] === this) index = k;
        var next = index;
        if (event.key === "ArrowRight") next = (index + 1) % list.length;
        else if (event.key === "ArrowLeft") next = (index + list.length - 1) % list.length;
        else if (event.key === "Home") next = 0;
        else if (event.key === "End") next = list.length - 1;
        else if (event.key !== "Enter" && event.key !== " ") return;
        event.preventDefault();
        select(list[next].getAttribute("data-panel"));
        list[next].focus();
      if (list[next].getAttribute("data-panel") === "addresses" && map) map.redraw();
    });
  }
  select("profile");

  var account = window.undalAccount ? window.undalAccount() : null;

  /**
   * A session this tab does not have but the browser does: signing in through
   * the plain form on a phone with no script leaves the session in an HttpOnly
   * cookie, and this is where the scripted page picks it up. One cookie, both
   * halves of the site, no surprise sign-outs.
   */
  function adoptCookieSession() {
    if (window.undalHasAccount && window.undalHasAccount()) return Promise.resolve(window.undalAccount());
    return fetch("/api/account/session", { headers: { accept: "application/json" } })
      .then(function (response) { return response.ok ? response.json() : null; })
      .then(function (payload) {
        if (!payload || !payload.ok || !payload.idToken) return null;
        window.undalSaveAccount(payload);
        return window.undalAccount();
      })
      .catch(function () { return null; });
  }

  function signedOut() {
    document.getElementById("account-heading").textContent = "You are not signed in";
    document.getElementById("account-subheading").textContent = "This tab has no Undal session. Sign in, or order as a guest — both work.";
    document.getElementById("account-signed-out").removeAttribute("hidden");
  }

  if (!account) {
    adoptCookieSession().then(function (adopted) {
      if (!adopted) { signedOut(); return; }
      window.location.reload();
    });
    return;
  }
  document.getElementById("account-body").removeAttribute("hidden");
  document.getElementById("account-heading").textContent = account.email || "Your Undal account";
  document.getElementById("account-subheading").textContent = "Signed in on this phone. Everything below belongs to you alone.";
  document.getElementById("close-word-shown").textContent = CLOSE_WORD;

  // ---- profile -------------------------------------------------------------
  function loadProfile() {
    return window.undalApi("/api/account/profile").then(function (payload) {
      profile = payload.profile || profile;
      document.getElementById("profile-name").value = profile.name || "";
      document.getElementById("profile-phone").value = profile.phone || "";
      document.getElementById("profile-email").textContent =
        (payload.email || account.email || "") + (payload.emailVerified ? " · verified" : " · not verified yet");
      if (payload.vendor) {
        say(document.getElementById("profile-result"), "This is the kitchen's own account. The vendor portal in the app manages it.", "");
      }
      renderAddresses(payload.addresses || []);
      return payload;
    });
  }

  var saveProfile = document.getElementById("profile-save");
  saveProfile.addEventListener("click", function () {
    var host = document.getElementById("profile-result");
    var name = document.getElementById("profile-name").value.trim();
    var phone = document.getElementById("profile-phone").value.trim();
    if (!window.undalUsableName(name)) { say(host, "Please write your full name — letters, three or more.", "error"); return; }
    if (!window.undalUsablePhone(phone)) { say(host, "Write a Bangladeshi mobile number like 01712345678.", "error"); return; }
    busy(saveProfile, true, "Saving…");
    window.undalApi("/api/account/profile", {
      method: "POST",
      body: { name: name, phone: phone, address: profile.address || undefined }
    })
      .then(function (payload) {
        busy(saveProfile, false);
        window.undalSaveProfile({ name: name, phone: window.undalUsablePhone(phone), address: profile.address });
        say(host, payload.needsAddress
          ? "Saved on this phone. Add an address with a ticked pin and your Undal profile is created."
          : (payload.message || "Your Undal profile is saved."), "ok");
        return loadProfile();
      })
      .catch(function (error) {
        busy(saveProfile, false);
        say(host, error.hint ? String(error.message) + " " + String(error.hint) : String(error.message || error), "error");
      });
  });

  /** Empties a node the long way: an older WebView has no replaceChildren. */
  function clearNode(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  // ---- addresses -----------------------------------------------------------
  var list = document.getElementById("address-list");
  function renderAddresses(addresses) {
    clearNode(list);
    if (!addresses.length) {
      var empty = document.createElement("li");
      empty.className = "field-note";
      empty.textContent = "No saved addresses yet. Add one and the checkout arrives with the pin already ticked.";
      list.appendChild(empty);
      return;
    }
    addresses.forEach(function (address) {
      var row = document.createElement("li");
      row.className = "saved-address";
      var marker = document.createElement("div");
      marker.className = "marker";
      marker.textContent = (address.label || address.title || "A").trim().charAt(0).toUpperCase();
      marker.setAttribute("aria-hidden", "true");
      var text = document.createElement("div");
      var heading = document.createElement("h3");
      heading.textContent = address.label || address.title || "Saved address";
      var detail = document.createElement("p");
      detail.textContent = [address.title, address.desc, address.note].filter(Boolean).join(" · ");
      var pin = document.createElement("p");
      pin.className = "field-note";
      pin.textContent = "Pin " + Number(address.lat).toFixed(5) + ", " + Number(address.lng).toFixed(5);
      text.appendChild(heading);
      text.appendChild(detail);
      text.appendChild(pin);
      var actions = document.createElement("div");
      actions.className = "actions";
      var use = document.createElement("button");
      use.type = "button";
      use.className = "mini-button";
      use.textContent = "Use at checkout";
      use.addEventListener("click", function () {
        profile.address = { lat: address.lat, lng: address.lng, title: address.title, desc: address.desc, note: address.note };
        window.undalSaveProfile({ name: profile.name, phone: profile.phone, address: profile.address });
        window.undalFlash("That address will be waiting at the checkout.");
      });
      var edit = document.createElement("button");
      edit.type = "button";
      edit.className = "mini-button";
      edit.textContent = "Edit";
      edit.addEventListener("click", function () { openEditor(address); });
      var remove = document.createElement("button");
      remove.type = "button";
      remove.className = "mini-button danger";
      remove.textContent = "Remove";
      remove.addEventListener("click", function () { removeAddress(address, remove); });
      actions.appendChild(use);
      actions.appendChild(edit);
      actions.appendChild(remove);
      row.appendChild(marker);
      row.appendChild(text);
      row.appendChild(actions);
      list.appendChild(row);
    });
  }

  function removeAddress(address, button) {
    busy(button, true, "Removing…");
    window.undalApi("/api/account/addresses/" + encodeURIComponent(address.id), { method: "DELETE" })
      .then(function () {
        busy(button, false);
        say(document.getElementById("address-result"), "That address is gone.", "ok");
        return loadProfile();
      })
      .catch(function (error) {
        busy(button, false);
        say(document.getElementById("address-result"), String(error.message || error), "error");
      });
  }

  var editor = document.getElementById("address-editor");
  function buildMap() {
    if (map) return map;
    map = window.undalMap({
      mount: "undal-map",
      store: storePin.lat ? storePin : null,
      perKmPaisa: (window.UNDAL_STORE || {}).perKmPaisa || 0,
      confirmLabel: "Tick this pin as the address",
      onPin: function (point, info) { mapPin = point; mapInfo = info || mapInfo; },
      onConfirm: function (point, info) {
        mapPin = point;
        mapInfo = info || mapInfo;
        var title = document.getElementById("address-title");
        var desc = document.getElementById("address-desc");
        if (mapInfo.area && !title.value) {
          title.value = mapInfo.city && mapInfo.city !== mapInfo.area ? mapInfo.area + ", " + mapInfo.city : mapInfo.area;
        }
        if (mapInfo.road && !desc.value) desc.value = mapInfo.road;
      }
    });
    return map;
  }

  function openEditor(address) {
    editingId = address && address.id ? address.id : "";
    document.getElementById("address-label").value = address ? (address.label || "") : "";
    document.getElementById("address-title").value = address ? (address.title || "") : "";
    document.getElementById("address-desc").value = address ? (address.desc || "") : "";
    document.getElementById("address-note").value = address ? (address.note || "") : "";
    editor.removeAttribute("hidden");
    select("addresses");
    var built = buildMap();
    if (built) {
      if (address && address.lat && address.lng) { built.setPin(address.lat, address.lng, 17); built.confirm(); }
      else if (profile.address && profile.address.lat) { built.setPin(profile.address.lat, profile.address.lng, 17); built.confirm(); }
      else if (storePin.lat) { built.setPin(storePin.lat, storePin.lng, 15); }
      built.redraw();
    }
    editor.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  document.getElementById("address-new").addEventListener("click", function () { openEditor(null); });
  document.getElementById("address-cancel").addEventListener("click", function () {
    editor.setAttribute("hidden", "");
    editingId = "";
  });

  var saveAddress = document.getElementById("address-save");
  saveAddress.addEventListener("click", function () {
    var host = document.getElementById("address-result");
    if (!mapPin) { say(host, "Move the map and tick the pin first — the rider is sent to exactly that point.", "error"); return; }
    if (!map || !map.isConfirmed()) { say(host, "Tick the pin to confirm it. That is the point the delivery fee is measured from.", "error"); return; }
    var payload = {
      label: document.getElementById("address-label").value.trim(),
      title: document.getElementById("address-title").value.trim(),
      desc: document.getElementById("address-desc").value.trim(),
      note: document.getElementById("address-note").value.trim(),
      lat: mapPin.lat,
      lng: mapPin.lng
    };
    if (!window.undalUsableText(payload.title) || !window.undalUsableText(payload.desc) || !window.undalUsableText(payload.note)) {
      say(host, "Fill in the area, the house and road, and one landmark the rider can ask for.", "error");
      return;
    }
    busy(saveAddress, true, "Saving…");
    window.undalApi("/api/account/addresses", { method: "POST", body: { id: editingId, address: payload } })
      .then(function () {
        busy(saveAddress, false);
        say(host, "Saved. That address is ready to choose at the checkout.", "ok");
        editor.setAttribute("hidden", "");
        editingId = "";
        if (!profile.address) {
          profile.address = payload;
          window.undalSaveProfile({ name: profile.name, phone: profile.phone, address: profile.address });
        }
        return loadProfile();
      })
      .catch(function (error) {
        busy(saveAddress, false);
        say(host, String(error.message || error), "error");
      });
  });

  // ---- orders --------------------------------------------------------------
  var orderList = document.getElementById("order-list");
  var statusWord = {
    placed: "Placed", accepted: "Cooking", ready: "Ready", in_delivery: "On the way",
    delivered: "Delivered", cancelled: "Cancelled"
  };
  function loadOrders() {
    say(document.getElementById("order-result"), "Reading your orders…", "");
    window.undalApi("/api/account/orders")
      .then(function (payload) {
        say(document.getElementById("order-result"), "");
        clearNode(orderList);
        if (!payload.orders.length) {
          var empty = document.createElement("li");
          empty.className = "field-note";
          empty.textContent = "No orders on this account yet. The menu is one tap away.";
          orderList.appendChild(empty);
          return;
        }
        payload.orders.forEach(function (order) {
          var row = document.createElement("li");
          row.className = "history-row";
          var dot = document.createElement("span");
          dot.className = "status-dot " + order.status;
          var who = document.createElement("div");
          var title = document.createElement("div");
          title.className = "who";
          title.textContent = (order.shortId || "Order") + " · " + (statusWord[order.status] || order.status);
          var when = document.createElement("div");
          when.className = "when";
          when.textContent = order.createdAt
            ? new Date(order.createdAt).toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short" })
            : "";
          who.appendChild(title);
          who.appendChild(when);
          var amount = document.createElement("span");
          amount.className = "amount";
          amount.textContent = money(order.totalPaisa);
          var link = document.createElement("a");
          link.className = "mini-button";
          link.href = "/order/" + encodeURIComponent(order.id);
          link.textContent = "Track";
          row.appendChild(dot);
          row.appendChild(who);
          row.appendChild(amount);
          row.appendChild(link);
          orderList.appendChild(row);
        });
        if (payload.more) {
          var note = document.createElement("li");
          note.className = "field-note";
          note.textContent = "The newest " + payload.orders.length + " orders are shown here; the app keeps the whole history.";
          orderList.appendChild(note);
        }
      })
      .catch(function (error) {
        say(document.getElementById("order-result"), String(error.message || error), "error");
      });
  }
  loadOrders();

  // ---- security ------------------------------------------------------------
  var changePassword = document.getElementById("password-save");
  changePassword.addEventListener("click", function () {
    var host = document.getElementById("password-result");
    var password = document.getElementById("password-new").value;
    var confirm = document.getElementById("password-confirm").value;
    if (password.length < 6 || /^[0-9]+$/.test(password)) { say(host, "Use at least six characters, and not only numbers.", "error"); return; }
    if (password !== confirm) { say(host, "The two passwords do not match.", "error"); return; }
    busy(changePassword, true, "Changing…");
    window.undalApi("/api/account/password", { method: "POST", body: { password: password } })
      .then(function (payload) {
        busy(changePassword, false);
        if (payload.idToken) {
          window.undalSaveAccount({
            idToken: payload.idToken, refreshToken: payload.refreshToken,
            expiresIn: payload.expiresIn, uid: payload.uid, email: account.email
          });
        }
        document.getElementById("password-new").value = "";
        document.getElementById("password-confirm").value = "";
        say(host, payload.message || "Your password is changed.", "ok");
      })
      .catch(function (error) {
        busy(changePassword, false);
        say(host, error.hint ? String(error.message) + " " + String(error.hint) : String(error.message || error), "error");
      });
  });

  document.getElementById("signout-button").addEventListener("click", function () {
    window.undalDropAccount();
    window.location.href = "/login";
  });

  var close = document.getElementById("close-button");
  close.addEventListener("click", function () {
    var host = document.getElementById("close-result");
    var word = document.getElementById("close-word").value.trim();
    if (word !== CLOSE_WORD) { say(host, "Type the confirmation word exactly as it is shown, and the button will do what it says.", "error"); return; }
    busy(close, true, "Closing the account…");
    window.undalApi("/api/account/close", { method: "POST", body: { confirmation: CLOSE_WORD } })
      .then(function (payload) {
        busy(close, false);
        window.undalDropAccount();
        say(host, payload.message || "Your account is closed.", "ok");
        window.setTimeout(function () { window.location.href = "/store"; }, 2400);
      })
      .catch(function (error) {
        busy(close, false);
        say(host, error.hint ? String(error.message) + " " + String(error.hint) : String(error.message || error), "error");
      });
  });

  loadProfile().catch(function (error) {
    say(document.getElementById("profile-result"),
      error.hint ? String(error.message) + " " + String(error.hint) : String(error.message || error), "error");
  });
  window.undalRender();
})();`;

  return shell({
    title: "Your account — Undal HomeKitchen",
    description:
      "Manage your Undal profile, your saved delivery addresses, your order history, your password and your account itself.",
    body,
    script,
    origin,
    path: "/account",
    coupons: menu?.coupons ?? [],
    serverCart: carts,
    store,
  });
}
