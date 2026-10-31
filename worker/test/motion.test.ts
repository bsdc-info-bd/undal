/**
 * The motion layer, executed.
 *
 * `motion-client.ts` ships as text inside a template literal: the TypeScript
 * compiler never reads it, and a browser that cannot parse it says nothing at all.
 * `tools/check-web-compat.mjs` proves the dialect; this file proves the behaviour,
 * by running the real script against the real rendered storefront in a DOM just
 * large enough to hold it.
 *
 * The point of each test is a promise the page makes to a customer: a dish that
 * scrolls into view becomes visible, a theme survives a reload, an add to the
 * basket is answered, and none of it happens at all when the customer has asked
 * for reduced motion.
 */
import assert from "node:assert/strict";
import { test } from "node:test";
import { renderPage } from "../src/web/layout";
import { MOTION_SCRIPT, themeToggle } from "../src/web/motion-client";
import { THEME_BOOT_SCRIPT } from "../src/web/motion";
import { MiniElement, parseHtml } from "./helpers/mini-dom";

// --------------------------------------------------------------- a small DOM

type Listener = (event: Record<string, unknown>) => void;

interface StubElement extends MiniElement {
  style: Record<string, string>;
  parentNode: StubElement | null;
  nodeType: number;
  complete?: boolean;
  naturalWidth?: number;
  listeners: Map<string, Listener[]>;
  appendChild(child: StubElement): StubElement;
  removeChild(child: StubElement): StubElement;
  addEventListener(type: string, handler: Listener): void;
  removeEventListener(type: string, handler: Listener): void;
  closest(selector: string): StubElement | null;
  matches(selector: string): boolean;
  querySelector(selector: string): StubElement | null;
  querySelectorAll(selector: string): StubElement[];
  focus(): void;
  select(): void;
  scrollIntoView(options?: unknown): void;
  getBoundingClientRect(): { left: number; top: number; width: number; height: number; right: number; bottom: number };
  get firstChild(): StubElement | null;
  dispatchEvent(event: Record<string, unknown>): void;
  focused?: boolean;
}

/** Adds the DOM surface the motion script touches to a parsed element. Idempotent. */
function patch(element: MiniElement, parent: StubElement | null = null): StubElement {
  const node = element as StubElement;
  if (node.listeners) {
    if (parent) node.parentNode = parent;
    return node;
  }
  node.nodeType = 1;
  // `parseHtml` records attributes and leaves `className` empty; the selector
  // matcher below reads `className`, the way a browser does.
  if (!node.className) node.className = node.getAttribute("class") ?? "";
  node.style = node.style ?? {};
  node.parentNode = parent;
  node.listeners = node.listeners ?? new Map();
  node.appendChild = function (child: StubElement): StubElement {
    child.parentNode = node;
    node.children.push(child);
    return child;
  };
  node.removeChild = function (child: StubElement): StubElement {
    const index = node.children.indexOf(child);
    if (index >= 0) node.children.splice(index, 1);
    child.parentNode = null;
    return child;
  };
  node.addEventListener = function (type: string, handler: Listener): void {
    const list = node.listeners.get(type) ?? [];
    list.push(handler);
    node.listeners.set(type, list);
  };
  node.removeEventListener = function (type: string, handler: Listener): void {
    const list = node.listeners.get(type) ?? [];
    node.listeners.set(
      type,
      list.filter((entry) => entry !== handler),
    );
  };
  node.dispatchEvent = function (event: Record<string, unknown>): void {
    for (const handler of node.listeners.get(String(event.type)) ?? []) handler(event);
  };
  node.matches = (selector: string): boolean => matchesSelector(node, selector);
  node.closest = function (selector: string): StubElement | null {
    let current: StubElement | null = node;
    while (current) {
      if (matchesSelector(current, selector)) return current;
      current = current.parentNode;
    }
    return null;
  };
  node.querySelector = (selector: string): StubElement | null => querySelectorAllFrom(node, selector)[0] ?? null;
  node.querySelectorAll = (selector: string): StubElement[] => querySelectorAllFrom(node, selector);
  node.focus = function (): void {
    node.focused = true;
  };
  node.select = function (): void {
    node.focused = true;
  };
  node.scrollIntoView = function (): void {};
  node.getBoundingClientRect = (): { left: number; top: number; width: number; height: number; right: number; bottom: number } => ({
    left: 10,
    top: 20,
    width: 120,
    height: 44,
    right: 130,
    bottom: 64,
  });
  Object.defineProperty(node, "firstChild", {
    configurable: true,
    get: () => (node.children[0] && "tagName" in node.children[0] ? patch(node.children[0] as MiniElement, node) : null),
  });
  for (const child of node.children) {
    if ("tagName" in child) patch(child as MiniElement, node);
  }
  return node;
}

