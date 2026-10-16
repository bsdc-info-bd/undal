import assert from "node:assert/strict";
import { test } from "node:test";
import {
  buildCouponBoard,
  couponExpiryLabel,
  couponExpiryMillis,
  couponExpiryMoment,
  evaluateCoupon,
  type CouponBoardEntry,
  type CouponContext,
  type CouponDocument,
} from "../src/web/coupons";
import { couponSentence } from "../src/routes/store";
import { handleStoreFront } from "../src/web/store-pages";
import type { Env } from "../src/env";
import type { WebMenu } from "../src/web/store-data";

/**
 * The expiry of a promotion, and the sentence a customer gets when it does not
 * apply.
 *
 * This file exists because one field was read two different ways. The kitchen
 * writes `coupons.expiresAt` as a Firestore timestamp; the Worker meets it as an
 * ISO string over REST, the app meets it as a `Timestamp` object, and a cached
 * board entry carries it already normalised as `expiresAtMillis`. A reader that
 * knows only one of those shapes answers "no expiry" for the others, and "no
 * expiry" is the same answer as "live forever" — so the phone accepted a code the
 * web refused as expired, and the owner was left arguing with their own checkout.
 *
 * Every shape is therefore pinned here, in Dhaka time, against the sentence the
 * customer reads.
 */

/** 2026-10-16T17:59:59Z — 23:59:59 on 16 October in Asia/Dhaka (UTC+6). */
const END_OF_16_OCT_DHAKA = Date.parse("2026-10-16T17:59:59Z");
/** One minute later in Dhaka: 00:00 on 17 October. The date rolls over. */
const START_OF_17_OCT_DHAKA = Date.parse("2026-10-16T18:00:00Z");

function context(over: Partial<CouponContext> = {}): CouponContext {
  return {
    minutes: 12 * 60,
    weekday: "thu",
    now: Date.parse("2026-10-15T06:00:00Z"),
    subtotalPaisa: 50_000,
    deliveryPaisa: 5_000,
    ...over,
  };
}

function coupon(over: Partial<CouponDocument> = {}): CouponDocument {
  return {
    code: "EID20",
    type: "percent",
    value: 20,
    active: true,
    autoApply: true,
    showOnWeb: true,
    minSubtotalAmountPaisa: 0,
    maxDiscountPaisa: 0,
    usageLimit: 0,
    usedCount: 0,
    ...over,
  };
}

const taka = (paisa: number) => `৳ ${(paisa / 100).toLocaleString("en-US")}`;

test("an expiry is named as a Dhaka date, in the shape the app prints", () => {
  // `d MMM yyyy`, which is `TimeDhaka.formatDate` on the phone: one label for one
  // instant, whichever surface refuses the coupon.
  assert.equal(couponExpiryLabel(END_OF_16_OCT_DHAKA), "16 Oct 2026");
  assert.equal(couponExpiryLabel("2026-10-16T17:59:59Z"), "16 Oct 2026");
  assert.equal(couponExpiryLabel({ seconds: END_OF_16_OCT_DHAKA / 1000, nanos: 0 }), "16 Oct 2026");

  // Dhaka is ahead of UTC, so the same instant is the next day there. A label
  // computed in UTC would say 16 Oct for a coupon that runs to midnight on the
  // 17th — one day short of the truth the owner typed.
  assert.equal(couponExpiryLabel(START_OF_17_OCT_DHAKA), "17 Oct 2026");

  // No expiry, and an expiry nobody can read, both answer "" rather than a date
  // invented out of the epoch.
  assert.equal(couponExpiryLabel(undefined), "");
  assert.equal(couponExpiryLabel(0), "");
  assert.equal(couponExpiryLabel("not a date"), "");
});

