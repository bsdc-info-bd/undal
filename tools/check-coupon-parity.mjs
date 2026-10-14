#!/usr/bin/env node
/**
 * Undal — coupon wiring gate.
 *
 * The *rule* is proven by `tools/check-web-rules.mjs`, which replays the vector
 * tables out of `CouponMathTest.kt` (discounts, eligibility, daily windows)
 * through the Worker's `coupons.ts` and the storefront script. This file proves
 * something a vector table cannot: that a coupon the kitchen creates actually
 * reaches a customer.
 *
 * A promotion can be arithmetically perfect and still be invisible — a board that
 * drops rows whose `autoApply` field is missing, a basket script that skips
 * automatic coupons, an order path that ignores the board, a banner nobody
 * renders. Each of those has been a real bug in a real store, so each one is a
 * check here.
 *
 * Run it by hand:  node tools/check-coupon-parity.mjs
 */
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const read = (relative) => readFileSync(join(ROOT, relative), "utf8");

const COUPONS = "worker/src/web/coupons.ts";
const CART_CLIENT = "worker/src/web/cart-client.ts";
const STORE_ROUTES = "worker/src/routes/store.ts";
const STORE_PAGES = "worker/src/web/store-pages.ts";

const coupons = read(COUPONS);
const cartClient = read(CART_CLIENT);
const storeRoutes = read(STORE_ROUTES);
const storePages = read(STORE_PAGES);

const failures = [];
const passes = [];

function check(title, condition, detail) {
  if (condition) passes.push(title);
  else failures.push({ title, detail });
}

/** A function's source, declaration included, brace-balanced. */
function functionSource(source, name) {
  const start = source.search(new RegExp(`(?:export\\s+)?function\\s+${name}\\s*\\(`));
  if (start === -1) return null;
  const open = source.indexOf("{", start);
  if (open === -1) return null;
  let depth = 0;
  let quote = null;
  for (let index = open; index < source.length; index += 1) {
    const ch = source[index];
    if (quote) {
      if (ch === "\\") index += 1;
      else if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === "`") {
      quote = ch;
      continue;
    }
    if (ch === "{") depth += 1;
    else if (ch === "}") {
      depth -= 1;
      if (depth === 0) return source.slice(start, index + 1);
    }
  }
  return null;
}

/** True when a function's parameter list starts with these names, in order. */
function declaresParameters(source, names) {
  if (!source) return false;
  const open = source.indexOf("(");
  const close = source.indexOf(")", open);
  if (open === -1 || close === -1) return false;
  const parameters = source
    .slice(open + 1, close)
    .split(",")
    .map((parameter) => parameter.split(":")[0].split("=")[0].trim())
    .filter(Boolean);
  return names.every((name, index) => parameters[index] === name);
}

// ---------------------------------------------------------------------------
// 1 · the rule exists, in both JavaScript copies
// ---------------------------------------------------------------------------
check(
  "the server owns a couponDiscountPaisa(subtotal, delivery, type, value, cap)",
  declaresParameters(functionSource(coupons, "couponDiscountPaisa"), [
    "subtotalPaisa",
    "deliveryPaisa",
    "type",
    "value",
    "maxDiscountPaisa",
  ]),
  `${COUPONS} must keep the one function both the board and the checkout price with`,
);
check(
  "the server judges a coupon with the basket's own delivery fee",
  declaresParameters(functionSource(coupons, "evaluateCoupon"), ["raw", "context"]),
  `${COUPONS}: a freedelivery coupon cannot be priced without the ride it is paying for`,
);
check(
  "the browser keeps its own discountOf(coupon, subtotal, delivery)",
  declaresParameters(functionSource(cartClient, "discountOf"), ["coupon", "subtotal", "delivery"]),
  `${CART_CLIENT}: the basket page must price the coupon itself before the server does`,
);
check(
  "the browser's verdict function takes an optional clock",
  /window\.undalEvaluateCoupon\s*=\s*function\s*\([^)]*clockArg[^)]*\)/.test(cartClient),
  `${CART_CLIENT}: without a clock argument the storefront could only be proven at one time of day`,
);