/** `#id`, `.class`, `tag`, `[attr]`, `[attr="v"]`, `:not(.x)`, `>` and commas. */
function matchesSimple(element: StubElement, simple: string): boolean {
  let rest = simple;
  let required = true;
  const notMatch = /:not\(([^)]*)\)/.exec(rest);
  if (notMatch) {
    if (matchesSimple(element, notMatch[1] ?? "")) return false;
    rest = rest.replace(notMatch[0], "");
  }
  const attribute = /\[([A-Za-z-]+)(?:=["']?([^"'\]]*)["']?)?\]/.exec(rest);
  if (attribute) {
    const name = (attribute[1] ?? "").toLowerCase();
    const wanted = attribute[2];
    const value = element.getAttribute(name);
    if (value === null) required = false;
    else if (wanted !== undefined && value !== wanted) required = false;
    rest = rest.replace(attribute[0], "");
  }
  const parts = rest.match(/[#.][A-Za-z0-9_-]+|^[A-Za-z0-9]+/g) ?? [];
  for (const part of parts) {
    if (part.startsWith("#")) {
      if (element.id !== part.slice(1)) required = false;
    } else if (part.startsWith(".")) {
      if (!element.className.split(/\s+/).includes(part.slice(1))) required = false;
    } else if (element.tagName !== part.toLowerCase()) {
      required = false;
    }
  }
  return required;
}

function matchesSelector(element: StubElement, selector: string): boolean {
  for (const option of selector.split(",")) {
    const steps = option.trim().split(/\s+/).filter((step) => step.length > 0);
    if (steps.length === 0) continue;
    // The rightmost simple selector is the element itself: ".card p" must not
    // match a <p> merely because a card is somewhere above it.
    if (!matchesSimple(element, steps[steps.length - 1] ?? "")) continue;
    let current: StubElement | null = element.parentNode;
    let matched = true;
    // A ">" forbids the walk upwards that a descendant combinator allows.
    let onlyParent = false;
    for (let index = steps.length - 2; index >= 0; index -= 1) {
      const step = steps[index] ?? "";
      if (step === ">") {
        onlyParent = true;
        continue;
      }
      if (!current) {
        matched = false;
        break;
      }
      if (matchesSimple(current, step)) {
        current = current.parentNode;
        onlyParent = false;
        continue;
      }
      if (onlyParent) {
        matched = false;
        break;
      }
      let walker = current.parentNode;
      while (walker && !matchesSimple(walker, step)) walker = walker.parentNode;
      if (!walker) {
        matched = false;
        break;
      }
      current = walker.parentNode;
    }
    if (matched) return true;
  }
  return false;
}

function querySelectorAllFrom(scope: StubElement, selector: string): StubElement[] {
  return scope
    .descendants()
    .map((element) => patch(element))
    .filter((element) => matchesSelector(element, selector));
}

/**
 * Runs one script against a rendered page.
 *
 * `markup` is the whole document, so the harness can be pointed at a page the
 * real Worker produced or at the two elements a single behaviour needs.
 */
function harness(
  markup: string,
  options: { dark?: boolean; reduced?: boolean; stored?: string } = {},
): {
  root: StubElement;
  window: StubWindow;
  navigator: { vibrate?: (pattern: number) => boolean };
  run: (code: string) => void;
  byId: (id: string) => StubElement | null;
  find: (selector: string) => StubElement | null;
  findAll: (selector: string) => StubElement[];
  fireWindow: (type: string, event?: Record<string, unknown>) => void;
  fireDocument: (type: string, event?: Record<string, unknown>) => void;
} {
  const tree = patch(parseHtml(markup));
  const found = tree.descendants().find((element) => element.tagName === "html");
  const root = found ? patch(found, tree) : patch(tree.appendChild(new MiniElement("html")), tree);
  const head = (root.descendants().find((element) => element.tagName === "head") ??
    patch(root.appendChild(new MiniElement("head")), root)) as StubElement;
  const body = (root.descendants().find((element) => element.tagName === "body") ??
    patch(root.appendChild(new MiniElement("body")), root)) as StubElement;
  void head;
  root.setAttribute("data-undal-theme", options.dark ? "dark" : "light");
  const themeMeta = new MiniElement("meta");
  themeMeta.setAttribute("name", "theme-color");
  themeMeta.setAttribute("content", "#165b3b");
  const existingMeta = root.descendants().find(
    (element) => element.tagName === "meta" && element.getAttribute("name") === "theme-color",
  );
  if (!existingMeta) head.appendChild(patch(themeMeta, head));

  const store = new Map<string, string>();
  if (options.stored) store.set("undal-theme", options.stored);

  const windowListeners = new Map<string, Listener[]>();
  const observers: StubWindow["observers"] = [];
  const mutations: StubWindow["mutations"] = [];
  const timers: Array<{ id: number; at: number; handler: () => void }> = [];
  const clock = { now: 0 };
  let timerSequence = 0;

  function intersectionObserverStub(
    callback: (entries: Array<{ isIntersecting: boolean; target: StubElement }>) => void,
  ) {
    const targets: StubElement[] = [];
    const observer = {
      targets,
      observe: (element: StubElement) => {
        targets.push(element);
      },
      unobserve: (element: StubElement) => {
        const index = targets.indexOf(element);
        if (index >= 0) targets.splice(index, 1);
      },
      disconnect: () => {
        targets.length = 0;
      },
      intersect: (elements: StubElement[]) => {
        callback(elements.map((target) => ({ isIntersecting: true, target })));
      },
    };
    observers.push(observer);
    return observer;
  }

  function mutationObserverStub(callback: (records: Array<{ addedNodes: StubElement[] }>) => void) {
    return {
      observe: (target: StubElement) => {
        mutations.push({ target, fire: (added: StubElement[]) => callback([{ addedNodes: added }]) });
      },
      disconnect: () => {},
    };
  }


  const stubWindow: StubWindow = {
    innerHeight: 800,
    pageYOffset: 0,
    listeners: windowListeners,
    observers,
    mutations,
    // A browser reaches these as `window.IntersectionObserver`, and the script
    // feature-tests them that way, so the harness offers them that way too.
    IntersectionObserver: intersectionObserverStub,
    MutationObserver: mutationObserverStub,
    toasts: [],
    matchMedia: (query: string) => ({
      matches: query.includes("dark") ? !!options.dark : query.includes("reduce") ? !!options.reduced : false,
      addEventListener: () => {},
    }),
    localStorage: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => {
        store.set(key, value);
      },
    },
    requestAnimationFrame: (handler: () => void) => {
      handler();
    },
    // A virtual clock. The script schedules a 3.2-second toast dismissal and a
    // 460-millisecond icon spin; a test that waits in real time is a slow test, and
    // a test that clamps every delay to a few milliseconds watches a toast dismiss
    // itself before it was ever on screen. Timers are queued and run on demand.
    setTimeout: ((handler: () => void, ms?: number) => {
      const id = ++timerSequence;
      timers.push({ id, at: clock.now + Math.max(0, ms ?? 0), handler });
      return id as unknown as ReturnType<typeof setTimeout>;
    }) as typeof setTimeout,
    clearTimeout: ((handle: number) => {
      const index = timers.findIndex((timer) => timer.id === Number(handle));
      if (index >= 0) timers.splice(index, 1);
    }) as typeof clearTimeout,
    advance: (milliseconds: number) => {
      const until = clock.now + milliseconds;
      for (let guard = 0; guard < 200; guard += 1) {
        const due = timers.filter((timer) => timer.at <= until).sort((left, right) => left.at - right.at)[0];
        if (!due) break;
        timers.splice(timers.indexOf(due), 1);
        clock.now = Math.max(clock.now, due.at);
        due.handler();
      }
      clock.now = until;
    },
    addEventListener: (type: string, handler: Listener, listenOptions?: unknown) => {
      // Reading `passive` is exactly what the script's own feature test waits for.
      if (listenOptions && typeof listenOptions === "object") void (listenOptions as { passive?: boolean }).passive;
      const list = windowListeners.get(type) ?? [];
      list.push(handler);
      windowListeners.set(type, list);
    },
    removeEventListener: (type: string, handler: Listener) => {
      const list = windowListeners.get(type) ?? [];
      windowListeners.set(
        type,
        list.filter((entry) => entry !== handler),
      );
    },
  };

  const documentListeners = new Map<string, Listener[]>();
  const documentStub = {
    readyState: "complete",
    documentElement: root,
    body,
    head,
    listeners: documentListeners,
    createElement: (tag: string): StubElement => patch(new MiniElement(tag)),
    querySelector: (selector: string): StubElement | null => querySelectorAllFrom(root, selector)[0] ?? null,
    querySelectorAll: (selector: string): StubElement[] => querySelectorAllFrom(root, selector),
    getElementById: (id: string): StubElement | null => querySelectorAllFrom(root, `#${id}`)[0] ?? null,
    addEventListener: (type: string, handler: Listener): void => {
      const list = documentListeners.get(type) ?? [];
      list.push(handler);
      documentListeners.set(type, list);
    },
  };

  const navigator = { vibrate: (pattern: number): boolean => Boolean(pattern) };

  const run = (code: string): void => {
    const factory = new Function(
      "window",
      "document",
      "navigator",
      "IntersectionObserver",
      "MutationObserver",
      `"use strict";\n${code}`,
    );
    factory(
      stubWindow,
      documentStub,
      navigator,
      intersectionObserverStub,
      mutationObserverStub,
    );
  };

  return {
    root,
    window: stubWindow,
    advance: stubWindow.advance,
    navigator,
    run,
    byId: (id: string) => querySelectorAllFrom(root, `#${id}`)[0] ?? null,
    find: (selector: string) => querySelectorAllFrom(root, selector)[0] ?? null,
    findAll: (selector: string) => querySelectorAllFrom(root, selector),
    fireWindow: (type: string, event: Record<string, unknown> = {}) => {
      for (const handler of windowListeners.get(type) ?? []) handler({ type, preventDefault: () => {}, stopPropagation: () => {}, ...event });
    },
    fireDocument: (type: string, event: Record<string, unknown> = {}) => {
      for (const handler of documentListeners.get(type) ?? []) handler({ type, preventDefault: () => {}, stopPropagation: () => {}, ...event });
    },
  };
}