test("a coupon is judged by its expiry in every shape it can arrive in", () => {
  const shapes: Array<[string, unknown]> = [
    ["epoch millis", END_OF_16_OCT_DHAKA],
    ["an ISO string from Firestore REST", "2026-10-16T17:59:59Z"],
    ["seconds and nanos", { seconds: END_OF_16_OCT_DHAKA / 1000, nanos: 0 }],
  ];
  for (const [name, shape] of shapes) {
    assert.equal(couponExpiryMillis(shape), END_OF_16_OCT_DHAKA, name);
    const verdict = evaluateCoupon(coupon({ expiresAt: shape }), context());
    assert.equal(verdict.eligible, true, `${name}: a coupon expiring tomorrow is live`);
    assert.equal(verdict.discountPaisa, 10_000, name);
  }

  // The shape only a cached board entry carries. Reading `expiresAt` alone
  // answered "no expiry" here, which is how a dated-out promotion kept looking
  // live to anything built from the board.
  const fromBoard = coupon({ expiresAtMillis: END_OF_16_OCT_DHAKA });
  assert.equal(evaluateCoupon(fromBoard, context()).eligible, true);
  const expiredBoard = coupon({ expiresAtMillis: context().now - 60_000 });
  assert.equal(evaluateCoupon(expiredBoard, context()).eligible, false);
  assert.equal(evaluateCoupon(expiredBoard, context()).reason, "expired");
});

test("an expiry written by hand is read the way a person meant it, never as 1970", () => {
  // Epoch seconds: typed into the Firebase console as a number, these used to be
  // read as milliseconds — 21 January 1970 — and every customer heard "expired".
  assert.equal(couponExpiryMillis(END_OF_16_OCT_DHAKA / 1000), END_OF_16_OCT_DHAKA);
  assert.equal(couponExpiryMillis(String(END_OF_16_OCT_DHAKA / 1000)), END_OF_16_OCT_DHAKA);
  assert.equal(couponExpiryMillis(String(END_OF_16_OCT_DHAKA)), END_OF_16_OCT_DHAKA);
  // The Admin SDK's JSON spelling of a Timestamp.
  assert.equal(couponExpiryMillis({ _seconds: END_OF_16_OCT_DHAKA / 1000, _nanoseconds: 0 }), END_OF_16_OCT_DHAKA);
  // A bare date is the whole of that day in Dhaka, as the app's editor stores it.
  assert.equal(couponExpiryMillis("2026-10-16"), END_OF_16_OCT_DHAKA);
  // RFC 3339 with the microseconds Firestore keeps.
  assert.equal(couponExpiryMillis("2026-10-16T17:59:59.000000Z"), END_OF_16_OCT_DHAKA);
  // Nothing readable is "no expiry", not a date.
  for (const nothing of [null, undefined, 0, -5, "", "soon", {}, Number.NaN]) {
    assert.equal(couponExpiryMillis(nothing), 0, String(nothing));
  }

  const live = evaluateCoupon(coupon({ expiresAt: END_OF_16_OCT_DHAKA / 1000 }), context());
  assert.equal(live.eligible, true, "a coupon dated in seconds for tomorrow is live today");
});

test("a refusal prints the minute, so 'it is not expired' can be settled by reading it", () => {
  assert.equal(couponExpiryMoment(END_OF_16_OCT_DHAKA), "16 Oct 2026, 11:59 PM");
  assert.equal(couponExpiryMoment("2026-10-16T00:00:00Z"), "16 Oct 2026, 6:00 AM");
  assert.equal(couponExpiryMoment(START_OF_17_OCT_DHAKA), "17 Oct 2026, 12:00 AM");
  assert.equal(couponExpiryMoment(undefined), "");
});

test("a refusal names the fact, so it is an answer rather than a dead end", () => {
  const expired = coupon({ expiresAt: "2026-10-14T17:59:59Z" });
  // The date and the minute: an owner who believes the code is live can compare
  // this with the date they picked and see what the database actually holds.
  assert.equal(
    couponSentence("EID20", expired as Record<string, unknown>, "expired", taka),
    "EID20 expired on 14 Oct 2026, 11:59 PM.",
  );

  // A coupon with no date to name still gets a sentence — just not an invented one.
  assert.equal(
    couponSentence("EID20", coupon() as Record<string, unknown>, "expired", taka),
    "EID20 has expired.",
  );

  assert.equal(
    couponSentence("EID20", coupon({ usageLimit: 50, usedCount: 50 }) as Record<string, unknown>, "used up", taka),
    "EID20 has been used its full 50 times.",
  );
  assert.equal(
    couponSentence("EID20", coupon({ minSubtotalAmountPaisa: 50_000 }) as Record<string, unknown>, "basket too small", taka),
    "EID20 needs a food subtotal of ৳ 500.",
  );
  assert.equal(
    couponSentence("EID20", coupon({ startsAt: "18:00", endsAt: "22:00" }) as Record<string, unknown>, "outside its hours", taka),
    "EID20 works between 18:00 and 22:00.",
  );
  assert.equal(
    couponSentence("EID20", coupon({ daysOfWeek: ["fri", "sat"] }) as Record<string, unknown>, "not today", taka),
    "EID20 only works on fri, sat.",
  );
});

