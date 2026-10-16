import assert from "node:assert/strict";
import { test } from "node:test";
import {
  clockMinutes,
  closedSentence,
  closingLabel,
  isOpenAt,
  isTakingOrders,
  nextOpening,
  slotWindow,
  storeStatus,
  switchOn,
  syncedOpenFlag,
  type StoreConfig,
} from "../src/util/store-hours";

/**
 * The store keeps its schedule.
 *
 * The reported failure: a kitchen that published its hours took orders around
 * the clock, because the switch alone decided — once it was on, nothing closed
 * it. The rule now is "switch on ∧ not paused ∧ the hours cover now", and these
 * are its vectors. `app/src/test/…/core/model/StoreHoursTest.kt` holds the
 * Kotlin twin (`StoreHours.kt`) to the same fixture and the same answers, so the
 * phone, the web page and the web checkout cannot disagree about a minute.
 */

/** A minute in Dhaka (UTC+6, no daylight saving). */
const at = (local: string): Date => new Date(Date.parse(`${local.replace(" ", "T")}:00+06:00`));

// 2026-10-19 is a Monday. The fixture week runs Mon 19 … Mon 26 October.
const kitchen: StoreConfig = {
  storeName: "Undal HomeKitchen",
  open: true,
  acceptingOrders: true,
  openSynced: true,
  autoClosed: false,
  weekly: {
    mon: [{ open: "11:00", close: "15:00" }, { open: "19:00", close: "23:00" }],
    tue: [{ open: "11:00", close: "15:00" }, { open: "19:00", close: "23:00" }],
    wed: [{ open: "11:00", close: "15:00" }, { open: "19:00", close: "23:00" }],
    thu: [{ open: "11:00", close: "15:00" }, { open: "19:00", close: "23:00" }],
    // A late kitchen: the Friday service runs past midnight.
    fri: [{ open: "20:00", close: "01:00" }],
    sat: [],
    // The 24/7 template writes 23:59, which must not close the store for a minute.
    sun: [{ open: "00:00", close: "23:59" }],
  },
  specialDates: [
    { date: "2026-10-21", closed: true, note: "Family wedding", slots: [] },
    { date: "2026-10-22", closed: false, slots: [{ open: "09:00", close: "12:00" }] },
    // Marked open with no hours: the builder tells the owner it "behaves like a normal day".
    { date: "2026-10-23", closed: false, slots: [] },
  ],
};

const OPEN_AT: Array<[string, boolean, string]> = [
  ["2026-10-19 10:59", false, "a minute before the lunch service"],
  ["2026-10-19 11:00", true, "the opening minute is open"],
  ["2026-10-19 14:59", true, "the last minute of lunch"],
  ["2026-10-19 15:00", false, "the closing minute is closed"],
  ["2026-10-19 18:30", false, "between the services"],
  ["2026-10-19 22:59", true, "late dinner"],
  ["2026-10-19 23:00", false, "after dinner"],
  ["2026-10-20 00:30", false, "Monday's slots do not cross midnight"],
  ["2026-10-21 12:00", false, "a day off overrides the weekly lunch"],
  ["2026-10-22 10:00", true, "special hours apply"],
  ["2026-10-22 13:00", false, "special hours replace the weekly lunch"],
  ["2026-10-22 20:00", false, "special hours replace the weekly dinner too"],
  ["2026-10-23 21:00", true, "a special date with no hours behaves like a normal Friday"],
  ["2026-10-24 00:30", true, "Friday's late service runs into Saturday, a day off or not"],
  ["2026-10-24 01:00", false, "and ends at its closing minute"],
  ["2026-10-24 12:00", false, "Saturday has no hours"],
  ["2026-10-25 00:00", true, "24/7 from the first minute"],
  ["2026-10-25 23:59", true, "to the last: 23:59 means midnight"],
  ["2026-10-26 00:00", false, "Monday starts at eleven again"],
  ["2026-10-16 00:30", false, "a Friday after midnight belongs to Thursday, which ended at 23:00"],
  ["2026-10-16 20:30", true, "the Friday service"],
  ["2026-10-17 00:30", true, "the Friday service, after midnight"],
];