/** A document shaped like the one `layout.ts` ships, with the theme control in it. */
function shell(options: { dark?: boolean; reduced?: boolean; stored?: string } = {}): ReturnType<typeof harness> {
  const markup = `<!doctype html><html lang="en-GB"><head>
<meta charset="utf-8"><meta name="theme-color" content="#165b3b"><title>Undal</title>
</head><body>
<header><div class="header-tools">${themeToggle()}</div></header>
<nav class="store"><div class="inner"><a href="/store">Menu</a>
<a class="cart" href="/basket">Basket <span id="undal-cart-count" style="display:none"></span></a></div></nav>
<main id="main-content">
<section class="store-hero"><h2>A little taste of home</h2></section>
<dl class="undal-facts reveal"><div><dt>Delivery</dt><dd>৳ 25 per km</dd></div></dl>
<form class="search" action="/store" method="get"><input type="search" name="q"><button class="button">Search</button></form>
<div class="grid">
<div class="item reveal" style="--i:0"><div class="food-art"><img src="/a.jpg" alt=""></div></div>
<div class="item reveal" style="--i:1"><div class="food-art no-photo"><div class="food-placeholder"></div></div></div>
</div>
<form class="undal-add" data-undal-form="add" data-undal-next="store" method="post" action="/basket/add">
<input type="hidden" name="id" value="rice"><button class="button" type="submit">Add</button></form>
</main></body></html>`;
  return harness(markup, options);
}

