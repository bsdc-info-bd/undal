/**
 * The browser half of the motion layer.
 *
 * Written in the oldest JavaScript dialect the storefront supports — `var`,
 * `function`, no arrows, no optional chaining — because one token a phone cannot
 * parse kills the whole block, and with it every control the block wired up. The
 * gate in `tools/check-web-compat.mjs` reads this text as the browser sees it and
 * fails the build if the dialect slips.
 *
 * The rules this script lives by:
 *
 *   · **Nothing here is load-bearing.** Every dish, price and form works with the
 *     script switched off; the server rendered it. This file only adds the answer
 *     to "did that work?" and the sense that the page is alive.
 *   · **Feature-tested, never assumed.** No IntersectionObserver: everything is
 *     shown at once. No MutationObserver: the basket bar is simply not built. No
 *     pointer events: touch and click do the same job.
 *   · **Reduced motion is a command, not a preference to argue with.** When the
 *     customer has asked for less movement, the reveals are shown immediately, and
 *     no ripple, flight or vibration is started.
 *   · **One listener per job, and it survives a page that changes underneath it.**
 *     The storefront re-renders its basket in place, so anything bound to a node
 *     that can be replaced is bound to `document` instead.
 */

/** Progressive enhancement for the storefront, in the dialect an old WebView parses. */
export const MOTION_SCRIPT = `
(function () {
  var root = document.documentElement;
  // Nothing to enhance: no document element means no page, and this script is an
  // enhancement, never a dependency. (It also means a harness that hands the
  // script a partial DOM gets a silent no-op instead of a thrown TypeError.)
  if (!root || typeof root.setAttribute !== "function") return;
  var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function quiet() {
    return reduced;
  }

  function on(target, type, handler, options) {
    if (!target || !target.addEventListener) return;
    target.addEventListener(type, handler, options === undefined ? false : options);
  }

  // Passive listeners where the browser understands them, and nothing where it
  // does not: an options object handed to an old WebView is read as "capture".
  var PASSIVE = false;
  try {
    window.addEventListener("undal-passive-test", null, {
      get passive() { PASSIVE = { passive: true }; return false; }
    });
  } catch (error) { PASSIVE = false; }

  function frame(handler) {
    if (window.requestAnimationFrame) window.requestAnimationFrame(handler);
    else window.setTimeout(handler, 16);
  }

  function each(list, handler) {
    if (!list) return;
    for (var index = 0; index < list.length; index++) handler(list[index], index);
  }

  function ready(handler) {
    if (document.readyState === "loading") on(document, "DOMContentLoaded", handler);
    else handler();
  }

  // ---------------------------------------------------------------- the theme
  // The choice is written before the first paint by the head script; this is the
  // control that changes it, and the listener that follows the system when the
  // customer has not chosen for themselves.
  function saveTheme(value) {
    try { window.localStorage.setItem("undal-theme", value); } catch (error) { /* private mode */ }
  }
  function storedTheme() {
    try { return window.localStorage.getItem("undal-theme") || ""; } catch (error) { return ""; }
  }
  function paintTheme(value) {
    root.setAttribute("data-undal-theme", value);
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", value === "dark" ? "#0e1512" : "#165b3b");
  }
  function currentTheme() {
    return root.getAttribute("data-undal-theme") === "dark" ? "dark" : "light";
  }

  window.undalSetTheme = function (value, remember) {
    var next = value === "dark" ? "dark" : "light";
    paintTheme(next);
    if (remember !== false) saveTheme(next);
    each(document.querySelectorAll("[data-undal-theme-button]"), function (button) {
      var icon = button.querySelector(".undal-theme-icon");
      var label = button.querySelector(".undal-theme-label");
      if (icon) icon.textContent = next === "dark" ? "\\u263E" : "\\u2600";
      if (label) label.textContent = next === "dark" ? "Dark" : "Light";
      button.setAttribute("aria-pressed", next === "dark" ? "true" : "false");
      button.setAttribute("title", next === "dark" ? "Switch to the light theme" : "Switch to the dark theme");
      if (!quiet()) {
        button.className = button.className.indexOf("is-spinning") < 0 ? button.className + " is-spinning" : button.className;
        window.setTimeout(function () {
          button.className = button.className.replace(" is-spinning", "");
        }, 460);
      }
    });
    return next;
  };

  on(document, "click", function (event) {
    var button = event.target && event.target.closest ? event.target.closest("[data-undal-theme-button]") : null;
    if (!button) return;
    event.preventDefault();
    window.undalSetTheme(currentTheme() === "dark" ? "light" : "dark");
  });

  // A customer who has not chosen follows the system, and follows it live: a phone
  // that dims at sunset takes the storefront with it.
  if (window.matchMedia) {
    var query = window.matchMedia("(prefers-color-scheme: dark)");
    var follow = function (event) {
      if (storedTheme()) return;
      paintTheme(event.matches ? "dark" : "light");
    };
    if (query.addEventListener) query.addEventListener("change", follow);
    else if (query.addListener) query.addListener(follow);
  }

  // ------------------------------------------------------------------ reveals
  // '.reveal' is only hidden when this script says so, by putting 'js-reveal' on
  // the document element. A browser that never runs this file never hides a card.
  function revealAll() {
    each(document.querySelectorAll(".reveal"), function (node) {
      node.className = node.className.indexOf("is-visible") < 0 ? node.className + " is-visible" : node.className;
    });
  }

  function startReveals() {
    if (quiet() || !window.IntersectionObserver) {
      root.className = root.className; // no js-reveal: everything is already shown
      return;
    }
    root.className = (root.className ? root.className + " " : "") + "js-reveal";
    var seen = [];
    var observer = new IntersectionObserver(function (entries) {
      for (var index = 0; index < entries.length; index++) {
        if (!entries[index].isIntersecting) continue;
        var node = entries[index].target;
        node.className = node.className.indexOf("is-visible") < 0 ? node.className + " is-visible" : node.className;
        observer.unobserve(node);
        seen.push(node);
      }
      if (seen.length > 400) seen.length = 0;
    }, { rootMargin: "0px 0px -8% 0px", threshold: 0.06 });
    each(document.querySelectorAll(".reveal"), function (node) { observer.observe(node); });
    // Anything already on screen when the observer is built is shown at once.
    window.setTimeout(revealOnScreen, 60);
    function revealOnScreen() {
      each(document.querySelectorAll(".reveal:not(.is-visible)"), function (node) {
        var box = node.getBoundingClientRect();
        if (box.top < (window.innerHeight || 800) && box.bottom > 0) {
          node.className = node.className + " is-visible";
          observer.unobserve(node);
        }
      });
    }
  }

  // A page that renders new cards later — the basket, a filtered menu — gets them
  // revealed too, without a second observer for every screen.
  function watchNewReveals() {
    if (quiet() || !window.MutationObserver || root.className.indexOf("js-reveal") < 0) return;
    var observer = new MutationObserver(function (records) {
      for (var index = 0; index < records.length; index++) {
        var added = records[index].addedNodes;
        for (var node = 0; node < added.length; node++) {
          var element = added[node];
          if (!element || element.nodeType !== 1) continue;
          if (element.className && String(element.className).indexOf("reveal") >= 0) {
            window.setTimeout(function (target) {
              return function () {
                target.className = String(target.className).indexOf("is-visible") < 0
                  ? target.className + " is-visible" : target.className;
              };
            }(element), 30);
          }
        }
      }
    });
    observer.observe(document.body || root, { childList: true, subtree: true });
  }

  // ------------------------------------------------------------------- ripple
  function ripple(event, node) {
    if (quiet() || !node) return;
    var box = node.getBoundingClientRect();
    var size = Math.max(box.width, box.height);
    var x = (event.clientX || box.left + box.width / 2) - box.left - size / 2;
    var y = (event.clientY || box.top + box.height / 2) - box.top - size / 2;
    var dot = document.createElement("span");
    dot.className = "undal-ripple";
    dot.style.width = size + "px";
    dot.style.height = size + "px";
    dot.style.left = x + "px";
    dot.style.top = y + "px";
    node.appendChild(dot);
    window.setTimeout(function () {
      if (dot.parentNode) dot.parentNode.removeChild(dot);
    }, 640);
  }

  var POINTER = window.PointerEvent ? "pointerdown" : "touchstart";
  on(document, POINTER, function (event) {
    var target = event.target;
    if (!target || !target.closest) return;
    var node = target.closest(".button, .mini-button, .qty button, .chips a, .tabs a, .tabs button");
    if (!node) return;
    ripple(event.touches && event.touches[0] ? event.touches[0] : event, node);
  });

  // ------------------------------------------------------------------- toasts
  var toastHost = null;
  function host() {
    if (toastHost && toastHost.parentNode) return toastHost;
    toastHost = document.createElement("div");
    toastHost.className = "undal-toasts";
    toastHost.setAttribute("role", "status");
    toastHost.setAttribute("aria-live", "polite");
    (document.body || root).appendChild(toastHost);
    return toastHost;
  }

  /**
   * A short, dismissable confirmation. 'kind' is "ok" or "error".
   * Returns a function that closes it early.
   */
  window.undalToast = function (message, kind, milliseconds) {
    if (!message) return function () {};
    var box = document.createElement("div");
    box.className = "undal-toast" + (kind === "error" ? " is-error" : "");
    var mark = document.createElement("span");
    mark.className = "undal-toast-mark";
    mark.setAttribute("aria-hidden", "true");
    mark.textContent = kind === "error" ? "\\u2715" : "\\u2713";
    var text = document.createElement("span");
    text.textContent = String(message);
    var close = document.createElement("button");
    close.type = "button";
    close.setAttribute("aria-label", "Dismiss");
    close.textContent = "\\u00d7";
    box.appendChild(mark);
    box.appendChild(text);
    box.appendChild(close);
    host().appendChild(box);
    window.setTimeout(function () { box.className = box.className + " is-shown"; }, 20);
    var timer = window.setTimeout(dismiss, milliseconds || 3200);
    function dismiss() {
      window.clearTimeout(timer);
      box.className = box.className.replace(" is-shown", "");
      window.setTimeout(function () {
        if (box.parentNode) box.parentNode.removeChild(box);
      }, quiet() ? 0 : 320);
    }
    on(close, "click", dismiss);
    return dismiss;
  };

  on(document, "keydown", function (event) {
    if (event.key === "Escape" || event.keyCode === 27) {
      each(document.querySelectorAll(".undal-toast"), function (box) {
        box.className = box.className.replace(" is-shown", "");
        window.setTimeout(function () {
          if (box.parentNode) box.parentNode.removeChild(box);
        }, quiet() ? 0 : 320);
      });
    }
  });

  // ------------------------------------------------------- reading position
  var bar = null;
  function progressBar() {
    if (bar && bar.parentNode) return bar;
    bar = document.createElement("div");
    bar.className = "undal-progress";
    bar.setAttribute("aria-hidden", "true");
    bar.appendChild(document.createElement("span"));
    (document.body || root).appendChild(bar);
    return bar;
  }

  var ticking = false;
  function onScroll() {
    if (ticking) return;
    ticking = true;
    frame(function () {
      ticking = false;
      var doc = document;
      var height = (doc.documentElement.scrollHeight || 0) - (window.innerHeight || 0);
      var position = window.pageYOffset || doc.documentElement.scrollTop || 0;
      var ratio = height > 0 ? Math.min(1, Math.max(0, position / height)) : 0;
      var line = progressBar();
      line.firstChild.style.width = Math.round(ratio * 100) + "%";
      if (ratio > 0.005 && ratio < 0.999) {
        if (line.className.indexOf("is-on") < 0) line.className = line.className + " is-on";
      } else {
        line.className = line.className.replace(" is-on", "");
      }
      var nav = doc.querySelector("nav.store");
      if (nav) {
        if (position > 12) {
          if (nav.className.indexOf("is-stuck") < 0) nav.className = nav.className + " is-stuck";
        } else {
          nav.className = nav.className.replace(" is-stuck", "");
        }
      }
      if (position > 220) showBasketBar(); else hideBasketBar();
    });
  }

  // ----------------------------------------------------------- basket bar
  var basketBar = null;
  var basketCount = null;
  var basketTotal = null;

  function basketBarNode() {
    if (basketBar && basketBar.parentNode) return basketBar;
    basketBar = document.createElement("div");
    basketBar.className = "undal-basketbar";
    var link = document.createElement("a");
    link.href = "/basket";
    var label = document.createElement("span");
    label.textContent = "View basket";
    basketCount = document.createElement("span");
    basketCount.className = "undal-basketbar-count";
    basketCount.textContent = "0";
    basketTotal = document.createElement("span");
    basketTotal.className = "undal-basketbar-total";
    link.appendChild(label);
    link.appendChild(basketCount);
    link.appendChild(basketTotal);
    basketBar.appendChild(link);
    (document.body || root).appendChild(basketBar);
    return basketBar;
  }

  function basketFacts() {
    var count = 0;
    var total = 0;
    if (typeof window.undalLines === "function") {
      var lines = window.undalLines() || [];
      for (var index = 0; index < lines.length; index++) count += Number(lines[index].qty) || 0;
    } else {
      var badge = document.getElementById("undal-cart-count");
      count = badge ? Number(badge.textContent) || 0 : 0;
    }
    if (typeof window.undalTotals === "function") {
      // The subtotal, not the grand total: the delivery fee depends on a pin the
      // menu page has not asked for yet, and a bar that promises a total it cannot
      // know is a bar that argues with the checkout.
      var totals = window.undalTotals(window.UNDAL_DISTANCE_KM);
      if (totals && typeof window.undalMoney === "function") total = window.undalMoney(totals.subtotal);
    }
    return { count: count, total: total };
  }

  function showBasketBar() {
    var facts = basketFacts();
    if (facts.count <= 0) { hideBasketBar(); return; }
    var node = basketBarNode();
    basketCount.textContent = String(facts.count);
    basketTotal.textContent = facts.total ? String(facts.total) : "";
    if (node.className.indexOf("is-shown") < 0) node.className = node.className + " is-shown";
  }

  function hideBasketBar() {
    if (!basketBar) return;
    basketBar.className = basketBar.className.replace(" is-shown", "");
  }

  function bumpBasket() {
    var facts = basketFacts();
    if (facts.count <= 0) { hideBasketBar(); return; }
    showBasketBar();
    if (quiet()) return;
    var badge = document.getElementById("undal-cart-count");
    var nav = badge ? badge.parentNode : null;
    each([badge, nav, basketBar], function (node) {
      if (!node) return;
      node.className = node.className.replace(" is-bumped", "");
      window.setTimeout(function () { node.className = node.className + " is-bumped"; }, 10);
      window.setTimeout(function () { node.className = node.className.replace(" is-bumped", ""); }, 620);
    });
    if (navigator.vibrate) { try { navigator.vibrate(9); } catch (error) { /* not ours to give */ } }
  }

  // The basket is the cart script's business; this only notices that it changed.
  function watchBasket() {
    var badge = document.getElementById("undal-cart-count");
    if (!badge) return;
    if (window.MutationObserver) {
      var observer = new MutationObserver(function () { bumpBasket(); });
      observer.observe(badge, { childList: true, characterData: true, subtree: true, attributes: true });
    }
    showBasketBar();
  }

  // A dish flying into the basket: the answer to "did that work?" that does not
  // need a word, and the reason the badge bump above is not enough on its own.
  function flyToBasket(fromNode) {
    if (quiet() || !fromNode || !fromNode.getBoundingClientRect) return;
    var target = document.querySelector("nav.store a.cart") || document.getElementById("undal-cart-count");
    if (!target || !target.getBoundingClientRect) return;
    var start = fromNode.getBoundingClientRect();
    var end = target.getBoundingClientRect();
    var dot = document.createElement("span");
    dot.className = "undal-flight";
    dot.style.left = (start.left + start.width / 2) + "px";
    dot.style.top = (start.top + start.height / 2) + "px";
    (document.body || root).appendChild(dot);
    window.setTimeout(function () {
      dot.style.transform = "translate(" +
        (end.left + end.width / 2 - start.left - start.width / 2) + "px," +
        (end.top + end.height / 2 - start.top - start.height / 2) + "px) scale(.4)";
      dot.style.opacity = "0.15";
    }, 20);
    window.setTimeout(function () {
      if (dot.parentNode) dot.parentNode.removeChild(dot);
    }, 720);
  }

  on(document, "submit", function (event) {
    var form = event.target;
    if (!form || !form.getAttribute) return;
    if (form.getAttribute("data-undal-form") !== "add") return;
    var button = form.querySelector("button");
    flyToBasket(button || form);
  }, true);

  // ------------------------------------------------------------- the pictures
  // A photo that has painted stops the shimmer behind it. 'complete' catches the
  // ones that were already in the browser's own cache when this script ran.
  function paintPictures(scope) {
    each((scope || document).querySelectorAll(".food-art > img"), function (image) {
      if (image.complete && image.naturalWidth > 0) {
        if (image.parentNode) image.parentNode.className = image.parentNode.className + " is-painted";
        return;
      }
      on(image, "load", function () {
        if (image.parentNode && String(image.parentNode.className).indexOf("is-painted") < 0) {
          image.parentNode.className = image.parentNode.className + " is-painted";
        }
      });
      on(image, "error", function () {
        if (image.parentNode && String(image.parentNode.className).indexOf("is-painted") < 0) {
          image.parentNode.className = image.parentNode.className + " is-painted";
        }
      });
    });
  }

  // ------------------------------------------------------------------ search
  // "/" is the shortcut every menu, docs and shop site has taught a keyboard.
  on(document, "keydown", function (event) {
    var key = event.key || "";
    var typing = event.target && event.target.tagName &&
      (event.target.tagName === "INPUT" || event.target.tagName === "TEXTAREA" || event.target.tagName === "SELECT");
    if ((key === "/" || ((event.ctrlKey || event.metaKey) && (key === "k" || key === "K"))) && !typing) {
      var field = document.querySelector('.search input[type="search"], .search input');
      if (!field) return;
      event.preventDefault();
      field.focus();
      if (field.select) field.select();
      field.scrollIntoView({ block: "center", behavior: quiet() ? "auto" : "smooth" });
    }
  });

  on(window, "scroll", onScroll, PASSIVE);
  on(window, "resize", onScroll);

  ready(function () {
    startReveals();
    watchNewReveals();
    watchBasket();
    paintPictures(document);
    onScroll();
    // A page that was restored from the browser's own back-forward cache arrives
    // already scrolled, so the sticky state and the reveals have to be re-judged.
    on(window, "pageshow", function () { onScroll(); revealIfHidden(); });
    function revealIfHidden() {
      if (root.className.indexOf("js-reveal") < 0) return;
      each(document.querySelectorAll(".reveal:not(.is-visible)"), function (node) {
        var box = node.getBoundingClientRect();
        if (box.top < (window.innerHeight || 800)) node.className = node.className + " is-visible";
      });
    }
  });
})();
`;

/**
 * The header's theme control, server-rendered so it exists with the script off.
 *
 * `aria-pressed` rather than a checkbox: it is one button with two states, and a
 * screen reader announces the state instead of a control nobody can see.
 */
export function themeToggle(): string {
  return `<button class="undal-theme" type="button" data-undal-theme-button="true" aria-pressed="false"
 title="Switch to the dark theme"><span class="undal-theme-icon" aria-hidden="true">☀</span>
<span class="undal-theme-label">Light</span></button>`;
}
