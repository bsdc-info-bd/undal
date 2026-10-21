/**
 * The browser-side cart.
 *
 * One small script, served inline on every store page. The cart lives in
 * `localStorage` as `[{id, qty, kind}]` and holds no prices: the server prices
 * the order at checkout from live data, so a visitor editing storage can only
 * change what they *ask* for, never what they pay.
 *
 * Three things in here exist because the storefront has to work on *every*
 * phone, not on the developer's phone
 * ------------------------------------------------------------------------
 *
 * 1. **The script is written in the oldest dialect a browser can still run.**
 *    A phone on an older Android WebView — or an in-app browser that ships its
 *    own engine — used to hit one unsupported token (`?.`, `??`, `catch {}`,
 *    `replaceChildren`, `padStart`, array spread) and stop parsing the whole
 *    block. Every interactive control on the page then did nothing: sign in, add
 *    to basket, continue as a guest, the lot. That is exactly the class of bug
 *    that "works on my tablet" and dies on a phone, so this file uses `var`,
 *    `function`, no arrow functions, no optional chaining, no nullish
 *    coalescing, no `catch {}` without a binding, no spread and no
 *    `String.prototype.padStart`. `tools/check-web-compat.mjs` fails the build if
 *    that ever regresses.
 *
 * 2. **Nothing depends on this script to work.** The first statement sets
 *    `html.js`; the page's CSS shows the JavaScript control only when that class
 *    is present (`html.js .js-only`) and shows the plain-HTML form otherwise
 *    (`html.nojs .nojs-only`, and `nojs` is what a document has when this script
 *    never ran). If this file dies for any reason at all, the page still adds to
 *    the basket and still checks out — through the server.
 *
 * 3. **The server cart is merged, never overwritten.** A form post that the
 *    script could not intercept leaves the basket in a cookie, and the next page
 *    carries it here in `window.UNDAL_SERVER_CART` with a revision number. Each
 *    revision is folded in with `max(local, server)` per line — the highest
 *    quantity the customer asked for, applied once — so the two carts can never
 *    double an order.
 *
 * And the coupons: the board published by the kitchen arrives in
 * `window.UNDAL_COUPONS`, and the best one that helps the basket is applied by
 * itself. The arithmetic below is the same arithmetic as
 * `worker/src/web/coupons.ts` and the app's `CouponMath.kt`.
 */

export const CART_STORAGE_KEY = "undal_cart_v1";
export const SESSION_STORAGE_KEY = "undal_web_session_v1";
export const SERVER_CART_REV_KEY = "undal_server_cart_rev_v1";

/**
 * The customer's own name and phone, cached for the checkout form after a sign-in
 * (P-013). It is the same pair the app keeps in `users/{uid}`; nothing else from
 * the profile is ever fetched.
 */
export const PROFILE_STORAGE_KEY = "undal_web_profile_v1";