// ---------------------------------------------------------------------------
// 2 · the cap is a cap, on both sides
// ---------------------------------------------------------------------------
if (typeof globalThis.process === "object") {
  const strip = (text) => text.replace(/^\s*export\s+/, "");
  const serverDiscount = new Function(
    `${strip(functionSource(coupons, "couponDiscountPaisa")).replace(
      /:\s*(number|string|boolean|void|unknown|any|CouponType(\s*\|\s*null)?)\b/g,
      "",
    )}; return couponDiscountPaisa;`,
  )();
  const browserDiscount = new Function(
    `${functionSource(cartClient, "discountOf").replace(/^function\s+/, "return function ")}; return discountOf;`,
  )();

  const capped = serverDiscount(25_000, 4_500, "freedelivery", 0, 1_000);
  const cappedBrowser = browserDiscount({ type: "freedelivery", value: 0, maxDiscountPaisa: 1_000 }, 25_000, 4_500);
  check(
    "a capped free-delivery coupon is capped on both sides",
    capped === 1_000 && cappedBrowser === 1_000,
    `server said ${capped}, the browser said ${cappedBrowser}: a cap that caps is what "up to ৳100" promises`,
  );

  const far = serverDiscount(25_000, 30_000, "freedelivery", 0, 0);
  check(
    "an uncapped free-delivery coupon is worth exactly the fee",
    far === 30_000,
    `a ৳300 ride on an uncapped coupon should be free, got ${far}`,
  );

  const floor = serverDiscount(1_999, 0, "percent", 7, 0);
  check(
    "a percentage floors rather than rounds",
    floor === 139 && browserDiscount({ type: "percent", value: 7, maxDiscountPaisa: 0 }, 1_999, 0) === 139,
    `7 % of ৳19.99 is ৳1.39, got ${floor}`,
  );
}

// ---------------------------------------------------------------------------
// 3 · the refusal order is the order the customer reads
// ---------------------------------------------------------------------------
{
  const body = functionSource(coupons, "evaluateCoupon") ?? "";
  const order = ["switched off", "not set up correctly", "expired", "not today", "outside its hours", "used up", "basket too small"];
  const positions = order.map((phrase) => body.indexOf(phrase));
  check(
    "the server's refusals keep their order",
    positions.every((position) => position !== -1) &&
      positions.every((position, index) => index === 0 || position > positions[index - 1]),
    "is it alive → is it set up → is it in season → is it today → is it the right hour → used up → basket too small",
  );
}

// ---------------------------------------------------------------------------
// 4 · a coupon the kitchen creates is reachable by a customer
// ---------------------------------------------------------------------------
check(
  "a coupon with no autoApply field is treated as automatic",
  /autoApply\s*!==\s*false/.test(coupons),
  "the default is opt-out: without it, every coupon a kitchen creates would silently do nothing",
);
check(
  "the browser never auto-applies a code-only coupon",
  /autoApply\s*===\s*false\)\s*continue/.test(cartClient),
  `${CART_CLIENT}: a code-only coupon is typed, never applied by itself`,
);
check(
  "the order path chooses from the automatic board",
  /autoApply\s*!==\s*false/.test(storeRoutes) && /pickBestCoupon\(/.test(storeRoutes),
  `${STORE_ROUTES}: createWebOrder must filter the board before it picks the best coupon`,
);
check(
  "the storefront shows the coupons it applies",
  /couponStrip\(menu\.coupons/.test(storePages) && /showOnWeb/.test(storePages),
  `${STORE_PAGES}: a promotion the customer cannot see is a promotion nobody uses`,
);
check(
  "the board endpoint is public and cached",
  /handleStoreCouponsApi/.test(storeRoutes) && /max-age=60/.test(storeRoutes),
  `${STORE_ROUTES}: the board has to be readable before anyone signs in, and cheap enough for a phone`,
);
check(
  "a code-only coupon the kitchen advertised is published",
  /showOnWeb:\s*raw\.showOnWeb\s*!==\s*false/.test(coupons) &&
    /coupon\.autoApply\s*\?\s*"Applied automatically"\s*:\s*"Type this code at checkout"/.test(storePages),
  `${COUPONS}: the storefront renders a "type this code" tag, so toBoardEntry must report the ` +
    `kitchen's showOnWeb switch — a rule of its own here makes that tag unreachable`,
);
check(
  "only a coupon nobody can use is kept off the board",
  /raw\.autoApply\s*===\s*false\s*&&\s*raw\.showOnWeb\s*===\s*false/.test(coupons),
  `${COUPONS}: buildCouponBoard drops a coupon that is neither automatic nor advertised, and ` +
    `nothing else — absent switch fields mean shown, as they do in the console`,
);
check(
  "the app has a Kotlin twin of the rule",
  read("app/src/main/java/com/undal/syl/app/core/util/CouponMath.kt").includes("fun discountPaisa(") &&
    read("app/src/test/java/com/undal/syl/app/core/util/CouponMathTest.kt").includes("assertDiscount("),
  "the Android cart prices coupons with CouponMath, and the JVM test is the vector table check-web-rules.mjs replays",
);

// ---------------------------------------------------------------------------
// result
// ---------------------------------------------------------------------------
console.log(`check-coupon-parity.mjs — ${passes.length} wiring check(s) passed`);
for (const pass of passes) console.log(`  ✓ ${pass}`);

if (failures.length > 0) {
  console.log(`\n${failures.length} coupon wiring problem(s):\n`);
  for (const failure of failures) {
    console.log(`  ✗ ${failure.title}`);
    console.log(`      ${failure.detail}`);
  }
  console.log(
    "\nRESULT: a coupon the kitchen creates may not reach a customer. The rule itself is checked by tools/check-web-rules.mjs.\n",
  );
  process.exit(1);
}

console.log("\nRESULT: the rule is shared and the promotions reach people.\n");