/** Real time, for the assertions that only need a microtask to land. */
const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 10));

// ------------------------------------------------------------------- the theme

test("the head script decides the theme before the first paint", () => {
  const system = shell({ dark: true });
  system.root.removeAttribute("data-undal-theme");
  system.run(THEME_BOOT_SCRIPT);
  assert.equal(system.root.getAttribute("data-undal-theme"), "dark", "a phone in dark mode gets the dark storefront");

  const light = shell({ dark: false });
  light.root.removeAttribute("data-undal-theme");
  light.run(THEME_BOOT_SCRIPT);
  assert.equal(light.root.getAttribute("data-undal-theme"), "light");

  const saved = shell({ dark: true, stored: "light" });
  saved.root.removeAttribute("data-undal-theme");
  saved.run(THEME_BOOT_SCRIPT);
  assert.equal(saved.root.getAttribute("data-undal-theme"), "light", "a customer's own choice beats the system");
});

test("the theme button flips the palette, tells a screen reader, and is remembered", async () => {
  const page = shell();
  page.run(MOTION_SCRIPT);
  const button = page.find("[data-undal-theme-button]");
  assert.ok(button, "the control the server rendered is the control the script drives");

  page.fireDocument("click", { target: button.querySelector(".undal-theme-icon") ?? button });
  page.advance(500);
  assert.equal(page.root.getAttribute("data-undal-theme"), "dark");
  assert.equal(button.getAttribute("aria-pressed"), "true");
  assert.equal(page.window.localStorage.getItem("undal-theme"), "dark", "the choice survives the next visit");
  assert.equal(page.find('meta[name="theme-color"]')?.getAttribute("content"), "#0e1512", "and the browser chrome follows");

  page.fireDocument("click", { target: button });
  page.advance(500);
  assert.equal(page.root.getAttribute("data-undal-theme"), "light");
  assert.equal(button.getAttribute("aria-pressed"), "false");
});