export const CART_SCRIPT = `
(function () {
  var CART_KEY = ${JSON.stringify(CART_STORAGE_KEY)};
  var SESSION_KEY = ${JSON.stringify(SESSION_STORAGE_KEY)};
  var PROFILE_KEY = ${JSON.stringify(PROFILE_STORAGE_KEY)};
  var REV_KEY = ${JSON.stringify(SERVER_CART_REV_KEY)};

  // ---- 1. this document runs JavaScript ------------------------------------
  // The page is written for a browser without JavaScript first: the plain
  // <form> controls are the ones in the HTML, and the scripted ones carry an
  // inline display:none. This is the switch. If this block fails to parse —
  // the exact failure that made a phone's sign-in and basket do nothing while a
  // tablet's worked — nothing is hidden and nothing is missing, because the
  // server-rendered forms are what is on the page.
  try {
    document.documentElement.className = (document.documentElement.className || "").replace(/(^|\\s)nojs(\\s|$)/, " ") + " js";
    var plain = document.querySelectorAll(".nojs");
    for (var plainIndex = 0; plainIndex < plain.length; plainIndex++) plain[plainIndex].style.display = "none";
    var scripted = document.querySelectorAll(".js");
    for (var scriptedIndex = 0; scriptedIndex < scripted.length; scriptedIndex++) scripted[scriptedIndex].style.display = "";
  } catch (error) { /* nothing to show without a DOM; the forms still work */ }

  function write(key, value) { try { localStorage.setItem(key, value); } catch (error) { } }
  function read(key) { try { return localStorage.getItem(key); } catch (error) { return null; } }
  function remove(key) { try { localStorage.removeItem(key); } catch (error) { } }
  function writeTab(key, value) { try { sessionStorage.setItem(key, value); } catch (error) { } }
  function readTab(key) { try { return sessionStorage.getItem(key); } catch (error) { return null; } }

  /**
   * A letter test that cannot break the parse.
   *
   * The honest rule is the app's: one letter (any script), a space, a dot or a
   * dash. That is a Unicode property escape — and a browser that shipped before
   * them treats the *literal* as a syntax error, which used to take this entire
   * script down with it, including sign-in and the basket. Built from a string
   * instead, an engine that cannot compile it falls back to a range that still
   * covers Latin and Bengali, and keeps working.
   */
  var NAME_LETTER = (function () {
    try { return new RegExp("[\\\\p{L} .-]", "u"); } catch (error) { return /[A-Za-z\\u00C0-\\u024F\\u0980-\\u09FF .-]/; }
  })();

  // ---- catalogue, coupons and store, embedded by the page ------------------
  window.UNDAL_CATALOGUE = window.UNDAL_CATALOGUE || {};
  window.UNDAL_STORE = window.UNDAL_STORE || { perKmPaisa: 0, radiusKm: 0, minOrderPaisa: 0 };
  window.UNDAL_COUPONS = window.UNDAL_COUPONS || [];
  window.UNDAL_SERVER_CART = window.UNDAL_SERVER_CART || null;

  function catalogueEntry(id) { return window.UNDAL_CATALOGUE[id] || null; }

  // ---- 2. the cart ---------------------------------------------------------
  function readCart() {
    try {
      var parsed = JSON.parse(read(CART_KEY) || "[]");
      if (!parsed || typeof parsed.length !== "number") return [];
      var lines = [];
      for (var i = 0; i < parsed.length; i++) {
        var line = parsed[i];
        if (line && line.id && line.qty > 0) lines.push({ id: line.id, qty: line.qty, kind: line.kind || "dish" });
      }
      return lines;
    } catch (error) { return []; }
  }
  function writeCart(lines) {
    write(CART_KEY, JSON.stringify(lines));
    render();
  }
  function lineCount(lines) {
    var list = lines || readCart();
    var total = 0;
    for (var i = 0; i < list.length; i++) total += list[i].qty;
    return total;
  }
  function indexOfLine(lines, id) {
    for (var i = 0; i < lines.length; i++) if (lines[i].id === id) return i;
    return -1;
  }
  function maxQtyFor(id) {
    var entry = catalogueEntry(id);
    return entry && entry.maxQty ? entry.maxQty : 50;
  }

  /**
   * Folds a server cart (a full state, with a revision) into this browser's cart.
   * Each revision is applied at most once, and a line is taken at the *highest*
   * quantity either side knows about — so a form post that added two teas and a
   * later local add of a third tea can only ever mean three teas.
   */
  function mergeServerCart() {
    var server = window.UNDAL_SERVER_CART;
    if (!server || typeof server.rev !== "number") return false;
    var appliedRev = Number(read(REV_KEY) || 0);
    if (!isFinite(appliedRev)) appliedRev = 0;
    if (server.rev <= appliedRev) return false;
    var lines = readCart();
    var incoming = server.lines || [];
    for (var i = 0; i < incoming.length; i++) {
      var line = incoming[i];
      if (!line || !line.id || !(line.qty > 0)) continue;
      var at = indexOfLine(lines, line.id);
      var wanted = Math.min(Math.max(line.qty, at >= 0 ? lines[at].qty : 0), maxQtyFor(line.id));
      if (at >= 0) lines[at].qty = wanted;
      else lines.push({ id: line.id, qty: wanted, kind: line.kind || "dish" });
    }
    write(CART_KEY, JSON.stringify(lines));
    write(REV_KEY, String(server.rev));
    return true;
  }

  window.undalAdd = function (id, qty) {
    var entry = catalogueEntry(id);
    var lines = readCart();
    var at = indexOfLine(lines, id);
    var wanted = (at >= 0 ? lines[at].qty : 0) + (qty || 1);
    wanted = Math.min(wanted, Math.max(1, maxQtyFor(id)));
    if (at >= 0) lines[at].qty = wanted;
    else lines.push({ id: id, qty: wanted, kind: entry && entry.kind ? entry.kind : "dish" });
    writeCart(lines);
    flash((entry && entry.name ? entry.name : "That dish") + " added to your basket");
  };
  window.undalSetQty = function (id, qty) {
    var lines = readCart();
    var next = [];
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (line.id !== id) { next.push(line); continue; }
      var wanted = Math.max(0, Math.min(qty, 50));
      if (wanted > 0) next.push({ id: line.id, qty: wanted, kind: line.kind });
    }
    writeCart(next);
  };
  window.undalRemove = function (id) {
    var lines = readCart();
    var next = [];
    for (var i = 0; i < lines.length; i++) if (lines[i].id !== id) next.push(lines[i]);
    writeCart(next);
  };
  window.undalClear = function () { writeCart([]); };
  window.undalLines = readCart;

  // ---- 3. money, exactly as the app and the worker write it -----------------
  function groupIndian(digits) {
    if (digits.length <= 3) return digits;
    return digits.slice(0, -3).replace(/\\B(?=(\\d{2})+(?!\\d))/g, ",") + "," + digits.slice(-3);
  }
  // The same string the app and the worker produce: ৳ 1,250 · ৳ 1,00,000 · ৳ 250.5
  function money(paisa) {
    var rounded = Math.round(paisa);
    var negative = rounded < 0;
    var value = Math.abs(rounded);
    var whole = groupIndian(String(Math.floor(value / 100)));
    var rest = value % 100;
    var fraction = "";
    if (rest !== 0) {
      var text = String(rest);
      if (text.length < 2) text = "0" + text;
      text = text.replace(/0+$/, "");
      fraction = "." + text;
    }
    return "\\u09F3 " + (negative ? "-" : "") + whole + fraction;
  }
  window.undalMoney = money;

  function padTwo(value) {
    var text = String(value);
    return text.length < 2 ? "0" + text : text;
  }
  window.undalPadTwo = padTwo;

  /** The saved profile, or null for a guest or a browser in private mode. */
  window.undalProfile = function () {
    try { return JSON.parse(readTab(PROFILE_KEY) || "null"); } catch (error) { return null; }
  };

  // ---- 4. coupons: the best one is applied by itself ------------------------
  // One rule, three implementations (this, worker/src/web/coupons.ts and the
  // app's CouponMath.kt); tools/check-coupon-parity.mjs keeps them equal.
  function parseMinutes(text) {
    if (typeof text !== "string") return null;
    var match = /^([01]?\\d|2[0-3]):([0-5]\\d)$/.exec(text.replace(/^\\s+|\\s+$/g, ""));
    if (!match) return null;
    return Number(match[1]) * 60 + Number(match[2]);
  }
  function withinWindow(startsAt, endsAt, minutes) {
    var start = parseMinutes(startsAt), end = parseMinutes(endsAt);
    if (start === null && end === null) return true;
    if (start !== null && end === null) return minutes >= start;
    if (start === null && end !== null) return minutes < end;
    if (start === end) return true;
    if (end > start) return minutes >= start && minutes < end;
    return minutes >= start || minutes < end;
  }
  function discountOf(coupon, subtotal, delivery) {
    var cap = coupon.maxDiscountPaisa > 0 ? Math.trunc(coupon.maxDiscountPaisa) : 0;
    // Free delivery means the fee, and the cap still caps: a coupon that could
    // take ৳300 off a far-away order would be a cap that does not cap.
    if (coupon.type === "freedelivery") {
      var fee = Math.max(0, Math.trunc(delivery || 0));
      return cap > 0 ? Math.min(fee, cap) : fee;
    }
    if (subtotal <= 0) return 0;
    var discount = 0;
    if (coupon.type === "percent") {
      var percent = Math.min(100, Math.max(0, Math.trunc(coupon.value || 0)));
      discount = Math.floor(subtotal * percent / 100);
    } else if (coupon.type === "fixed") {
      discount = Math.max(0, Math.trunc(coupon.value || 0));
    }
    if (cap > 0) discount = Math.min(discount, cap);
    return Math.max(0, Math.min(discount, subtotal));
  }
  /** The Dhaka clock, worked out arithmetically: Bangladesh has no DST, ever. */
  function dhaka(minutesOffset) {
    var shifted = new Date(Date.now() + minutesOffset * 60000);
    var days = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];
    return { weekday: days[shifted.getUTCDay()], minutes: shifted.getUTCHours() * 60 + shifted.getUTCMinutes() };
  }
  /**
   * The same verdict the server will reach, so the basket shows the customer
   * what is really going to happen.
   *
   * The clock is a parameter with the real Dhaka time as its default: the page
   * never passes one, and the parity gate passes the clock from its own vector
   * table so the browser copy can be proven against the app and the Worker at
   * any hour of the day rather than only outside 18:00–22:00.
   */
  window.undalEvaluateCoupon = function (coupon, subtotal, delivery, clockArg) {
    var clock = clockArg || dhaka(360);
    if (!coupon || coupon.active === false) return { eligible: false, discountPaisa: 0, reason: "switched off" };
    if (coupon.type !== "percent" && coupon.type !== "fixed" && coupon.type !== "freedelivery") {
      return { eligible: false, discountPaisa: 0, reason: "not set up correctly" };
    }
    var expires = Number(coupon.expiresAtMillis || coupon.expiresAt || 0);
    // Seconds, not milliseconds, below 1e11 — the same rule as couponExpiryMillis.
    if (expires > 0 && expires < 100000000000) expires = expires * 1000;
    if (expires > 0 && expires <= Date.now()) return { eligible: false, discountPaisa: 0, reason: "expired" };
    var days = coupon.daysOfWeek || [];
    if (days.length > 0) {
      var today = false;
      for (var i = 0; i < days.length; i++) if (String(days[i]).slice(0, 3) === clock.weekday) today = true;
      if (!today) return { eligible: false, discountPaisa: 0, reason: "not today" };
    }
    if (!withinWindow(coupon.startsAt, coupon.endsAt, clock.minutes)) {
      return { eligible: false, discountPaisa: 0, reason: "outside its hours" };
    }
    if (coupon.usageLimit >= 1 && coupon.usedCount >= coupon.usageLimit) {
      return { eligible: false, discountPaisa: 0, reason: "used up" };
    }
    if (coupon.minSubtotalAmountPaisa > 0 && subtotal < coupon.minSubtotalAmountPaisa) {
      return { eligible: false, discountPaisa: 0, reason: "basket too small" };
    }
    var discount = discountOf(coupon, subtotal, delivery);
    if (discount <= 0) return { eligible: false, discountPaisa: 0, reason: "changes nothing here" };
    return { eligible: true, discountPaisa: discount, reason: "" };
  };
  window.undalCouponHeadline = function (coupon) {
    if (!coupon) return "";
    if (coupon.type === "freedelivery") {
      return coupon.maxDiscountPaisa > 0 ? "Free delivery, up to " + money(coupon.maxDiscountPaisa) : "Free delivery";
    }
    if (coupon.type === "percent") {
      var text = Math.min(100, Math.max(0, Math.trunc(coupon.value || 0))) + "% off";
      if (coupon.maxDiscountPaisa > 0) text += ", up to " + money(coupon.maxDiscountPaisa);
      return text;
    }
    return money(coupon.value) + " off";
  };
  window.undalBestCoupon = function (subtotal, delivery) {
    var board = window.UNDAL_COUPONS || [];
    var best = null;
    var bestExpiry = Infinity;
    for (var i = 0; i < board.length; i++) {
      // A coupon the kitchen marked code-only is never applied by itself: it is
      // in the board so the strip can show it, and it is applied only when the
      // customer types it.
      if (board[i].autoApply === false) continue;
      var verdict = window.undalEvaluateCoupon(board[i], subtotal, delivery);
      if (!verdict.eligible) continue;
      var expires = Number(board[i].expiresAtMillis || 0);
      var beats = best === null || verdict.discountPaisa > best.discountPaisa ||
        (verdict.discountPaisa === best.discountPaisa && expires > 0 && expires < bestExpiry);
      if (!beats) continue;
      best = { code: board[i].code, type: board[i].type, value: board[i].value, description: board[i].description || "",
        headline: window.undalCouponHeadline(board[i]), discountPaisa: verdict.discountPaisa };
      bestExpiry = expires > 0 ? expires : Infinity;
    }
    return best;
  };

  window.undalTotals = function (distanceKm) {
    var lines = readCart();
    var subtotal = 0;
    for (var i = 0; i < lines.length; i++) {
      var entry = catalogueEntry(lines[i].id);
      if (entry) subtotal += entry.price * lines[i].qty;
    }
    var store = window.UNDAL_STORE || {};
    var km = typeof distanceKm === "number" && distanceKm > 0 ? distanceKm : 0;
    var delivery = km > 0 && store.perKmPaisa > 0 ? Math.ceil(km) * store.perKmPaisa : 0;
    var coupon = window.undalBestCoupon(subtotal, delivery);
    var discount = coupon ? coupon.discountPaisa : 0;
    return {
      subtotal: Math.round(subtotal),
      delivery: Math.round(delivery),
      discount: Math.round(discount),
      total: Math.max(0, Math.round(subtotal + delivery - discount)),
      coupon: coupon,
      lines: lines
    };
  };

  function flash(message, isError) {
    var host = document.getElementById("undal-flash");
    if (!host) return;
    host.textContent = message;
    host.className = "notice" + (isError ? " error" : " ok");
    host.style.display = "block";
    if (!isError) {
      if (host._timer) window.clearTimeout(host._timer);
      host._timer = window.setTimeout(function () { host.style.display = "none"; }, 2600);
    }
  }
  window.undalFlash = flash;

  function escapeHtml(value) {
    return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }

  function render() {
    var lines = readCart();
    var badge = document.getElementById("undal-cart-count");
    if (badge) {
      var count = lineCount(lines);
      badge.textContent = count ? String(count) : "";
      badge.style.display = count ? "inline-block" : "none";
    }
    var host = document.getElementById("undal-cart-lines");
    if (!host) { renderTotals(lines); return; }
    if (lines.length === 0) {
      host.innerHTML = '<div class="notice">Your basket is empty. <a href="/store">Back to the menu</a>.</div>';
    } else {
      var html = [];
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        var entry = catalogueEntry(line.id) || { name: "Dish", price: 0, kind: "dish" };
        html.push('<div class="row"><div><strong>' + escapeHtml(entry.name) + '</strong><div class="smallprint">' +
          money(entry.price) + ' each</div></div><div class="qty">' +
          '<button type="button" aria-label="One less" onclick="undalSetQty(\\'' + line.id + '\\',' + (line.qty - 1) + ')">\\u2212</button>' +
          '<span>' + line.qty + '</span>' +
          '<button type="button" aria-label="One more" onclick="undalSetQty(\\'' + line.id + '\\',' + (line.qty + 1) + ')">+</button>' +
          '<button type="button" aria-label="Remove" onclick="undalRemove(\\'' + line.id + '\\')">\\u2715</button></div></div>');
      }
      host.innerHTML = html.join("");
    }
    renderTotals(lines);
  }

  function renderTotals(lines) {
    var totals = window.undalTotals(window.UNDAL_DISTANCE_KM);
    var subtotalHost = document.getElementById("undal-subtotal");
    if (subtotalHost) subtotalHost.textContent = money(totals.subtotal);
    var deliveryHost = document.getElementById("undal-delivery");
    if (deliveryHost) {
      deliveryHost.textContent = totals.delivery > 0 ? money(totals.delivery) : "measured at checkout";
    }
    var discountRow = document.getElementById("undal-discount-row");
    var discountHost = document.getElementById("undal-discount");
    if (discountRow && discountHost) {
      var applied = totals.coupon && totals.discount > 0;
      discountRow.style.display = applied ? "flex" : "none";
      if (applied) {
        discountHost.textContent = "\\u2212 " + money(totals.discount) + " (" + totals.coupon.code + ")";
      }
    }
    var totalHost = document.getElementById("undal-total");
    if (totalHost) totalHost.textContent = money(totals.total);
    var couponHost = document.getElementById("undal-auto-coupon");
    if (couponHost) {
      if (totals.coupon && totals.discount > 0) {
        couponHost.style.display = "block";
        couponHost.className = "notice ok";
        couponHost.textContent = totals.coupon.headline + " \\u2014 " + totals.coupon.code +
          " was applied for you. You save " + money(totals.discount) + ".";
      } else {
        couponHost.style.display = "none";
      }
    }
    var min = (window.UNDAL_STORE || {}).minOrderPaisa || 0;
    var warnHost = document.getElementById("undal-minimum");
    if (warnHost) {
      var short = min > 0 && totals.subtotal > 0 && totals.subtotal < min;
      warnHost.style.display = short ? "block" : "none";
      if (short) {
        warnHost.textContent = "The kitchen cooks from " + money(min) + " upwards. " +
          money(min - totals.subtotal) + " more and you are there.";
      }
    }
    var checkout = document.getElementById("undal-checkout");
    if (checkout) checkout.disabled = totals.lines.length === 0 || (min > 0 && totals.subtotal < min);
  }
  window.undalRender = render;

  // ---- 5. the same field rules the app and the worker use -------------------
  // Kept in step by tools/check-web-rules.mjs: these three functions are run
  // through the app's own ValidatorsTest vectors on every push.
  function usablePhone(raw) {
    var digits = String(raw).replace(/\\D/g, "");
    var local = digits.slice(0, 3) === "880" ? "0" + digits.slice(3) : digits;
    return /^01[3-9][0-9]{8}$/.test(local) ? local : null;
  }
  function usableText(raw) {
    return String(raw).replace(/^\\s+|\\s+$/g, "").length >= 3;
  }
  function usableName(raw) {
    var value = String(raw).replace(/^\\s+|\\s+$/g, "");
    if (value.length < 3 || value.length > 80) return false;
    for (var i = 0; i < value.length; i++) {
      if (NAME_LETTER.test(value.charAt(i))) return true;
    }
    return false;
  }
  // All three are published on the window because the checkout and the account page
  // run in their own script block: a validator that only exists inside this IIFE
  // is a ReferenceError at the moment a customer taps "Place the order", which is
  // exactly what the web checkout used to do — nothing at all, silently.
  window.undalUsablePhone = usablePhone;
  window.undalUsableName = usableName;
  window.undalUsableText = usableText;

  // ---- 6. the session: a signed-in account when there is one, a guest otherwise
  function readSession() {
    try { return JSON.parse(readTab(SESSION_KEY) || "null"); } catch (error) { return null; }
  }
  function writeSession(session) {
    writeTab(SESSION_KEY, JSON.stringify(session));
    return session;
  }
  function fresh(session) {
    return !!session && !!session.idToken && (session.expiresAt || 0) > Date.now() + 60000;
  }

  /** An anonymous guest session, created once per tab. */
  function guestSession() {
    return fetch("/api/store/session", { method: "POST" }).then(function (response) {
      return response.json().then(function (payload) {
        if (!response.ok || !payload.ok) throw new Error(payload.message || "Sign-in failed.");
        return writeSession({
          idToken: payload.idToken,
          expiresAt: Date.now() + (payload.expiresIn || 3600) * 1000,
          uid: payload.uid || "",
          guest: true
        });
      });
    });
  }

  /** Renews a signed-in account past its hour, using the refresh token it holds. */
  function renewSession(session) {
    if (!session || !session.refreshToken) return guestSession();
    return fetch("/api/account/refresh", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ refreshToken: session.refreshToken })
    })
      .then(function (response) {
        return response.json().then(function (payload) {
          if (!response.ok || !payload.ok) throw new Error(payload.message || "Your session has ended.");
          return writeSession({
            idToken: payload.idToken,
            refreshToken: payload.refreshToken || session.refreshToken,
            expiresAt: Date.now() + (payload.expiresIn || 3600) * 1000,
            uid: payload.uid || session.uid || "",
            email: session.email || "",
            guest: false
          });
        });
      })
      .catch(function () {
        // A refresh token that no longer works is not a dead end: the basket
        // survives and the customer can order as a guest or sign in again.
        return guestSession();
      });
  }

  /**
   * A valid ID token for whoever is using this tab.
   *
   * One function, two kinds of caller: a guest ordering lunch and an account
   * holder whose order has to land in the same history the app shows. Both get a
   * token that has at least a minute of life left, renewed if it has not.
   */
  window.undalSession = function () {
    var cached = readSession();
    if (fresh(cached)) return Promise.resolve(cached);
    if (cached && cached.refreshToken) return renewSession(cached);
    if (cached && cached.idToken && !cached.guest) return renewSession(cached);
    return guestSession();
  };

  /**
   * A new ID token whatever the cached one claims — for the request the server
   * just refused with 401. An account renews with its refresh token; a guest
   * (or an account that has none) starts a new guest session.
   */
  window.undalRenewSession = function () {
    var cached = readSession();
    if (cached && cached.refreshToken) return renewSession(cached);
    return guestSession();
  };

  /** The signed-in account on this tab, or null for a guest. */
  window.undalAccount = function () {
    var session = readSession();
    if (!session || session.guest || !session.idToken) return null;
    return { uid: session.uid || "", email: session.email || "", expiresAt: session.expiresAt || 0 };
  };

  window.undalSaveAccount = function (payload) {
    return writeSession({
      idToken: payload.idToken,
      refreshToken: payload.refreshToken || "",
      expiresAt: Date.now() + (payload.expiresIn || 3600) * 1000,
      uid: payload.uid || "",
      email: payload.email || "",
      guest: false
    });
  };

  window.undalDropAccount = function () {
    remove(SESSION_KEY);
    remove(PROFILE_KEY);
  };

  /** Saves the name, number and pinned address for the checkout form. */
  window.undalSaveProfile = function (profile) {
    writeTab(PROFILE_KEY, JSON.stringify(profile || {}));
  };

  /** True when the visitor is holding an account rather than a guest session. */
  window.undalHasAccount = function () { return window.undalAccount() !== null; };

  /**
   * One call to the account API: the right token, JSON both ways, and a single
   * renewal when the token expired mid-page.
   */
  window.undalApi = function (path, options) {
    var settings = options || {};
    return window.undalSession().then(function (session) {
      return fetch(path, {
        method: settings.method || "GET",
        headers: { "content-type": "application/json", authorization: "Bearer " + session.idToken },
        body: settings.body ? JSON.stringify(settings.body) : undefined
      }).then(function (response) {
        return response.text().then(function (text) {
          var payload = null;
          try { payload = text ? JSON.parse(text) : null; } catch (error) { payload = null; }
          if (response.status === 401 && !settings.retried) {
            return renewSession(session).then(function (renewed) {
              window.undalSaveAccount({
                idToken: renewed.idToken, refreshToken: renewed.refreshToken,
                expiresIn: Math.max(60, Math.round((renewed.expiresAt - Date.now()) / 1000)),
                uid: renewed.uid, email: renewed.email
              });
              return window.undalApi(path, { method: settings.method, body: settings.body, retried: true });
            });
          }
          if (!response.ok || !payload || payload.ok === false) {
            var error = new Error((payload && (payload.message || payload.error)) || "That did not go through (" + response.status + ").");
            error.status = response.status;
            error.hint = payload && payload.hint;
            throw error;
          }
          return payload;
        });
      });
    });
  };

  // ---- 7. the plain-HTML forms, upgraded when this script is alive ----------
  // Every "Add to basket" on the site is a real form pointed at /basket/add.
  // With JavaScript it never leaves the page; without it, the server keeps the
  // basket in a cookie and the customer is none the wiser.
  function interceptForms() {
    document.addEventListener("submit", function (event) {
      var form = event.target;
      if (!form || !form.getAttribute) return;
      var mode = form.getAttribute("data-undal-form");
      if (!mode || !window.undalAdd) return;
      var id = form.querySelector ? form.querySelector('[name="id"]') : null;
      if (!id) return;
      var qtyBox = form.querySelector('[name="qty"]');
      var qty = qtyBox ? Number(qtyBox.value) || 1 : 1;
      event.preventDefault();
      if (mode === "add") {
        window.undalAdd(id.value, qty);
        var next = form.getAttribute("data-undal-next");
        if (next === "basket") window.location.href = "/basket";
      } else if (mode === "remove") {
        window.undalRemove(id.value);
      } else if (mode === "set") {
        window.undalSetQty(id.value, qty);
      }
    });
  }

  /** A name for a dish the page is showing in a flash message after a form post. */
  function announceAdded() {
    var match = /[?&]added=([^&]+)/.exec(window.location.search || "");
    if (!match) return;
    var id = decodeURIComponent(match[1]);
    var entry = catalogueEntry(id);
    if (entry) flash(entry.name + " added to your basket");
  }

  function start() {
    interceptForms();
    mergeServerCart();
    render();
    announceAdded();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
`;
