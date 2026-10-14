#!/usr/bin/env node
/**
 * Undal — web/app rule parity gate (PART P).
 *
 * The storefront prices orders on the Worker, formats money in the Worker and
 * again in the browser, measures distances in the browser and in the Worker,
 * and validates the same phone numbers, names and coupons the Android app does.
 * Four implementations of one set of rules is four chances to drift apart, and
 * a drifting price is a customer arguing with the kitchen at the door.
 *
 * So the vectors are not written here twice. They are read out of the app's own
 * unit tests — MoneyTest.kt, DistanceTest.kt, ValidatorsTest.kt — and pushed
 * through the shipping JavaScript. If the app and the web ever disagree, this
 * file fails in CI before either of them reaches a customer.
 *
 * Rules of this file:
 *   • every value is taken from the Kotlin source at run time; nothing is retyped
 *   • the compared code is the code that ships — extracted from the real files,
 *     never a copy kept in this directory
 *   • an assertion in those Kotlin tests that this gate cannot classify or
 *     declare as app-only fails the run, so coverage cannot shrink silently
 *   • zero dependencies, so the workflow needs nothing but Node
 *
 * The Worker's TypeScript is imported directly — Node 22.18 and newer run `.ts`
 * files as they are (type stripping), so the modules under test are the modules
 * that deploy. The browser copies inside the Worker's template literals are
 * extracted verbatim instead, since only a browser can import them normally.
 *
 * Run it by hand:  node tools/check-web-rules.mjs
 */
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const read = (relative) => readFileSync(join(ROOT, relative), "utf8");

// ---------------------------------------------------------------------------
// 1 · reading Kotlin and JavaScript
// ---------------------------------------------------------------------------

/** Index of the first character after `// comment` or `/* comment *\/`. */
function skipComment(source, index) {
  if (source[index + 1] === "/") {
    const end = source.indexOf("\n", index);
    return end < 0 ? source.length : end;
  }
  const end = source.indexOf("*/", index);
  return end < 0 ? source.length : end + 2;
}

function isRegexStart(previous) {
  return previous === "" || "(,=:[!&|?{};+-*%~^<>".includes(previous);
}

/** Walks past a string, template or regex literal and returns the index after it. */
function skipLiteral(source, index) {
  const quote = source[index];
  if (quote === "/") {
    let i = index + 1;
    let inClass = false;
    for (; i < source.length; i++) {
      const ch = source[i];
      if (ch === "\\") i += 1;
      else if (ch === "[") inClass = true;
      else if (ch === "]") inClass = false;
      else if (ch === "/" && !inClass) return i + 1;
      else if (ch === "\n") return i;
    }
    return i;
  }
  let i = index + 1;
  let interpolations = 0;
  for (; i < source.length; i++) {
    const ch = source[i];
    if (ch === "\\") i += 1;
    else if (quote === "`" && ch === "$" && source[i + 1] === "{") {
      interpolations += 1;
      i += 1;
    } else if (quote === "`" && ch === "}" && interpolations > 0) interpolations -= 1;
    else if (ch === quote && interpolations === 0) return i + 1;
    else if (quote !== "`" && ch === "\n") return i;
  }
  return i;
}

/**
 * Pulls one `function name(...) { … }` (or `export function`) out of a source
 * file, character for character, so the gate tests the shipped text. Functions
 * with an inline object return type are out of scope: this is for the small
 * arithmetic and validation helpers, which all return scalars.
 */
export function extractFunction(source, name, label = name) {
  const pattern = new RegExp(`(?:^|[^.\\w$])function\\s+${name}\\s*\\(`);
  const match = pattern.exec(source);
  if (!match) throw new Error(`${label}: function ${name}() was not found`);
  let i = source.indexOf("(", match.index + match[0].length - 1);
  let depth = 0;
  for (; i < source.length; i++) {
    if (source[i] === "(") depth += 1;
    else if (source[i] === ")") {
      depth -= 1;
      if (depth === 0) {
        i += 1;
        break;
      }
    }
  }
  while (i < source.length && source[i] !== "{") i += 1;
  const body = matchBody(source, i);
  if (body === null) throw new Error(`${label}: function ${name}() has unbalanced braces`);
  return source.slice(match.index, body);
}

function matchBody(source, openIndex) {
  let depth = 0;
  let previous = "";
  let i = openIndex;
  while (i < source.length) {
    const ch = source[i];
    if (ch === "/" && (source[i + 1] === "/" || source[i + 1] === "*")) {
      i = skipComment(source, i);
      continue;
    }
    if (ch === '"' || ch === "'" || ch === "`") {
      i = skipLiteral(source, i);
      previous = '"';
      continue;
    }
    if (ch === "/" && isRegexStart(previous)) {
      i = skipLiteral(source, i);
      previous = "/";
      continue;
    }
    if (ch === "{") depth += 1;
    else if (ch === "}") {
      depth -= 1;
      if (depth === 0) return i + 1;
    }
    if (!/\s/.test(ch)) previous = ch;
    i += 1;
  }
  return null;
}

/** Pulls a one-line constant declaration out of a source file. */
export function extractConst(source, name, label = name) {
  const pattern = new RegExp(`(?:export )?const ${name}\\s*=\\s*[^;\\n]+;`);
  const match = pattern.exec(source);
  if (!match) throw new Error(`${label}: const ${name} was not found`);
  return match[0];
}

/**
 * Pulls a `var NAME = …;` declaration out of a script, brace-balanced.
 *
 * The storefront's browser script builds a couple of values once, at the top of
 * its IIFE, and the functions below them read those values from the enclosing
 * scope. A function extracted on its own is therefore missing them — this is how
 * that is supplied, from the shipping text, rather than by retyping the value
 * here (which is exactly the drift this gate exists to prevent).
 */