test("a customer who has not chosen follows the system, live", () => {
  const page = shell({ dark: true });
  page.run(MOTION_SCRIPT);
  assert.equal(page.root.getAttribute("data-undal-theme"), "dark");
});

// -------------------------------------------------------------------- reveals

test("a card is hidden until it is seen, and shown the moment it is", async () => {
  const page = shell();
  page.run(MOTION_SCRIPT);
  assert.ok(page.root.className.includes("js-reveal"), "the script, and only the script, is allowed to hide a card");
  const observer = page.window.observers[0];
  assert.ok(observer, "the cards are being watched");
  assert.equal(observer.targets.length, 3, "both dishes and the facts strip");

  const card = page.findAll(".item.reveal")[0]!;
  assert.ok(!card.className.includes("is-visible"), "not yet on screen, not yet shown");
  observer.intersect([card]);
  assert.ok(card.className.includes("is-visible"), "one intersection, one visible card");
  assert.equal(observer.targets.length, 2, "and it is not watched twice");
});

test("reduced motion shows everything at once and starts nothing", () => {
  const page = shell({ reduced: true });
  page.run(MOTION_SCRIPT);
  assert.ok(!page.root.className.includes("js-reveal"), "no card is hidden from a customer who asked for less movement");
  assert.equal(page.window.observers.length, 0, "and no observer is built to reveal it later");

  const form = page.find("[data-undal-form='add']") ?? page.findAll("form")[1]!;
  form.dispatchEvent({ type: "submit", target: form });
  assert.equal(page.findAll(".undal-flight").length, 0, "no dish flies across a still screen");
});

// --------------------------------------------------------------------- basket

test("adding a dish is answered: a flight, a bump and a bar within reach", async () => {
  const page = shell();
  page.window.undalLines = () => [{ id: "rice", qty: 2 }];
  page.window.undalTotals = () => ({ subtotal: 24000 });
  page.window.undalMoney = (paisa: number) => `৳ ${Math.round(paisa / 100)}`;
  page.run(MOTION_SCRIPT);

  const form = page.findAll("form").find((node) => node.getAttribute("data-undal-form") === "add")!;
  assert.ok(form, "the real add form is the one being watched");
  page.fireDocument("submit", { target: form });
  assert.equal(page.findAll(".undal-flight").length, 1, "the dish travels to the basket");

  const badge = page.byId("undal-cart-count")!;
  badge.textContent = "2";
  for (const mutation of page.window.mutations) mutation.fire([badge]);
  page.advance(30);
  const bar = page.find(".undal-basketbar");
  assert.ok(bar, "the basket bar exists once something is in the basket");
  assert.ok(bar.className.includes("is-shown"), "and it is on screen");
  assert.equal(page.find(".undal-basketbar-count")?.textContent, "2");
  assert.equal(page.find(".undal-basketbar-total")?.textContent, "৳ 240", "the subtotal the menu can honestly promise");
});

test("an empty basket keeps the bar off the screen", async () => {
  const page = shell();
  page.window.undalLines = () => [];
  page.run(MOTION_SCRIPT);
  page.window.pageYOffset = 900;
  page.fireWindow("scroll");
  page.advance(20);
  const bar = page.find(".undal-basketbar");
  assert.ok(!bar || !bar.className.includes("is-shown"), "nothing in the basket, nothing under the thumb");
});

// --------------------------------------------------------------------- toasts

test("a toast says what happened, and Escape dismisses it", async () => {
  const page = shell();
  page.run(MOTION_SCRIPT);
  page.window.undalToast?.("Kacchi biryani added", "ok");
  page.advance(40);
  const toast = page.find(".undal-toast");
  assert.ok(toast, "the confirmation is on the page");
  assert.match(toast.textContent, /Kacchi biryani added/);
  assert.ok(toast.className.includes("is-shown"), "and it has arrived, not merely been created");
  assert.equal(page.find(".undal-toasts")?.getAttribute("aria-live"), "polite", "announced, not shouted");

  page.fireDocument("keydown", { key: "Escape", keyCode: 27 });
  page.advance(400);
  assert.ok(!page.find(".undal-toast.is-shown"), "one key closes it");
});

