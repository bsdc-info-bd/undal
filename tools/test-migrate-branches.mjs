import assert from "node:assert/strict";
import test from "node:test";
import {
  BranchMigration,
  activationBlockers,
  missingBranch,
  staffAssignmentProblem,
} from "./migrate-branches.mjs";

const json = (body) => ({ ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) });
const document = (id, branchId) => ({
  name: `projects/demo/databases/(default)/documents/orders/${id}`,
  fields: branchId === undefined ? {} : { branchId: { stringValue: branchId } },
});

test("legacy ownership recognizes absent, null and blank branch ids only", () => {
  assert.equal(missingBranch(undefined), true);
  assert.equal(missingBranch(null), true);
  assert.equal(missingBranch(""), true);
  assert.equal(missingBranch("  "), true);
  assert.equal(missingBranch("default"), false);
  assert.equal(missingBranch(0), false, "a malformed number is reported invalid, not silently reassigned");
});

test("staff claims require an explicit matching RTDB map", () => {
  assert.equal(staffAssignmentProblem({}), null);
  assert.equal(staffAssignmentProblem({ vendor: true, superAdmin: true }), null);
  assert.equal(staffAssignmentProblem({ vendor: true }), "no branchIds claim");
  assert.equal(staffAssignmentProblem({ vendor: true, branchIds: ["default"] }), "no branchAccess map");
  assert.equal(
    staffAssignmentProblem({ vendor: true, branchIds: ["default"], branchAccess: { south: true } }),
    "branchIds and branchAccess disagree",
  );
  assert.equal(
    staffAssignmentProblem({ vendor: true, branchIds: ["default"], branchAccess: { default: true } }),
    null,
  );
  assert.match(staffAssignmentProblem({ vendor: true, branchIds: ["*"], branchAccess: { "*": true } }), /wildcard/);
});

test("activation blockers cover every store and staff prerequisite", () => {
  const report = {
    defaultBranchPresent: false,
    firestore: { orders: { missing: 2, invalid: 1 } },
    rtdb: { chats: { missing: 3, invalid: 0 } },
    unassignedStaff: [{ uid: "staff-1", problem: "no branchIds claim" }],
    unknownBranchAssignments: ["old-area"],
  };
  const blockers = activationBlockers(report);
  assert.equal(blockers.length, 6);
  assert.ok(blockers.some((line) => line.includes("branches/default")));
  assert.ok(blockers.some((line) => line.includes("orders")));
  assert.ok(blockers.some((line) => line.includes("chats")));
  assert.ok(blockers.some((line) => line.includes("staff-1")));
  assert.ok(blockers.some((line) => line.includes("old-area")));
});

test("Firestore backfill follows every page and patches only branchId", async () => {
  const calls = [];
  const commits = [];
  const fetch = async (url, init = {}) => {
    calls.push(url);
    if (init.method === "POST") {
      commits.push(JSON.parse(init.body));
      return json({ writeResults: [] });
    }
    const parsed = new URL(url);
    return json(parsed.searchParams.get("pageToken") === "second"
      ? { documents: [document("two", "south")] }
      : { documents: [document("one")], nextPageToken: "second" });
  };
  const migration = new BranchMigration({ project_id: "demo" }, "token", "https://demo.firebaseio.com", fetch);
  const result = await migration.migrateFirestoreCollection("orders", true);
  assert.equal(result.scanned, 2);
  assert.equal(result.missing, 1);
  assert.equal(result.patched, 1);
  assert.equal(commits.length, 1);
  assert.deepEqual(commits[0].writes[0].updateMask, { fieldPaths: ["branchId"] });
  assert.deepEqual(commits[0].writes[0].update.fields.branchId, { stringValue: "default" });
  assert.deepEqual(commits[0].writes[0].currentDocument, { exists: true });
  assert.ok(calls.some((url) => url.includes("pageToken=second")));
});

test("RTDB backfill uses shallow keys and bounded record pages", async () => {
  const reads = [];
  let patch;
  const fetch = async (url, init = {}) => {
    if (init.method === "PATCH") {
      patch = JSON.parse(init.body);
      return json({});
    }
    reads.push(url);
    if (url.includes("shallow=true")) return json({ legacy: true, current: true });
    if (url.includes("orderLive/legacy")) return json({ status: "placed" });
    if (url.includes("orderLive/current")) return json({ status: "placed", branchId: "north" });
    throw new Error(`unexpected read ${url}`);
  };
  const migration = new BranchMigration({ project_id: "demo" }, "token", "https://demo.firebaseio.com/", fetch);
  const result = await migration.migrateRtdbRoot("orderLive", true);
  assert.equal(result.scanned, 2);
  assert.equal(result.patched, 1);
  assert.deepEqual(patch, { "orderLive/legacy/branchId": "default" });
  assert.equal(reads.some((url) => /orderLive\.json\?access_token=token$/.test(url)), false, "never fetch the whole root");
});

test("the staff audit joins legacy profiles to authoritative Auth claims", async () => {
  const fetch = async () => json({
    users: [
      { localId: "legacy", customAttributes: "{}" },
      { localId: "valid", customAttributes: JSON.stringify({ vendor: true, branchIds: ["default"], branchAccess: { default: true } }) },
      { localId: "disabled", disabled: true, customAttributes: JSON.stringify({ vendor: true }) },
    ],
  });
  const migration = new BranchMigration({ project_id: "demo" }, "token", "https://demo.firebaseio.com", fetch);
  migration.listFirestorePage = async () => ({
    documents: ["legacy", "valid", "disabled", "missing"].map((id) => ({
      name: `projects/demo/databases/(default)/documents/vendor/${id}`,
      fields: {},
    })),
  });
  const result = await migration.listStaffProblems(new Set(["default"]));
  assert.deepEqual(result.unassignedStaff, [
    { uid: "legacy", problem: "vendor profile has no vendor:true Auth claim" },
    { uid: "missing", problem: "vendor profile has no Firebase Auth account" },
  ]);
});

test("activation cannot write when verification has a blocker", async () => {
  const migration = Object.create(BranchMigration.prototype);
  migration.scan = async () => ({
    defaultBranchPresent: false,
    firestore: {}, rtdb: {}, unassignedStaff: [], unknownBranchAssignments: [],
    blockers: ["branches/default is missing"], readyToActivate: false,
  });
  await assert.rejects(() => migration.activate("ACTIVATE_BRANCH_ISOLATION"), /activation blocked/);
});

test("a default backfill is a dry run and performs no writes", async () => {
  const migration = Object.create(BranchMigration.prototype);
  const report = { readyToActivate: false };
  migration.scan = async () => report;
  assert.equal(await migration.backfill(false), report);
});
