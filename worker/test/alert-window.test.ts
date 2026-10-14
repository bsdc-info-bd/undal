/**
 * The re-alert policy, pinned.
 *
 * Why this file exists: the sweep is the thing that guarantees a kitchen phone
 * that slept through the first push still rings. Its failure modes are quiet and
 * expensive in both directions — re-alert forever and the owner turns
 * notifications off for good; never re-alert and orders are missed with no trace.
 * Neither is visible in a typecheck, so both are asserted here.
 *
 * Run with the Node already in the deployment image:
 *
 *     npm test        (from worker/)
 *
 * It is deliberately dependency-free: Node's own test runner, and the module
 * under test has no imports, so nothing has to be bundled or mocked.
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  MISSED_FROM_SECONDS,
  MISSED_UNTIL_SECONDS,
  REALERT_FROM_SECONDS,
  REALERT_UNTIL_SECONDS,
  sweepAction,
  waitedMinutes,
} from "../src/crons/alert-window.ts";

describe("sweepAction", () => {
  it("stays quiet while the first push is still in flight", () => {
    assert.equal(sweepAction(0), "silent");
    assert.equal(sweepAction(1), "silent");
    assert.equal(sweepAction(REALERT_FROM_SECONDS - 1), "silent");
  });

  it("re-alerts for the whole fifteen minutes after the grace period", () => {
    assert.equal(sweepAction(REALERT_FROM_SECONDS), "realert");
    assert.equal(sweepAction(60), "realert");
    assert.equal(sweepAction(5 * 60), "realert");
    assert.equal(sweepAction(14 * 60 + 59), "realert");
    assert.equal(sweepAction(REALERT_UNTIL_SECONDS - 1), "realert");
    assert.equal(sweepAction(REALERT_UNTIL_SECONDS), "silent");
  });

  it("sends exactly one never-answered notice, and only inside its window", () => {
    assert.equal(sweepAction(MISSED_FROM_SECONDS - 1), "silent");
    assert.equal(sweepAction(MISSED_FROM_SECONDS), "missed");
    assert.equal(sweepAction(MISSED_UNTIL_SECONDS - 1), "missed");
    assert.equal(sweepAction(MISSED_UNTIL_SECONDS), "silent");
  });

  it("never rings for an order nobody touched, however old it is", () => {
    // The bug this guards against: a threshold instead of a window, which rings
    // the kitchen every minute for every stale `placed` order, forever.
    for (const age of [2 * 60 * 60, 24 * 60 * 60, 30 * 24 * 60 * 60, 400 * 24 * 60 * 60]) {
      assert.equal(sweepAction(age), "silent", `order aged ${age}s must be silent`);
    }
  });

  it("has windows that do not overlap and are ordered", () => {
    assert.ok(REALERT_FROM_SECONDS > 0);
    assert.ok(REALERT_UNTIL_SECONDS > REALERT_FROM_SECONDS, "the re-alert window must be non-empty");
    assert.ok(
      MISSED_FROM_SECONDS >= REALERT_UNTIL_SECONDS,
      "the notice must not compete with the ringing",
    );
    assert.ok(MISSED_UNTIL_SECONDS > MISSED_FROM_SECONDS, "the notice window must be non-empty");
  });

  it("cannot be starved by a negative age (clock skew between phone and server)", () => {
    assert.equal(sweepAction(-5), "silent");
    assert.equal(sweepAction(Number.NaN), "silent");
  });

  it("phrases the wait in whole minutes a person would say out loud", () => {
    assert.equal(waitedMinutes(MISSED_FROM_SECONDS), 27);
    assert.equal(waitedMinutes(29 * 60), 29);
    // Never "0 minutes".
    assert.equal(waitedMinutes(0), 1);
  });
});
