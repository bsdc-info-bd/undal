import assert from "node:assert/strict";
import { test } from "node:test";
import {
  FRESH_AFTER_MS,
  RESTORE_WINDOW_MS,
  STALE_AFTER_MS,
  presenceDecision,
} from "../src/crons/presence-policy";
import {
  REMIND_BEFORE_MINUTES,
  REMIND_GRACE_MINUTES,
  serviceInWindow,
} from "../src/crons/opening";
import type { StoreSlot } from "../src/util/store-hours";

const MINUTE = 60_000;
const now = 1_800_000_000_000;

/** A decision with sensible defaults, so each test states only what it means. */
const decide = (overrides: Partial<Parameters<typeof presenceDecision>[0]> = {}) =>
  presenceDecision({
    lastSeenMillis: now - 30_000,
    nowMillis: now,
    storeOpen: true,
    autoClosed: false,
    autoClosedAtMillis: 0,
    insideHours: true,
    ...overrides,
  });

// --------------------------------------------------------------- the ten rule

test("the rule the owner asked for: ten minutes of silence pauses the store", () => {
  assert.equal(STALE_AFTER_MS, 10 * MINUTE, "ten minutes, exactly");
  assert.ok(FRESH_AFTER_MS < STALE_AFTER_MS, "a phone that just wrote is not about to be closed");
  assert.equal(RESTORE_WINDOW_MS, 30 * MINUTE);
});

test("a phone that just checked in keeps the store open", () => {
  const decision = decide();
  assert.equal(decision.action, "none");
  assert.equal(decision.reason, "device-present");
});

test("one missed beat is not an outage, two is", () => {
  assert.equal(decide({ lastSeenMillis: now - 5.5 * MINUTE }).action, "none");
  assert.equal(decide({ lastSeenMillis: now - 9 * MINUTE }).action, "auto-offline");
  assert.equal(decide({ lastSeenMillis: now - 9 * MINUTE }).reason, "device-unreachable");
  assert.equal(decide({ lastSeenMillis: now - 9 * MINUTE }).ageMinutes, 9);
});

test("a closed store is never paused by the watchdog", () => {
  const decision = decide({ lastSeenMillis: now - 60 * MINUTE, storeOpen: false });
  assert.equal(decision.action, "none");
  assert.equal(decision.reason, "store-already-closed", "there are no orders to lose in a closed kitchen");
});

test("a store that has never had a heartbeat is not closed for one", () => {
  const decision = decide({ lastSeenMillis: 0, storeOpen: true });
  assert.equal(decision.action, "none");
  assert.equal(decision.reason, "device-never-seen");
});

test("an already-paused store is left alone while the phone is still gone", () => {
  const decision = decide({ lastSeenMillis: now - 90 * MINUTE, storeOpen: false, autoClosed: true });
  assert.equal(decision.action, "none");
  assert.equal(decision.reason, "auto-closed-device-absent");
});

// ------------------------------------------------------------- coming back

test("a short pause inside the kitchen's hours is undone by itself", () => {
  const decision = decide({
    storeOpen: false,
    autoClosed: true,
    autoClosedAtMillis: now - 7 * MINUTE,
    insideHours: true,
  });
  assert.equal(decision.action, "restore");
  assert.equal(decision.reason, "device-returned-within-window");
  assert.equal(decision.awayMinutes, 7);
});

test("the restore window is exactly half an hour", () => {
  const justInside = decide({
    storeOpen: false,
    autoClosed: true,
    autoClosedAtMillis: now - 29 * MINUTE,
  });
  assert.equal(justInside.action, "restore");
  const justOutside = decide({
    storeOpen: false,
    autoClosed: true,
    autoClosedAtMillis: now - 31 * MINUTE,
  });
  assert.equal(justOutside.action, "remind");
  assert.equal(justOutside.reason, "long-outage");
});

test("a phone that comes back after closing time has its pause lifted, which opens nothing", () => {
  // The schedule is the authority on when: outside the hours the store stays
  // closed whatever the pause says, so lifting the pause cannot reopen a kitchen
  // whose owner has gone home. Keeping it would be worse — tomorrow's service,
  // which the owner left the switch on for, would stay shut because tonight's
  // phone blinked.
  for (const away of [5 * MINUTE, 6 * 60 * MINUTE]) {
    const decision = decide({
      storeOpen: false,
      autoClosed: true,
      autoClosedAtMillis: now - away,
      insideHours: false,
    });
    assert.equal(decision.action, "restore");
    assert.equal(decision.reason, "device-returned-after-hours");
  }
});

test("a long outage in the middle of a service still asks before reopening", () => {
  const decision = decide({
    storeOpen: false,
    autoClosed: true,
    autoClosedAtMillis: now - 3 * 60 * MINUTE,
    insideHours: true,
  });
  assert.equal(decision.action, "remind", "a kitchen whose owner may have gone home must not reopen mid-service by itself");
  assert.equal(decision.reason, "long-outage");
});

test("a switched-on store outside its hours is not paused by a silent phone", () => {
  // The caller passes storeOpen = switch on AND inside the hours. A phone left
  // off overnight with the switch on is a kitchen asleep, not an outage — and
  // pausing it would stop the store opening itself in the morning.
  const decision = decide({ lastSeenMillis: now - 8 * 60 * MINUTE, storeOpen: false, insideHours: false });
  assert.equal(decision.action, "none");
  assert.equal(decision.reason, "store-already-closed");
});

test("a pause with no recorded moment is only ever undone by asking", () => {
  const decision = decide({ storeOpen: false, autoClosed: true, autoClosedAtMillis: 0 });
  assert.equal(decision.action, "remind");
});

// ------------------------------------------------------- the opening reminder

const slot = (open: string, close: string): StoreSlot => ({ open, close });

test("the reminder window is the owner's example: half an hour before the service", () => {
  assert.equal(REMIND_BEFORE_MINUTES, 30);
  assert.equal(REMIND_GRACE_MINUTES, 15);
});

test("a service starting in the window is reminded about, once", () => {
  const slots = [slot("21:00", "23:30")];
  // 20:30 — exactly the owner's example for a nine o'clock opening.
  const candidate = serviceInWindow(slots, 20 * 60 + 30);
  assert.deepEqual(candidate, { service: "21:00", minutesUntil: 30 });
});

test("a service that already started is still worth asking about, briefly", () => {
  const slots = [slot("21:00", "23:30")];
  assert.deepEqual(serviceInWindow(slots, 21 * 60 + 14), { service: "21:00", minutesUntil: -14 });
  assert.equal(serviceInWindow(slots, 21 * 60 + 16), null, "twenty minutes late is a different conversation");
});

test("nothing is asked outside the window", () => {
  const slots = [slot("21:00", "23:30")];
  assert.equal(serviceInWindow(slots, 19 * 60), null, "an hour and a half early is noise");
  assert.equal(serviceInWindow(slots, 22 * 60), null, "the service is already running");
  assert.equal(serviceInWindow([], 20 * 60 + 45), null, "a day with no service has nothing to ask about");
});

test("with two services the soonest one is the one asked about", () => {
  const slots = [slot("13:00", "15:00"), slot("19:00", "22:30")];
  const candidate = serviceInWindow(slots, 18 * 60 + 40);
  assert.deepEqual(candidate, { service: "19:00", minutesUntil: 20 });
});