test("the hours decide the minute, including services that run past midnight", () => {
  for (const [local, expected, why] of OPEN_AT) {
    assert.equal(isOpenAt(kitchen, at(local)), expected, `${local}: ${why}`);
  }
});

test("a switched-on store is taking orders exactly when its hours say so", () => {
  for (const [local, expected, why] of OPEN_AT) {
    assert.equal(isTakingOrders(kitchen, at(local)), expected, `${local}: ${why}`);
  }
  // The reported bug, as one line: the switch is on and it is 3 a.m. on a Tuesday.
  assert.equal(isTakingOrders(kitchen, at("2026-10-20 03:00")), false);
  assert.equal(storeStatus(kitchen, at("2026-10-20 03:00")).headline, "Closed now");
});

test("the next opening is named the way a customer reads it", () => {
  assert.equal(nextOpening(kitchen, at("2026-10-19 16:00"))?.label, "today at 7:00 PM");
  assert.equal(nextOpening(kitchen, at("2026-10-19 23:30"))?.label, "tomorrow at 11:00 AM");
  // Wednesday is a day off, so Tuesday night's next opening is Thursday's special hours.
  assert.equal(nextOpening(kitchen, at("2026-10-20 23:30"))?.label, "on Thursday at 9:00 AM");
  assert.equal(nextOpening(kitchen, at("2026-10-24 02:00"))?.label, "tomorrow at 12:00 AM");
  assert.equal(nextOpening({ ...kitchen, weekly: {}, specialDates: [] }, at("2026-10-19 12:00")), null);
});

test("an open store says when it closes", () => {
  assert.equal(closingLabel(kitchen, at("2026-10-19 12:00")), "3:00 PM");
  assert.equal(closingLabel(kitchen, at("2026-10-23 21:00")), "1:00 AM");
  assert.equal(closingLabel(kitchen, at("2026-10-24 00:30")), "1:00 AM");
  assert.equal(closingLabel(kitchen, at("2026-10-25 12:00")), "midnight");
  assert.equal(closingLabel(kitchen, at("2026-10-19 16:00")), "");
});

test("a time is read the way an owner writes it, and garbage is not midnight", () => {
  assert.equal(clockMinutes("09:00"), 540);
  assert.equal(clockMinutes("9:00"), 540);
  assert.equal(clockMinutes(" 21:30 "), 1290);
  assert.equal(clockMinutes("০৯:৩০"), 570, "Bengali digits, as a Bangla keyboard types them");
  assert.equal(clockMinutes("24:00"), 1440);
  for (const bad of ["25:00", "9", "09:60", "", "nine", "9.30"]) {
    assert.equal(clockMinutes(bad), null, bad);
  }
  assert.equal(clockMinutes(undefined), null);
  // An unreadable slot is no slot — the old reader opened it at 00:00.
  assert.equal(slotWindow({ open: "late", close: "22:00" }), null);
  assert.equal(slotWindow({ open: "09:00", close: "09:00" }), null, "an empty slot");
  assert.deepEqual(slotWindow({ open: "18:00", close: "00:00" }), { open: 1080, close: 1440 });
  assert.deepEqual(slotWindow({ open: "20:00", close: "01:00" }), { open: 1200, close: 60 });
});

test("a day off is a day off in both spellings, and weekday keys may be written out", () => {
  const older: StoreConfig = { ...kitchen, specialDates: [{ date: "2026-10-19", mode: "closed" }] };
  assert.equal(isOpenAt(older, at("2026-10-19 12:00")), false);
  const spelledOut: StoreConfig = { ...kitchen, weekly: { Monday: [{ open: "11:00", close: "15:00" }] }, specialDates: [] };
  assert.equal(isOpenAt(spelledOut, at("2026-10-19 12:00")), true);
});

// ------------------------------------------------------------- the switch

test("the switch is the owner's latest word, whichever build wrote it", () => {
  // Documents no schedule-aware writer has touched: `open` is the switch.
  assert.equal(switchOn({ open: true }), true);
  assert.equal(switchOn({ open: false }), false);
  assert.equal(switchOn({}), false);
  // Written by a schedule-aware build: the switch is recorded on its own.
  assert.equal(switchOn({ acceptingOrders: true, open: false, openSynced: false }), true);
  assert.equal(switchOn({ acceptingOrders: false, open: false, openSynced: false }), false);
  assert.equal(switchOn({ acceptingOrders: true }), true);
  // An older vendor build pressed Close (or Open) after that: `open` no longer
  // matches what the schedule last wrote, so that press is the latest word.
  assert.equal(switchOn({ acceptingOrders: true, open: false, openSynced: true }), false);
  assert.equal(switchOn({ acceptingOrders: false, open: true, openSynced: false }), true);
});