export function extractVar(source, name, label = name) {
  const start = new RegExp(`var\\s+${name}\\s*=`).exec(source);
  if (!start) throw new Error(`${label}: var ${name} was not found`);
  const end = matchBrace(source, start.index + start[0].length);
  if (end === null) throw new Error(`${label}: var ${name} is not a complete declaration`);
  return source.slice(start.index, end);
}

/**
 * `window.undalX = function (…) {…};` — a function the page script exports.
 *
 * Rewritten to a plain declaration as it is pulled out, so the shipping text can
 * be compiled in Node without a `window`: the body is untouched, only the
 * assignment target changes.
 */
export function extractWindowFunction(source, name, label = name) {
  const start = new RegExp(`window\\.${name}\\s*=\\s*function`).exec(source);
  if (!start) throw new Error(`${label}: window.${name} was not found`);
  const end = matchBrace(source, start.index + start[0].length);
  if (end === null) throw new Error(`${label}: window.${name} is not a complete function`);
  const declaration = source.slice(start.index, end);
  // `window.undalX = function (…) {…};` → `var undalX = function (…) {…};`
  return declaration.replace(/^window\.[A-Za-z0-9_]+\s*=\s*/, `var ${name} = `);
}

/** Index just past the balanced `;` of a declaration, or null. */
function matchBrace(source, from) {
  let depth = 0;
  let quote = null;
  for (let i = from; i < source.length; i += 1) {
    const ch = source[i];
    if (quote) {
      if (ch === "\\") i += 1;
      else if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === "`") {
      quote = ch;
      continue;
    }
    if (ch === "(" || ch === "{" || ch === "[") depth += 1;
    else if (ch === ")" || ch === "}" || ch === "]") depth -= 1;
    else if (ch === ";" && depth <= 0) return i + 1;
  }
  return null;
}

/**
 * The browser scripts live inside template literals in the Worker's source, so
 * a `\\u09F3` in the file is a `\u09F3` in the browser. Undoing that one step
 * lets the gate run the exact text the browser receives.
 */
function emittedBrowserJs(text) {
  return text.replace(/\\\\/g, "\\");
}

/**
 * Evaluates extracted declarations and hands back the functions by name. The
 * `export` keyword comes off first: the text is the shipped text, only without
 * the module syntax that a function body cannot carry.
 */
function build(names, ...declarations) {
  const body = declarations
    .map((text) => text.replace(/(^|[;}\n])\s*export\s+(?=(?:const|function|let|var)\b)/g, "$1 "))
    .join("\n");
  // eslint-disable-next-line no-new-func
  const factory = new Function(`${body}\nreturn { ${names.join(", ")} };`);
  return factory();
}

// ---------------------------------------------------------------------------
// 2 · the Kotlin assertions, as data
// ---------------------------------------------------------------------------

const number = (literal) => {
  // Kotlin writes Long literals with a trailing L and groups digits with `_`.
  const cleaned = String(literal).replace(/_/g, "").trim().replace(/L$/, "");
  if (cleaned === "Double.NaN") return Number.NaN;
  if (cleaned === "Double.POSITIVE_INFINITY") return Number.POSITIVE_INFINITY;
  if (cleaned === "null") return null;
  const value = Number(cleaned);
  if (Number.isNaN(value) || !Number.isFinite(value)) {
    throw new Error(`cannot read the Kotlin number literal "${literal}"`);
  }
  return value;
};

/** Splits `a, b, "c d"` on the top-level commas only. */
function splitArguments(text) {
  const parts = [];
  let current = "";
  let quote = null;
  for (const ch of text) {
    if (quote) {
      current += ch;
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"') {
      quote = ch;
      current += ch;
      continue;
    }
    if (ch === ",") {
      parts.push(current.trim());
      current = "";
      continue;
    }
    current += ch;
  }
  if (current.trim()) parts.push(current.trim());
  return parts;
}

const stringLiteral = (text) => {
  const trimmed = text.trim();
  if (!trimmed.startsWith('"')) throw new Error(`expected a Kotlin string, got ${trimmed}`);
  return trimmed.slice(1, -1);
};

/**
 * The index just past the `)` that closes the `(` at `openIndex`. An assertion
 * is a call, not a block, and Kotlin's `fun assertKm(…) { … }` helper is a
 * definition rather than a check — both facts matter here.
 */
function matchParens(source, openIndex) {
  let depth = 0;
  let previous = "";
  let i = openIndex;
  while (i < source.length) {
    const ch = source[i];
    if (ch === "/" && (source[i + 1] === "/" || source[i + 1] === "*")) {
      i = skipComment(source, i);
      continue;
    }
    if (ch === '"' || ch === "'" || ch === "`") {
      i = skipLiteral(source, i);
      previous = '"';
      continue;
    }
    if (ch === "(") depth += 1;
    else if (ch === ")") {
      depth -= 1;
      if (depth === 0) return i + 1;
    }
    if (!/\s/.test(ch)) previous = ch;
    i += 1;
  }
  return null;
}

/**
 * Every `assert…(…)` call in a Kotlin test file, with its whole span. The
 * `fun assertKm(…)` helper definitions are skipped: their bodies are harness
 * messages, not vectors.
 */
function assertions(source) {
  const spans = [];
  const pattern = /assert[A-Za-z]*\(/g;
  let match;
  while ((match = pattern.exec(source)) !== null) {
    const open = match.index + match[0].length - 1;
    const before = source.slice(Math.max(0, match.index - 8), match.index);
    const end = matchParens(source, open);
    if (end === null) throw new Error("unbalanced assertion in a Kotlin test file");
    if (!/fun\s+$/.test(before)) {
      spans.push({ text: source.slice(match.index, end), start: match.index, end });
    }
    pattern.lastIndex = end;
  }
  return spans;
}

/** The first regex match inside one assertion text, or null. */
function pick(text, pattern) {
  const match = pattern.exec(text);
  return match ? match.slice(1) : null;
}

/** `assertEquals(…, Money.format(N))` → { amount, expected }. */
function readFormatCalls(text) {
  const found = pick(text, /assertEquals\("((?:[^"\\]|\\.)*)",\s*Money\.format\(([\d_]+)\)\)/);
  return found ? { expected: found[0], amount: number(found[1]) } : null;
}

