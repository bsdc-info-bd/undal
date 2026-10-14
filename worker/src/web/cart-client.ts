/**
 * The browser-side cart.
 *
 * One small script, served inline on every store page. The cart lives in
 * `localStorage` as `[{id, qty, kind}]` and holds no prices: the server prices
 * the order at checkout from live data, so a visitor editing storage can only
 * change what they *ask* for, never what they pay. The script also caches the
 * anonymous session token in `sessionStorage` so a page reload does not create
 * a new Firebase account for the same human.
 */

export const CART_STORAGE_KEY = "undal_cart_v1";
export const SESSION_STORAGE_KEY = "undal_web_session_v1";

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

  function readCart() {
    try {
      var parsed = JSON.parse(localStorage.getItem(CART_KEY) || "[]");
      return Array.isArray(parsed) ? parsed.filter(function (line) { return line && line.id && line.qty > 0; }) : [];
    } catch (error) { return []; }
  }
  function writeCart(lines) {
    try { localStorage.setItem(CART_KEY, JSON.stringify(lines)); } catch (error) { /* private mode */ }
    render();
  }
  function lineCount(lines) {
    return (lines || readCart()).reduce(function (sum, line) { return sum + line.qty; }, 0);
  }

  // ---- catalogue, embedded by the page -------------------------------------
  window.UNDAL_CATALOGUE = window.UNDAL_CATALOGUE || {};
  window.UNDAL_STORE = window.UNDAL_STORE || { perKmPaisa: 0, radiusKm: 0, minOrderPaisa: 0 };

  function catalogueEntry(id) { return window.UNDAL_CATALOGUE[id] || null; }

  window.undalAdd = function (id, qty) {
    var entry = catalogueEntry(id);
    if (!entry) return;
    var lines = readCart();
    var existing = lines.find(function (line) { return line.id === id; });
    var wanted = (existing ? existing.qty : 0) + (qty || 1);
    wanted = Math.min(wanted, Math.max(1, entry.maxQty || 50));
    if (existing) { existing.qty = wanted; } else { lines.push({ id: id, qty: wanted, kind: entry.kind }); }
    writeCart(lines);
    flash(entry.name + " added to your basket");
  };
  window.undalSetQty = function (id, qty) {
    var lines = readCart().map(function (line) {
      return line.id === id ? { id: line.id, qty: Math.max(0, Math.min(qty, 50)), kind: line.kind } : line;
    }).filter(function (line) { return line.qty > 0; });
    writeCart(lines);
  };
  window.undalRemove = function (id) {
    writeCart(readCart().filter(function (line) { return line.id !== id; }));
  };
  window.undalClear = function () { writeCart([]); };
  window.undalLines = readCart;

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
    var fraction = rest === 0 ? "" : "." + String(rest).padStart(2, "0").replace(/0+$/, "");
    return "\\u09F3 " + (negative ? "-" : "") + whole + fraction;
  }
  window.undalMoney = money;

  function cents(paisa) { return Math.round(paisa); }

  /** The saved profile, or null for a guest or a browser in private mode. */
  window.undalProfile = function () {
    try { return JSON.parse(sessionStorage.getItem(PROFILE_KEY) || "null"); } catch (error) { return null; }
  };
  window.undalTotals = function (distanceKm) {
    var lines = readCart();
    var subtotal = 0;
    lines.forEach(function (line) {
      var entry = catalogueEntry(line.id);
      if (entry) subtotal += entry.price * line.qty;
    });
    var store = window.UNDAL_STORE || {};
    var km = typeof distanceKm === "number" && distanceKm > 0 ? distanceKm : 0;
    var delivery = km > 0 && store.perKmPaisa > 0 ? Math.ceil(km) * store.perKmPaisa : 0;
    return { subtotal: cents(subtotal), delivery: cents(delivery), total: cents(subtotal + delivery), lines: lines };
  };

  function flash(message, isError) {
    var host = document.getElementById("undal-flash");
    if (!host) return;
    host.textContent = message;
    host.className = "notice" + (isError ? " error" : " ok");
    host.style.display = "block";
    if (!isError) {
      window.clearTimeout(host._timer);
      host._timer = window.setTimeout(function () { host.style.display = "none"; }, 2600);
    }
  }
  window.undalFlash = flash;

  function render() {
    var lines = readCart();
    var badge = document.getElementById("undal-cart-count");
    if (badge) {
      var count = lineCount(lines);
      badge.textContent = count ? String(count) : "";
      badge.style.display = count ? "inline-block" : "none";
    }
    var host = document.getElementById("undal-cart-lines");
    if (!host) return;
    if (lines.length === 0) {
      host.innerHTML = '<div class="notice">Your basket is empty. <a href="/store">Back to the menu</a>.</div>';
    } else {
      host.innerHTML = lines.map(function (line) {
        var entry = catalogueEntry(line.id) || { name: "Dish", price: 0, kind: "dish" };
        return '<div class="row"><div><strong>' + escapeHtml(entry.name) + '</strong><div class="smallprint">' +
          money(entry.price) + ' each</div></div><div class="qty">' +
          '<button type="button" aria-label="One less" onclick="undalSetQty(\\'' + line.id + '\\',' + (line.qty - 1) + ')">\\u2212</button>' +
          '<span>' + line.qty + '</span>' +
          '<button type="button" aria-label="One more" onclick="undalSetQty(\\'' + line.id + '\\',' + (line.qty + 1) + ')">+</button>' +
          '<button type="button" aria-label="Remove" onclick="undalRemove(\\'' + line.id + '\\')">\\u2715</button></div></div>';
      }).join("");
    }
    var totals = window.undalTotals(window.UNDAL_DISTANCE_KM);
    var subtotalHost = document.getElementById("undal-subtotal");
    if (subtotalHost) subtotalHost.textContent = money(totals.subtotal);
    var deliveryHost = document.getElementById("undal-delivery");
    if (deliveryHost) {
      deliveryHost.textContent = totals.delivery > 0 ? money(totals.delivery) : "measured at checkout";
    }
    var totalHost = document.getElementById("undal-total");
    if (totalHost) totalHost.textContent = money(totals.total);
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
    if (checkout) {
      checkout.disabled = lines.length === 0 || (min > 0 && totals.subtotal < min);
    }
  }
  function escapeHtml(value) {
    return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }
  window.undalRender = render;

  // ---- the same field rules the app and the worker use ---------------------
  // Kept in step by tools/check-web-rules.mjs: these three functions are run
  // through the app's own ValidatorsTest vectors on every push.
  function usablePhone(raw) {
    var digits = String(raw).replace(/\\D/g, "");
    var local = digits.indexOf("880") === 0 ? "0" + digits.slice(3) : digits;
    return /^01[3-9][0-9]{8}$/.test(local) ? local : null;
  }
  function usableText(raw) {
    return String(raw).trim().length >= 3;
  }
  function usableName(raw) {
    var value = String(raw).trim();
    if (value.length < 3 || value.length > 80) return false;
    return [...value].some(function (character) { return /[\\p{L} .-]/u.test(character); });
  }
  // All three are published on the window because the checkout and the account page
  // run in their own script block: a validator that only exists inside this IIFE
  // is a ReferenceError at the moment a customer taps "Place the order", which is
  // exactly what the web checkout used to do — nothing at all, silently.
  window.undalUsablePhone = usablePhone;
  window.undalUsableName = usableName;
  window.undalUsableText = usableText;

  // ---- the session: a signed-in account when there is one, a guest otherwise
  function readSession() {
    try { return JSON.parse(sessionStorage.getItem(SESSION_KEY) || "null"); } catch (error) { return null; }
  }
  function writeSession(session) {
    try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(session)); } catch (error) { /* private mode */ }
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
    try {
      sessionStorage.removeItem(SESSION_KEY);
      sessionStorage.removeItem(PROFILE_KEY);
    } catch (error) { /* nothing to clear */ }
  };

  /** Saves the name, number and pinned address for the checkout form. */
  window.undalSaveProfile = function (profile) {
    try { sessionStorage.setItem(PROFILE_KEY, JSON.stringify(profile || {})); } catch (error) { /* private mode */ }
  };

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

  document.addEventListener("DOMContentLoaded", render);
  if (document.readyState !== "loading") render();
})();
`;