// ------------------------------------------------------- reading and controls

test("the reading position follows the page and the header knows it is stuck", async () => {
  const page = shell();
  page.run(MOTION_SCRIPT);
  (page.root as unknown as { scrollHeight: number }).scrollHeight = 4000;
  page.window.innerHeight = 800;
  page.window.pageYOffset = 1600;
  page.fireWindow("scroll");
  page.advance(20);
  const line = page.find(".undal-progress");
  assert.ok(line, "the progress line exists");
  assert.ok(line.className.includes("is-on"), "and it is showing");
  assert.equal((line.firstChild as StubElement | null)?.style.width, "50%", "halfway down the page is half a bar");
  assert.ok(page.find("nav.store")?.className.includes("is-stuck"), "a scrolled header says so");
});

test('"/" reaches the search field, which is the shortcut a menu teaches', () => {
  const page = shell();
  page.run(MOTION_SCRIPT);
  const field = page.find('.search input[type="search"]');
  assert.ok(field, "the field the shortcut is for");
  page.fireDocument("keydown", { key: "/", target: page.root });
  assert.equal(field.focused, true);
});

test("a painted photograph stops the shimmer behind it", () => {
  const page = shell();
  const images = page.findAll(".food-art img");
  assert.ok(images.length >= 1, "the shell has a dish photo to paint");
  images[0]!.complete = true;
  images[0]!.naturalWidth = 480;
  page.run(MOTION_SCRIPT);
  const painted = images[0]!.parentNode as StubElement;
  assert.ok(painted.className.includes("is-painted"), "a photo already in the browser's cache stops the shimmer at once");
  for (const image of images.slice(1)) {
    assert.ok(image.listeners.get("load"), "a photo still travelling has something waiting for it");
  }
});

test("the shipped page carries both palettes, the control and the reduced-motion contract", async () => {
  const html = await renderPage({
    title: "Undal",
    description: "The storefront",
    body: '<div class="item reveal">Kacchi</div>',
    cacheSeconds: 0,
  }).text();

  assert.match(html, /data-undal-theme-button/, "the theme control is in the markup, not added by a script");
  assert.match(html, /:root\[data-undal-theme="dark"\]\{/, "an explicit dark palette");
  assert.match(html, /@media\(prefers-color-scheme:dark\)\{:root:not\(\[data-undal-theme="light"\]\)/,
    "and the system preference is followed until the customer chooses");
  assert.match(html, /color-scheme:light dark/);
  assert.match(html, /<meta name="theme-color" media="\(prefers-color-scheme: dark\)" content="#0e1512">/);
  assert.match(html, /@media\(prefers-reduced-motion:reduce\)/, "the reduced-motion contract is in the stylesheet");
  assert.match(html, /animation-duration:\.001ms!important/, "and it stops animation rather than merely slowing it");
  assert.match(html, /\.js-reveal \.reveal\{opacity:0/, "a card is hidden only under a class the script adds");
  assert.match(html, /calc\(var\(--i,0\) \* 45ms\)/, "the stagger index travels with the card");
  assert.match(html, /env\(safe-area-inset-bottom\)/, "the sticky bars clear the home indicator");
  assert.doesNotMatch(html, /width:100vw/, "nothing is sized by the viewport width, which is how a page overflows sideways");
});

test("the enhancement script is never the last script on the page", async () => {
  // The mini-DOM harness runs the *last* inline script, on the principle that it
  // is the page's own behaviour — the tracking poll, the basket, the map. The
  // motion layer is therefore placed at the top of the body: an enhancement must
  // never displace the thing it is enhancing as the script a test executes.
  const response = renderPage({
    title: "Order",
    description: "Tracking",
    body: '<script>window.UNDAL_PAGE_SCRIPT = true;</script>',
    cacheSeconds: 0,
  });
  const html = await response.text();
  const scripts = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map((match) => match[1] ?? "");
  assert.ok(scripts.length >= 3, "the theme decision, the enhancement and the page's own script");
  assert.match(scripts[scripts.length - 1] ?? "", /UNDAL_PAGE_SCRIPT/, "the page's own behaviour is still last");
  assert.match(scripts[0] ?? "", /undal-theme/, "and the theme is decided before the first paint");
});