/** `assertEquals(…, Money.<fn>(args))` → { args, expected }. */
/** `assertEquals(…L, Money.<fn>(arg, arg))` → { args, expected }. */
function readCallOccurrences(text, fn) {
  const pattern = new RegExp(`assertEquals\\(([\\d_]+)L?,\\s*${fn}\\(([^)]*)\\)`);
  const found = pick(text, pattern);
  return found ? { expected: number(found[0]), args: splitArguments(found[1]) } : null;
}

// ---------------------------------------------------------------------------
// 3 · the shipping implementations
// ---------------------------------------------------------------------------

const MONEY = "worker/src/util/money.ts";
const CART = "worker/src/web/cart.ts";
const CART_CLIENT = "worker/src/web/cart-client.ts";
const STORE_PAGES = "worker/src/web/store-pages.ts";
// The distance a customer is charged for is measured on the map they ticked their
// pin on, so the browser's haversine now ships with the map rather than inside a
// page template. Same function, same vectors, new home.
const MAP_CLIENT = "worker/src/web/map-client.ts";

const cartClientSource = emittedBrowserJs(read(CART_CLIENT));
const storePagesSource = emittedBrowserJs(read(STORE_PAGES));
const mapClientSource = emittedBrowserJs(read(MAP_CLIENT));

// The Worker modules are imported as they ship. Node strips the types; a Node
// too old for that says so instead of silently checking nothing.
let money;
let cart;
let couponModule;
try {
  money = await import(new URL("../worker/src/util/money.ts", import.meta.url));
  cart = await import(new URL("../worker/src/web/cart.ts", import.meta.url));
  couponModule = await import(new URL("../worker/src/web/coupons.ts", import.meta.url));
} catch (error) {
  console.error(
    "This gate imports the Worker's TypeScript directly, which needs Node 22.18 or newer " +
      "(type stripping is on by default).",
  );
  console.error(String(error && error.message ? error.message : error));
  process.exit(1);
}

for (const name of [
  "formatPaisa",
  "deliveryFeePaisa",
  "percentDiscountPaisa",
  "lineTotalPaisa",
  "grandTotalPaisa",
  "applicableDiscountPaisa",
  "haversineKm",
  "distanceFromStorePinned",
  "isWithinRadius",
  "formatKm",
  "normalizeBdMobile",
  "normalizeCoupon",
  "usableText",
  "usableName",
]) {
  const module = name === "formatPaisa" ? money : cart;
  if (typeof module[name] !== "function") {
    throw new Error(`${name}() is gone from the Worker — the web cannot be checked against the app`);
  }
}

for (const name of ["couponDiscountPaisa", "evaluateCoupon", "parseMinutesOfDay", "withinDailyWindow"]) {
  if (typeof couponModule[name] !== "function") {
    throw new Error(`${name}() is gone from worker/src/web/coupons.ts — the coupon rule can no longer be checked`);
  }
}

const browser = build(
  [
    "money",
    "haversine",
    "usablePhone",
    "usableText",
    "usableName",
    "parseMinutes",
    "withinWindow",
    "discountOf",
    "undalEvaluateCoupon",
  ],
  // The script's own letter test is read with the functions that use it, so the
  // gate runs the shipping rule — including the fallback an older WebView needs.
  extractVar(cartClientSource, "NAME_LETTER", CART_CLIENT),
  extractFunction(cartClientSource, "groupIndian", CART_CLIENT),
  extractFunction(cartClientSource, "money", CART_CLIENT),
  extractFunction(cartClientSource, "usablePhone", CART_CLIENT),
  extractFunction(cartClientSource, "usableText", CART_CLIENT),
  extractFunction(cartClientSource, "usableName", CART_CLIENT),
  // The coupon copies the browser runs: the same three functions the storefront
  // calls, read out of the shipping text.
  extractFunction(cartClientSource, "parseMinutes", CART_CLIENT),
  extractFunction(cartClientSource, "withinWindow", CART_CLIENT),
  extractFunction(cartClientSource, "discountOf", CART_CLIENT),
  extractWindowFunction(cartClientSource, "undalEvaluateCoupon", CART_CLIENT),
  extractFunction(mapClientSource, "haversine", MAP_CLIENT),
);

/** The fee the basket page computes, taken from the expression it really uses. */
function browserFee() {
  const guard = /store\.perKmPaisa > 0 \? (Math\.ceil\(km\) \* store\.perKmPaisa) : 0/.exec(
    cartClientSource,
  );
  if (!guard) {
    throw new Error(
      `${CART_CLIENT}: the basket fee expression changed shape — teach this gate the new one`,
    );
  }
  return build(
    ["fee"],
    `function fee(km, store) { return km > 0 && store.perKmPaisa > 0 ? ${guard[1]} : 0; }`,
  ).fee;
}

const browserFeePaisa = browserFee();

/** The browser's haversine takes two points; the vectors carry four numbers. */
const browserHaversineKm = (lat1, lng1, lat2, lng2) =>
  browser.haversine({ lat: lat1, lng: lng1 }, { lat: lat2, lng: lng2 });

// ---------------------------------------------------------------------------
// 4 · the families the web shares with the app
// ---------------------------------------------------------------------------

const TOLERANCE_KM = 1e-6;
const results = [];
const compare = (family, actual, expected, site) => {
  const outcome = {
    family,
    site,
    actual,
    expected,
    ok: Object.is(actual, expected) || (typeof expected === "number" && Math.abs(actual - expected) <= TOLERANCE_KM),
  };
  if (!outcome.ok) results.push(outcome);
  return outcome;
};