test("paused, auto-closed and outside-hours are three different closed stores", () => {
  const lunch = at("2026-10-19 12:00");
  const paused: StoreConfig = { ...kitchen, acceptingOrders: false, open: false, openSynced: false, manualOverrideNote: "Back on Friday." };
  assert.equal(isTakingOrders(paused, lunch), false);
  assert.equal(storeStatus(paused, lunch).reason, "paused");
  assert.equal(storeStatus(paused, lunch).detail, "Back on Friday.");

  const offline: StoreConfig = { ...kitchen, autoClosed: true, open: false, openSynced: false };
  assert.equal(isTakingOrders(offline, lunch), false);
  assert.equal(storeStatus(offline, lunch).reason, "auto-closed");

  const evening = storeStatus(kitchen, at("2026-10-19 16:00"));
  assert.equal(evening.openNow, false);
  assert.equal(evening.reason, "outside-hours");
  assert.equal(evening.detail, "We open today at 7:00 PM.");

  const wedding = storeStatus(kitchen, at("2026-10-21 12:00"));
  assert.equal(wedding.detail, "Closed today — Family wedding. We open tomorrow at 9:00 AM.");

  const noHours = storeStatus({ ...kitchen, weekly: {}, specialDates: [] }, lunch);
  assert.equal(noHours.reason, "no-hours");

  const open = storeStatus(kitchen, lunch);
  assert.equal(open.openNow, true);
  assert.equal(open.headline, "Open now");
  assert.equal(open.detail, "Taking orders until 3:00 PM.");
});

test("a checkout refusal names the time the kitchen opens", () => {
  assert.equal(closedSentence(kitchen, at("2026-10-19 12:00")), "");
  assert.equal(
    closedSentence(kitchen, at("2026-10-19 16:00")),
    "The kitchen is closed right now — it opens today at 7:00 PM. Your basket is saved until then.",
  );
  assert.match(closedSentence({ ...kitchen, autoClosed: true }, at("2026-10-19 12:00")), /phone is offline/);
  assert.match(closedSentence({ ...kitchen, acceptingOrders: false, open: false, openSynced: false }, at("2026-10-19 12:00")), /not taking orders/);
  assert.notEqual(closedSentence(null), "");
});

// --------------------------------------------- `open`, for the older builds

test("the cron keeps `open` in step with the schedule for builds that read only `open`", () => {
  // Switched on, lunch: open.
  assert.deepEqual(syncedOpenFlag({ ...kitchen, open: false, openSynced: false }, at("2026-10-19 12:00")), {
    open: true,
    openSynced: true,
  });
  // Switched on, three in the morning: closed — the line that stops an old
  // phone selling at 3 a.m.
  assert.deepEqual(syncedOpenFlag(kitchen, at("2026-10-20 03:00")), { open: false, openSynced: false });
  // Already in step: nothing to write.
  assert.deepEqual(syncedOpenFlag(kitchen, at("2026-10-19 12:00")), {});
  // A document from before the switch existed: `open` was the switch, and is
  // recorded as such the first time the cron touches it.
  assert.deepEqual(syncedOpenFlag({ ...kitchen, acceptingOrders: undefined, openSynced: undefined }, at("2026-10-20 03:00")), {
    acceptingOrders: true,
    open: false,
    openSynced: false,
  });
  // An older vendor build pressed Close at lunch: the press is adopted as the
  // switch, and the store stays closed — the cron must never undo it.
  assert.deepEqual(syncedOpenFlag({ ...kitchen, open: false, openSynced: true }, at("2026-10-19 12:00")), {
    acceptingOrders: false,
    openSynced: false,
  });
  // The watchdog's pause wins over the hours.
  assert.deepEqual(syncedOpenFlag({ ...kitchen, autoClosed: true }, at("2026-10-19 12:00")), { open: false, openSynced: false });
});