test("the board publishes only what can be used tonight", () => {
  const now = Date.parse("2026-10-15T06:00:00Z");
  const board = buildCouponBoard(
    [
      coupon({ code: "LIVE", expiresAt: now + 3_600_000 }),
      coupon({ code: "GONE", expiresAt: now - 1_000 }),
      coupon({ code: "OFF", active: false }),
      coupon({ code: "SPENT", usageLimit: 10, usedCount: 10 }),
      coupon({ code: "SECRET", autoApply: false, showOnWeb: false }),
      // Code-only but advertised: a real thing a partner app must support.
      coupon({ code: "FREESHIP", autoApply: false, showOnWeb: true, type: "freedelivery" }),
    ],
    now,
  );
  assert.deepEqual(
    board.map((entry) => entry.code),
    ["LIVE", "FREESHIP"],
  );
  assert.equal(board.find((entry) => entry.code === "FREESHIP")?.autoApply, false);
  assert.equal(board.find((entry) => entry.code === "FREESHIP")?.showOnWeb, true);
});

/** The storefront, rendered from a menu snapshot the KV cache already held. */
function storefrontWith(coupons: CouponBoardEntry[]): Promise<string> {
  const menu: WebMenu = {
    fetchedAt: Date.now(),
    store: null,
    sections: [],
    subsections: [],
    campaigns: [],
    combos: [],
    dishes: [],
    coupons,
  };
  const env = { UNDAL_KV: { get: async () => menu } } as unknown as Env;
  return handleStoreFront(env, new Request("https://undal.test/store")).then((response) => response.text());
}

test("a banner is not printed for an offer the checkout would refuse", async () => {
  const entry: CouponBoardEntry = {
    autoApply: true,
    showOnWeb: true,
    code: "EID20",
    type: "percent",
    value: 20,
    description: "",
    minSubtotalAmountPaisa: 0,
    maxDiscountPaisa: 0,
    expiresAtMillis: Date.now() - 60_000,
    startsAt: "",
    endsAt: "",
    daysOfWeek: [],
    bannerUrl: "",
  };

  // The board is cached for a minute or two, so it can outlive the offers on it.
  // The page re-judges each entry against its own clock: advertising a code the
  // order will refuse is a promise the checkout breaks.
  //
  // The assertion is about the *banner*, not the raw code. The page also embeds
  // the board as JSON for the basket's own arithmetic, and that copy has to stay
  // complete — `undalEvaluateCoupon` in the browser applies the same expiry rule
  // to it, so an expired entry there changes no price.
  const stale = await storefrontWith([entry]);
  assert.doesNotMatch(stale, /coupon-banner/);
  assert.doesNotMatch(stale, /coupon-code/);
  assert.doesNotMatch(stale, /Offers running today/);
  assert.match(stale, /UNDAL_COUPONS/, "the basket still gets the board to price with");

  const live = await storefrontWith([{ ...entry, expiresAtMillis: Date.now() + 3_600_000 }]);
  assert.match(live, /coupon-banner/);
  assert.match(live, /<span class="coupon-code">EID20<\/span>/);
  assert.match(live, /Applied automatically/);

  // And a coupon with no end date is still advertised — "no expiry" is a decision,
  // not a reason to hide the offer.
  const forever = await storefrontWith([{ ...entry, expiresAtMillis: 0 }]);
  assert.match(forever, /coupon-code/);
});