function run(family, vectors, sites, { minimum }) {
  if (vectors.length < minimum) {
    throw new Error(
      `${family}: read ${vectors.length} vectors from the Kotlin tests but ${minimum} are expected — ` +
        `were assertions deleted or renamed?`,
    );
  }
  let checked = 0;
  for (const vector of vectors) {
    for (const [name, fn] of Object.entries(sites)) {
      compare(family, fn(vector), vector.expect, name);
      checked += 1;
    }
  }
  return { family, vectors: vectors.length, sites: Object.keys(sites).length, checked };
}

/** `assertEquals("…", Distance.haversineKm(a, b, c, d), tol?)`. */
function haversineVectors(source) {
  const pattern =
    /assertKm\(\s*([\d.]+),\s*Distance\.haversineKm\(([^)]*)\)\s*(?:,\s*([\d.]+)\s*)?\)/g;
  const vectors = [];
  let match;
  while ((match = pattern.exec(source)) !== null) {
    const [lat1, lng1, lat2, lng2] = splitArguments(match[2]).map(number);
    vectors.push({ args: [lat1, lng1, lat2, lng2], expect: number(match[1]), tolerance: number(match[3] ?? "0.1") });
  }
  return vectors;
}

/** `assertNull(Distance.distanceFromStore(a, b, c, d))` — a null pin is the unset marker. */
function unpinnedVectors(source) {
  const pattern = /assertNull\(Distance\.distanceFromStore\(([^)]*)\)\)/g;
  const vectors = [];
  let match;
  while ((match = pattern.exec(source)) !== null) {
    const args = splitArguments(match[1]).map((part) => (part.trim() === "null" ? 0 : number(part)));
    vectors.push({ args, expect: null });
  }
  return vectors;
}

/** `assertEquals(0.0, Distance.distanceFromStore(…) ?: -1.0, tol)`. */
function pinnedVectors(source) {
  const pattern =
    /assertEquals\(\s*([\d.]+),\s*Distance\.distanceFromStore\(([^)]*)\)\s*\?: -1\.0,\s*([\d.]+)\s*\)/g;
  const vectors = [];
  let match;
  while ((match = pattern.exec(source)) !== null) {
    const args = splitArguments(match[2]).map(number);
    vectors.push({ args, expect: number(match[1]), tolerance: number(match[3]) });
  }
  return vectors;
}

function radiusVectors(source, expected) {
  const pattern = new RegExp(
    `assert${expected ? "True" : "False"}\\(Distance\\.isWithinRadius\\(([^)]*)\\)\\)`,
    "g",
  );
  const vectors = [];
  let match;
  while ((match = pattern.exec(source)) !== null) {
    vectors.push({ args: splitArguments(match[1]).map(number), expect: expected });
  }
  return vectors;
}

function formatKmVectors(source) {
  const pattern = /assertEquals\("([^"]+)",\s*Distance\.formatKm\(([\d.]+)\)\)/g;
  const vectors = [];
  let match;
  while ((match = pattern.exec(source)) !== null) {
    vectors.push({ args: [number(match[2])], expect: match[1] });
  }
  return vectors;
}

/**
 * `assertEquals("01712345678", Validators.normalizeBdMobile("…"))` and
 * `assertNull(Validators.normalizeBdMobile("…"))` — the app returns the
 * eleven-digit form or nothing, and so must the web.
 */
function phoneVectors(source) {
  const vectors = [];
  const equal = /assertEquals\("([^"]+)",\s*Validators\.normalizeBdMobile\("([^"]*)"\)\)/g;
  const none = /assertNull\(Validators\.normalizeBdMobile\("([^"]*)"\)\)/g;
  let match;
  while ((match = equal.exec(source)) !== null) {
    vectors.push({ args: [match[2]], expect: match[1] });
  }
  while ((match = none.exec(source)) !== null) {
    vectors.push({ args: [match[1]], expect: null });
  }
  return vectors;
}

/**
 * `assertNull(Validators.name("…"))` means the app accepts the value and
 * `assertNotNull(…)` means it refuses it. The argument is a Kotlin string, or
 * the `"x".repeat(120)` shape the length test uses.
 */
function readValidatorVector(text, expression) {
  const pattern = new RegExp(`^assert(Null|NotNull)\\(${expression}\\((.*)\\)\\)$`, "s");
  const match = pattern.exec(text.trim());
  if (!match) return null;
  const argument = match[2];
  const plain = /^"([^"]*)"$/.exec(argument);
  const repeated = /^"([^"]*)"\.repeat\((\d+)\)$/.exec(argument);
  const value = plain ? plain[1] : repeated ? repeated[1].repeat(Number(repeated[2])) : null;
  if (value === null) throw new Error(`cannot read the Kotlin validator vector ${text}`);
  return { args: [value], expect: match[1] === "Null" };
}

function validatorVectors(source, expression) {
  return assertions(source)
    .map((span) => readValidatorVector(span.text, expression))
    .filter(Boolean);
}

// ---------------------------------------------------------------------------
// 5 · what the app owns alone, declared out loud
// ---------------------------------------------------------------------------

/**
 * Assertions this gate deliberately does not run against the web, with the
 * reason. A Kotlin assertion that matches none of these and none of the shared
 * families fails the run — so the only way to add an unchecked rule is to say
 * here, in review, why the web does not own it.
 */
