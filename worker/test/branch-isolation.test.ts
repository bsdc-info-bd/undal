import assert from "node:assert/strict";
import { test } from "node:test";
import { FakeFirebase } from "./helpers/fake-firebase";
import { runAllPresenceSweeps } from "../src/crons/branch-presence";
import { runAllOpeningReminders } from "../src/crons/opening";
import { vendorUidsForBranch } from "../src/recipients";

const ALL_DAY = [{ open: "00:00", close: "23:59" }];
const EVERY_DAY = { sun: ALL_DAY, mon: ALL_DAY, tue: ALL_DAY, wed: ALL_DAY, thu: ALL_DAY, fri: ALL_DAY, sat: ALL_DAY };
const NOW = new Date("2026-11-06T14:35:00.000Z"); // 20:35 in Dhaka, a Friday
const MINUTE = 60_000;

function branch(id: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    name: id,
    active: true,
    storeLat: 24.8949,
    storeLng: 91.8687,
    radiusKm: 5,
    open: true,
    openSynced: true,
    acceptingOrders: true,
    autoClosed: false,
    weekly: EVERY_DAY,
    specialDates: [],
    ...overrides,
  };
}

function staff(backend: FakeFirebase, uid: string, branchIds: string[]): FakeFirebase {
  backend.setDocument(`vendor/${uid}`, { name: uid, role: "vendor", branchIds });
  for (const branchId of branchIds) {
    const token = `${uid}-${branchId}-registered-device-token`;
    backend.setDocument(`fcmTokens/${uid}/tokens/${token}`, { platform: "android" });
  }
  return backend;
}

test("legacy and default recipients never inherit another branch's staff", async () => {
  const backend = new FakeFirebase();
  staff(backend, "legacy", []);
  staff(backend, "default-staff", ["default"]);
  staff(backend, "north-staff", ["north"]);
  staff(backend, "disabled-north", ["north"]);
  backend.setDocument("vendor/disabled-north", { name: "disabled", role: "vendor", branchIds: ["north"], disabled: true });

  const restore = backend.install();
  try {
    assert.deepEqual((await vendorUidsForBranch(backend.env, "north")).sort(), ["north-staff"]);
    assert.deepEqual((await vendorUidsForBranch(backend.env, "default")).sort(), ["default-staff", "legacy"]);
    assert.deepEqual(await vendorUidsForBranch(backend.env, "south"), []);
  } finally {
    restore();
  }
});

test("a stale phone auto-closes and notifies only its own branch", async () => {
  const backend = new FakeFirebase()
    .setDocument("branches/north", branch("north"))
    .setDocument("branches/south", branch("south"))
    .setRtdb("presence/branches/north/vendor", { lastSeen: NOW.getTime() - 15 * MINUTE, online: true })
    .setRtdb("presence/branches/south/vendor", { lastSeen: NOW.getTime() - MINUTE, online: true });
  staff(backend, "north-staff", ["north"]);
  staff(backend, "south-staff", ["south"]);

  const restore = backend.install();
  let results;
  try {
    results = await runAllPresenceSweeps(backend.env, NOW);
  } finally {
    restore();
  }

  assert.deepEqual(results.map((result) => result.action).sort(), ["auto-offline", "none"]);
  assert.equal(backend.docs.get("branches/north")?.autoClosed, true);
  assert.equal(backend.docs.get("branches/north")?.open, false);
  assert.notEqual(backend.docs.get("branches/south")?.autoClosed, true);
  assert.equal(backend.docs.get("branches/south")?.open, true);
  const alertedTokens = backend.fcmSends.map((send) => send.message?.token);
  assert.deepEqual(alertedTokens, ["north-staff-north-registered-device-token"]);
  assert.equal(backend.docs.get("presenceDays/north_2026-11-06")?.branchId, "north");
  assert.equal(backend.docs.get("presenceDays/south_2026-11-06")?.branchId, "south");
});

test("opening reminders use branch presence, staff and de-duplication keys", async () => {
  const evening = { fri: [{ open: "21:00", close: "22:00" }] };
  const backend = new FakeFirebase()
    .setDocument("branches/north", branch("north", { acceptingOrders: false, open: false, openSynced: false, weekly: evening }))
    .setDocument("branches/south", branch("south", { acceptingOrders: false, open: false, openSynced: false, weekly: evening }))
    .setRtdb("presence/branches/north/vendor", { lastSeen: NOW.getTime() - MINUTE })
    .setRtdb("presence/branches/south/vendor", { lastSeen: NOW.getTime() - 30 * MINUTE });
  staff(backend, "north-staff", ["north"]);
  staff(backend, "south-staff", ["south"]);

  const restore = backend.install();
  try {
    const first = await runAllOpeningReminders(backend.env, NOW);
    assert.deepEqual(first.map((result) => result.reminded), [true, false]);
    assert.deepEqual(backend.fcmSends.map((send) => send.message?.token), ["north-staff-north-registered-device-token"]);
    assert.equal(backend.fcmSends[0]?.message?.data?.branchId, "north");

    const second = await runAllOpeningReminders(backend.env, NOW);
    assert.equal(second[0]?.reason, "already-reminded");
    assert.equal(backend.fcmSends.length, 1, "the same branch and service must not be reminded twice");
    assert.ok([...backend.kv.keys()].some((key) => key.includes(":north:2026-11-06:21:00")));
    assert.ok(![...backend.kv.keys()].some((key) => key.includes(":south:2026-11-06:21:00")));
  } finally {
    restore();
  }
});