const APP_ONLY = [
  {
    what: "Money.parseTakaToPaisa / the parse-format round trip",
    pattern: /Money\.parseTakaToPaisa/,
    why: "the web never parses typed money — prices come from Firestore as paisa",
  },
  {
    what: "Validators.email, password, passwordConfirmation, assessPassword",
    pattern: /Validators\.(email|password|passwordConfirmation|assessPassword)\(/,
    why: "accounts are app-only; the web checkout is anonymous by design",
  },
  {
    what: "Validators.rating",
    pattern: /Validators\.rating\(/,
    why: "spice levels are chosen in the app; the web basket carries no ratings",
  },
  {
    what: "the assertKm helper's own message",
    pattern: /expected \$expected km but was \$actual km/,
    why: "that is the tolerance helper's text; the vectors it drives are checked above",
  },
  {
    what: "CouponMath.pickBest (the app's own choice of automatic coupon)",
    pattern: /CouponMath\.pickBest\(|assertEquals\("soon"|assertEquals\(5_000L, best\?\.discountPaisa\)/,
    why:
      "the Android cart picks its own best coupon from the board and the server re-prices every order " +
      "before it is written, so the JS copies of that choice are proven by the Worker's own tests",
  },
  {
    what: "CouponMath headline and condition line",
    pattern: /CouponMath\.(headline|conditionLine)\(/,
    why: "the wording is local to each surface: the app writes it in Kotlin, the page in its own script",
  },
  {
    what: "the coupon spec harness's own assertions",
    pattern:
      /coupon\.(type|value|active|autoApply|daysOfWeek|startsAt|endsAt|expiresAtMillis|minSubtotalAmountPaisa|maxDiscountPaisa|usageLimit|usedCount)\b|clock\.(weekday|minutes|now)\b|clockOf\(|assertFalse\(couponOf|CouponMath\.Clock|CouponMath\.expiryMillis\(/,
    why:
      "these prove the test's own spec reader and the Asia/Dhaka clock (the Worker does that in couponClock()); " +
      "the coupon rules themselves are the three families above",
  },
];

/**
 * Coupon vectors, read out of CouponMathTest.kt.
 *
 * Three tables, three families: the money (`assertDiscount`), the eligibility
 * verdicts (`assertCoupon`) and the daily windows (`assertWindow`). The same
 * characters are replayed through the Worker's `coupons.ts` and through the
 * storefront script, which is what makes "one rule, three copies" a fact rather
 * than a comment.
 */
function discountVectors(source) {
  const pattern =
    /assertDiscount\(\s*"([a-z]+)"\s*,\s*(-?[\d_]+)\s*,\s*(-?[\d_]+)\s*,\s*(-?[\d_]+)\s*,\s*(-?[\d_]+)\s*,\s*(-?[\d_]+)\s*\)/g;
  const vectors = [];
  let match;
  while ((match = pattern.exec(source)) !== null) {
    vectors.push({
      type: match[1],
      value: number(match[2]),
      cap: number(match[3]),
      subtotal: number(match[4]),
      delivery: number(match[5]),
      expect: number(match[6]),
    });
  }
  return vectors;
}

function couponVectors(source) {
  const pattern =
    /assertCoupon\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*(-?[\d_]+)\s*,\s*(-?[\d_]+)\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*(-?[\d_]+)\s*\)/g;
  const vectors = [];
  let match;
  while ((match = pattern.exec(source)) !== null) {
    vectors.push({
      spec: match[1],
      subtotal: number(match[2]),
      delivery: number(match[3]),
      reason: match[4],
      expect: number(match[5]),
    });
  }
  return vectors;
}

function windowVectors(source) {
  const pattern = /assertWindow\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*([^,]+?)\s*,\s*(true|false)\s*\)/g;
  const vectors = [];
  let match;
  while ((match = pattern.exec(source)) !== null) {
    const expression = match[3].trim();
    if (!/^[\d\s*+]+$/.test(expression)) {
      throw new Error(`CouponMathTest.kt: "${expression}" is not a minute expression this gate can read`);
    }
    vectors.push({
      from: match[1],
      to: match[2],
      minutes: Function(`return ${expression}`)(),
      expect: match[4] === "true",
    });
  }
  return vectors;
}

/** One `key=value` out of a coupon spec, or "". Mirrors the Kotlin `specValue`. */
function specValue(spec, key) {
  for (const part of spec.split(";")) {
    const trimmed = part.trim();
    if (!trimmed) continue;
    if (trimmed.split("=")[0].trim() !== key) continue;
    return trimmed.split("=").slice(1).join("=").trim();
  }
  return "";
}

/** The spec grammar, written twice on purpose: Kotlin builds it, this rebuilds it. */
function couponFromSpec(spec, nowMillis) {
  const expiry = specValue(spec, "exp");
  return {
    code: specValue(spec, "code") || "TEST",
    type: specValue(spec, "type") || "percent",
    value: Number(specValue(spec, "value") || 20),
    active: specValue(spec, "active") !== "0",
    minSubtotalAmountPaisa: Number(specValue(spec, "min") || 0),
    maxDiscountPaisa: Number(specValue(spec, "cap") || 0),
    usageLimit: Number(specValue(spec, "uses") || 0),
    usedCount: Number(specValue(spec, "used") || 0),
    startsAt: specValue(spec, "from"),
    endsAt: specValue(spec, "to"),
    daysOfWeek: specValue(spec, "days")
      .split(",")
      .map((day) => day.trim())
      .filter(Boolean),
    expiresAt:
      expiry === "expired" ? nowMillis - 60_000 : expiry === "future" ? nowMillis + 3_600_000 : 0,
    expiresAtMillis:
      expiry === "expired" ? nowMillis - 60_000 : expiry === "future" ? nowMillis + 3_600_000 : 0,
  };
}

/**
 * The clock half of the spec.
 *
 * The Kotlin test pins the instant; this reads the wall clock, because the
 * relative vectors ("expired" is a minute ago, "future" is an hour away) mean the
 * same thing at any hour, and a vector table that only ran at 3 a.m. would be a
 * table nobody trusted.
 */
function couponContextFromSpec(spec, subtotal, delivery, nowMillis) {
  const at = specValue(spec, "at");
  return {
    subtotalPaisa: subtotal,
    deliveryPaisa: delivery,
    weekday: specValue(spec, "weekday") || "wed",
    minutes: at ? couponModule.parseMinutesOfDay(at) : 12 * 60,
    now: nowMillis,
  };
}

/** The composed test at the end of DistanceTest: haversine → fee → whole taka. */
function compositionVectors(source) {
  const pattern =
    /val km = Distance\.haversineKm\(([^)]*)\)\s*\n\s*val fee = Money\.deliveryFeePaisa\(km, perKmPaisa = ([\d_]+)\)\s*\n\s*\/\/[^\n]*\n\s*assertTrue\("unexpected fee \$fee", fee in ([\d_]+)L\.\.([\d_]+)L\)\s*\n\s*assertEquals\(0L, fee % 100\)/;
  const match = pattern.exec(source);
  if (!match) {
    throw new Error("DistanceTest.kt: the fee-and-distance composition test changed shape");
  }
  return [
    {
      args: splitArguments(match[1]).map(number),
      perKmPaisa: number(match[2]),
      low: number(match[3]),
      high: number(match[4]),
    },
  ];
}

// ---------------------------------------------------------------------------
// 6 · the run
// ---------------------------------------------------------------------------

const moneyTest = read("app/src/test/java/com/undal/syl/app/core/util/MoneyTest.kt");
const couponTest = read("app/src/test/java/com/undal/syl/app/core/util/CouponMathTest.kt");
const distanceTest = read("app/src/test/java/com/undal/syl/app/core/util/DistanceTest.kt");
const validatorsTest = read("app/src/test/java/com/undal/syl/app/core/util/ValidatorsTest.kt");

const summary = [];

summary.push(
  run(
    "money.format",
    assertions(moneyTest)
      .map((span) => readFormatCalls(span.text))
      .filter(Boolean)
      .map((vector) => ({ amount: vector.amount, expect: vector.expected })),
    {
      "worker money.ts · formatPaisa": (v) => money.formatPaisa(v.amount),
      "browser cart-client.ts · money": (v) => browser.money(v.amount),
    },
    { minimum: 11 },
  ),
);

const feeFromMoney = assertions(moneyTest)
  .map((span) => readCallOccurrences(span.text, "Money\\.deliveryFeePaisa"))
  .filter(Boolean)
  .map((vector) => ({ args: vector.args.map(number), expect: vector.expected }));

summary.push(
  run(
    "money.deliveryFeePaisa",
    feeFromMoney,
    {
      "worker money.ts · deliveryFeePaisa": (v) => money.deliveryFeePaisa(...v.args),
      "worker cart.ts · deliveryFeePaisa": (v) => cart.deliveryFeePaisa(...v.args),
      "browser cart-client.ts · basket fee": (v) => browserFeePaisa(v.args[0], { perKmPaisa: v.args[1] }),
    },
    { minimum: 6 },
  ),
);

const callVectors = (fn) =>
  assertions(moneyTest)
    .map((span) => readCallOccurrences(span.text, `Money\\.${fn}`))
    .filter(Boolean)
    .map((vector) => ({ args: vector.args.map(number), expect: vector.expected }));

summary.push(
  run("money.lineTotal", callVectors("lineTotal"), { "worker cart.ts · lineTotalPaisa": (v) => cart.lineTotalPaisa(...v.args) }, { minimum: 2 }),
);
summary.push(
  run("money.grandTotal", callVectors("grandTotal"), { "worker cart.ts · grandTotalPaisa": (v) => cart.grandTotalPaisa(...v.args) }, { minimum: 3 }),
);
summary.push(
  run("money.percentDiscount", callVectors("percentDiscount"), { "worker cart.ts · percentDiscountPaisa": (v) => cart.percentDiscountPaisa(...v.args) }, { minimum: 6 }),
);

const applicable = assertions(moneyTest)
  .map((span) => pick(span.text, /assertEquals\(([\d_]+)L?,\s*Money\.applicableDiscount\(([^)]*)\)\)/))
  .filter(Boolean)
  .map((found) => {
    const args = splitArguments(found[1]);
    return {
      args: [number(args[0]), stringLiteral(args[1]), number(args[2])],
      expect: number(found[0]),
    };
  });

summary.push(
  run(
    "money.applicableDiscount",
    applicable,
    {
      "worker cart.ts · applicableDiscountPaisa": (v) =>
        cart.applicableDiscountPaisa(v.args[0], { type: v.args[1], value: v.args[2] }),
    },
    { minimum: 6 },
  ),
);

const haversine = haversineVectors(distanceTest);
summary.push({
  ...run("distance.haversineKm", haversine, {}, { minimum: 4 }),
  sites: 2,
});
{
  const sites = {
    "worker cart.ts · haversineKm": (v) => cart.haversineKm(...v.args),
    "browser store-pages.ts · haversine": (v) => browserHaversineKm(...v.args),
  };
  let checked = 0;
  for (const vector of haversine) {
    for (const [name, fn] of Object.entries(sites)) {
      const actual = fn(vector);
      const ok = Math.abs(actual - vector.expect) <= vector.tolerance;
      if (!ok) {
        results.push({ family: "distance.haversineKm", site: name, actual, expected: `${vector.expect} ± ${vector.tolerance}`, ok: false });
      }
      checked += 1;
    }
  }
  summary[summary.length - 1].checked = checked;
}

summary.push(
  run(
    "distance.distanceFromStore · unset pin",
    unpinnedVectors(distanceTest),
    { "worker cart.ts · distanceFromStorePinned": (v) => cart.distanceFromStorePinned(...v.args) },
    { minimum: 4 },
  ),
);

const pinned = pinnedVectors(distanceTest);
if (pinned.length < 1) {
  throw new Error(
    "distance.distanceFromStore · published pin: the app's own distance check could not be read — " +
      "were assertions deleted or renamed?",
  );
}
{
  let checked = 0;
  for (const vector of pinned) {
    const actual = cart.distanceFromStorePinned(...vector.args);
    const ok = actual !== null && Math.abs(actual - vector.expect) <= vector.tolerance;
    if (!ok) {
      results.push({ family: "distance.distanceFromStore · published pin", site: "worker cart.ts", actual, expected: `${vector.expect} ± ${vector.tolerance}`, ok: false });
    }
    checked += 1;
  }
  summary.push({ family: "distance.distanceFromStore · published pin", vectors: pinned.length, sites: 1, checked });
}

summary.push(
  run(
    "distance.isWithinRadius",
    [...radiusVectors(distanceTest, true), ...radiusVectors(distanceTest, false)],
    { "worker cart.ts · isWithinRadius": (v) => cart.isWithinRadius(...v.args) },
    { minimum: 4 },
  ),
);

summary.push(
  run("distance.formatKm", formatKmVectors(distanceTest), { "worker cart.ts · formatKm": (v) => cart.formatKm(...v.args) }, { minimum: 5 }),
);

{
  const vectors = compositionVectors(distanceTest);
  let checked = 0;
  for (const vector of vectors) {
    const km = cart.haversineKm(...vector.args);
    const fee = cart.deliveryFeePaisa(km, vector.perKmPaisa);
    if (fee < vector.low || fee > vector.high || fee % 100 !== 0) {
      results.push({ family: "distance → fee composition", site: "worker cart.ts", actual: `${km.toFixed(2)} km → ${fee}`, expected: `${vector.low}…${vector.high} and a whole number of taka`, ok: false });
    }
    checked += 1;
  }
  summary.push({ family: "distance → fee composition", vectors: vectors.length, sites: 1, checked });
}

// ---- the coupon rule: the app's vectors through both JavaScript copies ------

const COUPON_NOW = Date.now();

{
  const vectors = discountVectors(couponTest);
  summary.push(
    run(
      "coupons.discount",
      vectors,
      {
        "worker coupons.ts · couponDiscountPaisa": (v) =>
          couponModule.couponDiscountPaisa(v.subtotal, v.delivery, v.type, v.value, v.cap),
        "browser cart-client.ts · discountOf": (v) =>
          browser.discountOf({ type: v.type, value: v.value, maxDiscountPaisa: v.cap }, v.subtotal, v.delivery),
      },
      { minimum: 12 },
    ),
  );
}

{
  // Both halves are compared: the money AND the sentence. A refusal that quotes
  // "basket too small" on the phone and "expired" in the browser is a customer
  // arguing with the kitchen about which rule they broke.
  const vectors = couponVectors(couponTest);
  if (vectors.length < 15) {
    throw new Error(
      `coupons.eligibility: read ${vectors.length} vectors from CouponMathTest.kt but 15 are expected — ` +
        `were assertions deleted or renamed?`,
    );
  }
  const sites = {
    "worker coupons.ts · evaluateCoupon": (v) => {
      const verdict = couponModule.evaluateCoupon(
        couponFromSpec(v.spec, COUPON_NOW),
        couponContextFromSpec(v.spec, v.subtotal, v.delivery, COUPON_NOW),
      );
      return { reason: verdict.eligible ? "" : verdict.reason, discount: verdict.discountPaisa };
    },
    "browser cart-client.ts · undalEvaluateCoupon": (v) => {
      const verdict = browser.undalEvaluateCoupon(
        couponFromSpec(v.spec, COUPON_NOW),
        v.subtotal,
        v.delivery,
        couponContextFromSpec(v.spec, v.subtotal, v.delivery, COUPON_NOW),
      );
      return { reason: verdict.eligible ? "" : verdict.reason, discount: verdict.discountPaisa };
    },
  };
  let checked = 0;
  for (const vector of vectors) {
    for (const [site, evaluate] of Object.entries(sites)) {
      const got = evaluate(vector);
      if (got.discount !== vector.expect || got.reason !== vector.reason) {
        results.push({
          family: "coupons.eligibility",
          site,
          actual: `"${got.reason}" · ${got.discount}`,
          expected: `"${vector.reason}" · ${vector.expect}`,
          ok: false,
        });
      }
      checked += 1;
    }
  }
  summary.push({ family: "coupons.eligibility", vectors: vectors.length, sites: Object.keys(sites).length, checked });
}

summary.push(
  run(
    "coupons.window",
    windowVectors(couponTest).map((v) => ({ ...v, expect: `${v.expect}|${v.from}|${v.to}|${v.minutes}` })),
    {
      "worker coupons.ts · withinDailyWindow": (v) =>
        `${couponModule.withinDailyWindow(v.from, v.to, v.minutes)}|${v.from}|${v.to}|${v.minutes}`,
      "browser cart-client.ts · withinWindow": (v) =>
        `${browser.withinWindow(v.from, v.to, v.minutes)}|${v.from}|${v.to}|${v.minutes}`,
    },
    { minimum: 10 },
  ),
);

summary.push(
  run(
    "validators.normalizeBdMobile",
    phoneVectors(validatorsTest),
    {
      "worker cart.ts · normalizeBdMobile": (v) => cart.normalizeBdMobile(v.args[0]),
      "browser cart-client.ts · usablePhone": (v) => browser.usablePhone(v.args[0]),
    },
    { minimum: 5 },
  ),
);

summary.push(
  run(
    "validators.bdMobile",
    validatorVectors(validatorsTest, "Validators\\.bdMobile"),
    {
      "worker cart.ts · normalizeBdMobile": (v) => cart.normalizeBdMobile(v.args[0]) !== null,
      "browser cart-client.ts · usablePhone": (v) => browser.usablePhone(v.args[0]) !== null,
    },
    { minimum: 6 },
  ),
);

summary.push(
  run(
    "validators.couponCode",
    validatorVectors(validatorsTest, "Validators\\.couponCode"),
    { "worker cart.ts · normalizeCoupon": (v) => cart.normalizeCoupon(v.args[0]) !== null },
    { minimum: 5 },
  ),
);

summary.push(
  run(
    "validators.name",
    validatorVectors(validatorsTest, "Validators\\.name"),
    {
      "worker cart.ts · usableName": (v) => cart.usableName(v.args[0]),
      "browser cart-client.ts · usableName": (v) => browser.usableName(v.args[0]),
    },
    { minimum: 5 },
  ),
);

const textChecks = [
  ...validatorVectors(validatorsTest, "Validators\\.addressTitle").map((v) => ({ ...v, label: "address title" })),
  ...validatorVectors(validatorsTest, "Validators\\.addressDescription").map((v) => ({ ...v, label: "address description" })),
  ...validatorVectors(validatorsTest, "Validators\\.deliveryNote").map((v) => ({ ...v, label: "delivery note" })),
];
summary.push(
  run(
    "validators.address text",
    textChecks,
    {
      "worker cart.ts · usableText": (v) => cart.usableText(v.args[0]),
      "browser cart-client.ts · usableText": (v) => browser.usableText(v.args[0]),
    },
    { minimum: 6 },
  ),
);

// ---- nothing may slip through unclassified ---------------------------------

const covered = [];
const sharedExpressions = [
  /Money\.format\(/,
  /Money\.deliveryFeePaisa\(/,
  /Money\.lineTotal\(/,
  /Money\.grandTotal\(/,
  /Money\.percentDiscount\(/,
  /Money\.applicableDiscount\(/,
  /Distance\.haversineKm\(/,
  /Distance\.distanceFromStore\(/,
  /Distance\.isWithinRadius\(/,
  /Distance\.formatKm\(/,
  /Validators\.normalizeBdMobile\(/,
  /Validators\.bdMobile\(/,
  /Validators\.couponCode\(/,
  /Validators\.name\(/,
  /Validators\.addressTitle\(/,
  /Validators\.addressDescription\(/,
  /Validators\.deliveryNote\(/,
  // The coupon tables: the vector helpers are the rows, and the assertions
  // inside them read the coupon functions this gate replays.
  /assertDiscount\(/,
  /assertCoupon\(/,
  /assertWindow\(/,
  /CouponMath\.discountPaisa\(/,
  /CouponMath\.evaluate\(/,
  /CouponMath\.withinDailyWindow\(/,
  /verdict\.(discountPaisa|reason|eligible)\b/,
  // The composed test at the end of DistanceTest asserts on its own local `fee`.
  /fee in [\d_]+L\.\.[\d_]+L/,
  /assertEquals\(0L, fee % 100\)/,
];

const sources = [
  ["MoneyTest.kt", moneyTest],
  ["DistanceTest.kt", distanceTest],
  ["ValidatorsTest.kt", validatorsTest],
  ["CouponMathTest.kt", couponTest],
];
const unclassified = [];
for (const [name, source] of sources) {
  for (const span of assertions(source)) {
    const trimmed = span.text.replace(/\s+/g, " ").trim();
    if (sharedExpressions.some((pattern) => pattern.test(span.text))) {
      covered.push({ name, trimmed });
      continue;
    }
    if (APP_ONLY.some((entry) => entry.pattern.test(span.text))) continue;
    // A composed assertion whose own body reads the shared functions.
    if (/assertKm\(|assertTrue\(|assertFalse\(/.test(span.text) && /Distance\.|Money\./.test(span.text)) {
      if (/Distance\.haversineKm\(/.test(span.text) || /Money\.deliveryFeePaisa\(/.test(span.text)) {
        covered.push({ name, trimmed });
        continue;
      }
    }
    unclassified.push(`${name}: ${trimmed}`);
  }
}

// The round-trip test lists the amounts it runs; each one must also be pinned
// as a printed string above, so a new amount cannot arrive without an expectation.
const roundTrip = /val values = listOf\(([^)]*)\)/.exec(moneyTest);
if (!roundTrip) throw new Error("MoneyTest.kt: the round-trip value list changed shape");
const roundTripValues = splitArguments(roundTrip[1]).map(number);
const pinnedAmounts = new Set(
  assertions(moneyTest)
    .map((span) => readFormatCalls(span.text))
    .filter(Boolean)
    .map((vector) => vector.amount),
);
const unpinnedRoundTrips = roundTripValues.filter((value) => !pinnedAmounts.has(value));

// ---------------------------------------------------------------------------
// 7 · the report
// ---------------------------------------------------------------------------

const pad = (text, width) => String(text).padEnd(width);
console.log("Undal web/app rule parity — the app's own vectors through the shipping web code\n");
console.log(`  ${pad("family", 40)}${pad("vectors", 9)}${pad("checks", 8)}result`);
let checks = 0;
for (const entry of summary) {
  const failed = results.filter((failure) => failure.family === entry.family);
  checks += entry.checked;
  console.log(
    `  ${pad(entry.family, 40)}${pad(entry.vectors, 9)}${pad(entry.checked, 8)}${failed.length === 0 ? "PASS" : `FAIL (${failed.length})`}`,
  );
}
console.log(`\n  ${summary.length} rule families · ${checks} comparisons · ${covered.length} Kotlin assertions shared`);
console.log(`  app-only, declared: ${APP_ONLY.map((entry) => entry.what).join(" · ")}`);

let failed = false;
if (results.length) {
  failed = true;
  console.log("\nDisagreements:");
  for (const failure of results) {
    console.log(`  ✗ ${failure.family} — ${failure.site}`);
    console.log(`      app says ${JSON.stringify(failure.expected)}, the web says ${JSON.stringify(failure.actual)}`);
  }
}
if (unclassified.length) {
  failed = true;
  console.log("\nKotlin assertions this gate neither shares nor knows about:");
  for (const line of unclassified) console.log(`  ✗ ${line}`);
  console.log("  Add a family in section 4, or declare it app-only in section 5 with the reason.");
}
if (unpinnedRoundTrips.length) {
  failed = true;
  console.log("\nAmounts in the round-trip list with no pinned printed form:");
  for (const value of unpinnedRoundTrips) console.log(`  ✗ ${value} paisa — add assertEquals("৳ …", Money.format(${value}))`);
}

console.log(failed ? "\nRESULT: web and app disagree.\n" : "\nRESULT: the web and the app agree on every shared rule.\n");
process.exit(failed ? 1 : 0);
