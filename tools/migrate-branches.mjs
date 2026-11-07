#!/usr/bin/env node
/**
 * Paged, dry-run-first migration from the original single store to `default`.
 *
 *   node tools/migrate-branches.mjs scan
 *   node tools/migrate-branches.mjs backfill
 *   node tools/migrate-branches.mjs backfill --apply --confirm=BACKFILL_DEFAULT
 *   node tools/migrate-branches.mjs activate --confirm=ACTIVATE_BRANCH_ISOLATION
 *
 * Requires FIREBASE_SA_JSON and FIREBASE_RTDB_URL. The first two commands never
 * activate isolation. Activation performs a fresh complete scan, verifies Auth
 * claims and the default branch, then closes the paired Firestore/RTDB legacy
 * checkout gates and reads both back before reporting activation.
 */
import { pathToFileURL } from "node:url";
import {
  accessToken,
  fromFirestoreValue,
  readDocument,
  serviceAccountFromEnv,
  toFirestoreValue,
  writeDocument,
} from "./lib/gcp.mjs";

const FIRESTORE_COLLECTIONS = ["menuItems", "categories", "offers", "coupons", "orders", "chats"];
const RTDB_ROOTS = ["orderLive", "chats"];
const FIRESTORE_PAGE_SIZE = 200;
const RTDB_PAGE_SIZE = 40;

export function missingBranch(value) {
  return value == null || (typeof value === "string" && value.trim().length === 0);
}

export function staffAssignmentProblem(claims) {
  if (claims?.vendor !== true || claims?.superAdmin === true) return null;
  const ids = Array.isArray(claims.branchIds)
    ? [...new Set(claims.branchIds.filter((id) => typeof id === "string" && id.length > 0))]
    : [];
  if (ids.length === 0) return "no branchIds claim";
  if (ids.includes("*")) return "wildcard staff access must be replaced with explicit branches";
  const access = claims.branchAccess;
  if (!access || typeof access !== "object" || Array.isArray(access)) return "no branchAccess map";
  if (ids.some((id) => access[id] !== true)) return "branchIds and branchAccess disagree";
  return null;
}

export function activationBlockers(report) {
  const blockers = [];
  if (!report.defaultBranchPresent) blockers.push("branches/default is missing");
  for (const [name, result] of Object.entries(report.firestore ?? {})) {
    if (result.missing > 0) blockers.push(`Firestore ${name}: ${result.missing} documents need branchId`);
    if (result.invalid > 0) blockers.push(`Firestore ${name}: ${result.invalid} branchId values are invalid`);
  }
  for (const [name, result] of Object.entries(report.rtdb ?? {})) {
    if (result.missing > 0) blockers.push(`RTDB ${name}: ${result.missing} records need branchId`);
    if (result.invalid > 0) blockers.push(`RTDB ${name}: ${result.invalid} records are invalid`);
  }
  for (const staff of report.unassignedStaff ?? []) blockers.push(`staff ${staff.uid}: ${staff.problem}`);
  for (const id of report.unknownBranchAssignments ?? []) blockers.push(`staff assignment names missing branch ${id}`);
  return blockers;
}

function firestoreBase(projectId) {
  return `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents`;
}

function decodeDocument(document) {
  return {
    id: document.name.split("/").pop(),
    name: document.name,
    fields: Object.fromEntries(
      Object.entries(document.fields ?? {}).map(([key, value]) => [key, fromFirestoreValue(value)]),
    ),
  };
}

async function responseJson(response, label) {
  if (!response.ok) throw new Error(`${label} failed (${response.status}): ${await response.text()}`);
  return response.json();
}

export class BranchMigration {
  constructor(account, token, rtdbUrl, fetchImpl = fetch) {
    this.account = account;
    this.token = token;
    this.rtdbUrl = rtdbUrl.replace(/\/+$/, "");
    this.fetch = fetchImpl;
    this.firestore = firestoreBase(account.project_id);
  }

  async listFirestorePage(collection, pageToken) {
    const query = new URLSearchParams({ pageSize: String(FIRESTORE_PAGE_SIZE) });
    if (pageToken) query.set("pageToken", pageToken);
    const response = await this.fetch(`${this.firestore}/${collection}?${query}`, {
      headers: { authorization: `Bearer ${this.token}` },
    });
    return responseJson(response, `list Firestore ${collection}`);
  }

  async commitBranchIds(collection, documents) {
    for (let offset = 0; offset < documents.length; offset += 400) {
      const page = documents.slice(offset, offset + 400);
      const writes = page.map((document) => ({
        currentDocument: { exists: true },
        update: {
          name: document.name,
          fields: { branchId: toFirestoreValue("default") },
        },
        updateMask: { fieldPaths: ["branchId"] },
      }));
      const response = await this.fetch(`${this.firestore}:commit`, {
        method: "POST",
        headers: { authorization: `Bearer ${this.token}`, "content-type": "application/json" },
        body: JSON.stringify({ writes }),
      });
      await responseJson(response, `backfill Firestore ${collection}`);
    }
  }

  async migrateFirestoreCollection(collection, apply) {
    const result = { scanned: 0, missing: 0, invalid: 0, patched: 0, examples: [] };
    let pageToken = "";
    do {
      const page = await this.listFirestorePage(collection, pageToken);
      const documents = (page.documents ?? []).map(decodeDocument);
      const missing = [];
      for (const document of documents) {
        result.scanned += 1;
        const branchId = document.fields.branchId;
        if (missingBranch(branchId)) {
          result.missing += 1;
          if (result.examples.length < 12) result.examples.push(document.id);
          missing.push(document);
        } else if (typeof branchId !== "string") {
          result.invalid += 1;
          if (result.examples.length < 12) result.examples.push(`${document.id} (invalid branchId)`);
        }
      }
      if (apply && missing.length > 0) {
        await this.commitBranchIds(collection, missing);
        result.patched += missing.length;
      }
      pageToken = page.nextPageToken ?? "";
    } while (pageToken);
    return result;
  }

  rtdbUrlFor(path, shallow = false) {
    const query = new URLSearchParams({ access_token: this.token });
    if (shallow) query.set("shallow", "true");
    return `${this.rtdbUrl}/${path}.json?${query}`;
  }

  async writeRtdbIsolationGate() {
    const response = await this.fetch(this.rtdbUrlFor(""), {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ "config/branchIsolationEnabled": true }),
    });
    await responseJson(response, "activate the RTDB compatibility gate");
  }

  async readRtdbIsolationGate() {
    const response = await this.fetch(this.rtdbUrlFor("config/branchIsolationEnabled"));
    return await responseJson(response, "verify the RTDB compatibility gate") === true;
  }

  async patchRtdbBranchIds(root, ids) {
    if (ids.length === 0) return;
    const patch = Object.fromEntries(ids.map((id) => [`${root}/${id}/branchId`, "default"]));
    const response = await this.fetch(this.rtdbUrlFor(""), {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(patch),
    });
    await responseJson(response, `backfill RTDB ${root}`);
  }

  async migrateRtdbRoot(root, apply) {
    const result = { scanned: 0, missing: 0, invalid: 0, patched: 0, examples: [] };
    const shallowResponse = await this.fetch(this.rtdbUrlFor(root, true));
    const shallow = await responseJson(shallowResponse, `list RTDB ${root}`);
    const ids = Object.keys(shallow ?? {}).sort();
    for (let offset = 0; offset < ids.length; offset += RTDB_PAGE_SIZE) {
      const pageIds = ids.slice(offset, offset + RTDB_PAGE_SIZE);
      const entries = await Promise.all(pageIds.map(async (id) => {
        const response = await this.fetch(this.rtdbUrlFor(`${root}/${encodeURIComponent(id)}`));
        return [id, await responseJson(response, `read RTDB ${root}/${id}`)];
      }));
      const missing = [];
      for (const [id, value] of entries) {
        if (!value || typeof value !== "object" || Array.isArray(value)) {
          result.invalid += 1;
          if (result.examples.length < 12) result.examples.push(`${id} (invalid record)`);
          continue;
        }
        result.scanned += 1;
        if (missingBranch(value.branchId)) {
          result.missing += 1;
          if (result.examples.length < 12) result.examples.push(id);
          missing.push(id);
        } else if (typeof value.branchId !== "string") {
          result.invalid += 1;
          if (result.examples.length < 12) result.examples.push(`${id} (invalid branchId)`);
        }
      }
      if (apply) {
        await this.patchRtdbBranchIds(root, missing);
        result.patched += missing.length;
      }
    }
    return result;
  }

  async listBranches() {
    const branches = new Set();
    let pageToken = "";
    do {
      const page = await this.listFirestorePage("branches", pageToken);
      for (const document of page.documents ?? []) branches.add(decodeDocument(document).id);
      pageToken = page.nextPageToken ?? "";
    } while (pageToken);
    return branches;
  }

  async listStaffProblems(branches) {
    const problems = new Map();
    const assignments = new Set();
    const accounts = new Map();
    const profiles = new Set();
    let profileToken = "";
    do {
      const page = await this.listFirestorePage("vendor", profileToken);
      for (const raw of page.documents ?? []) {
        const profile = decodeDocument(raw);
        if (profile.fields.disabled !== true) profiles.add(profile.id);
      }
      profileToken = page.nextPageToken ?? "";
    } while (profileToken);

    let pageToken = "";
    do {
      const query = new URLSearchParams({ maxResults: "500" });
      if (pageToken) query.set("nextPageToken", pageToken);
      const response = await this.fetch(
        `https://identitytoolkit.googleapis.com/v1/projects/${this.account.project_id}/accounts:batchGet?${query}`,
        { headers: { authorization: `Bearer ${this.token}` } },
      );
      const page = await responseJson(response, "list Firebase Auth staff");
      for (const user of page.users ?? []) {
        let claims = {};
        try { claims = JSON.parse(user.customAttributes || "{}"); } catch { claims = {}; }
        accounts.set(user.localId, { disabled: user.disabled === true, claims });
        if (user.disabled === true) continue;
        const problem = staffAssignmentProblem(claims);
        if (problem) problems.set(user.localId, problem);
        if (claims.vendor === true && claims.superAdmin !== true && Array.isArray(claims.branchIds)) {
          for (const id of claims.branchIds) if (typeof id === "string") assignments.add(id);
        }
      }
      pageToken = page.nextPageToken ?? "";
    } while (pageToken);

    // A legacy profile with no usable Auth claim otherwise disappears from a
    // claim-only audit and leaves that kitchen locked out after activation.
    for (const uid of profiles) {
      const account = accounts.get(uid);
      if (!account) {
        problems.set(uid, "vendor profile has no Firebase Auth account");
      } else if (!account.disabled) {
        const problem = account.claims.vendor !== true
          ? "vendor profile has no vendor:true Auth claim"
          : staffAssignmentProblem(account.claims);
        if (problem) problems.set(uid, problem);
      }
    }
    return {
      unassignedStaff: [...problems].map(([uid, problem]) => ({ uid, problem })).slice(0, 100),
      unknownBranchAssignments: [...assignments].filter((id) => id !== "*" && !branches.has(id)).sort(),
    };
  }

  async scan() {
    const branches = await this.listBranches();
    const report = {
      checkedAt: new Date().toISOString(),
      projectId: this.account.project_id,
      defaultBranchPresent: branches.has("default"),
      firestore: {},
      rtdb: {},
      unassignedStaff: [],
      unknownBranchAssignments: [],
    };
    for (const collection of FIRESTORE_COLLECTIONS) {
      report.firestore[collection] = await this.migrateFirestoreCollection(collection, false);
    }
    for (const root of RTDB_ROOTS) report.rtdb[root] = await this.migrateRtdbRoot(root, false);
    Object.assign(report, await this.listStaffProblems(branches));
    report.blockers = activationBlockers(report);
    report.readyToActivate = report.blockers.length === 0;
    return report;
  }

  async ensureDefaultBranch() {
    if (await readDocument(this.account, this.token, "branches/default")) return false;
    const store = await readDocument(this.account, this.token, "config/store");
    if (!store) throw new Error("config/store is missing; create branches/default in the admin before migrating");
    const lat = Number(store.storeLat);
    const lng = Number(store.storeLng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
      throw new Error("config/store has no valid kitchen pin; create branches/default in the admin before migrating");
    }
    const {
      branchIsolationEnabled: _ignored,
      createdAt: _oldCreatedAt,
      updatedAt: _oldUpdatedAt,
      ...legacyFields
    } = store;
    const name = String(store.name || store.storeName || "Undal HomeKitchen").trim();
    await writeDocument(this.account, this.token, "branches/default", {
      ...legacyFields,
      id: "default",
      name,
      storeName: name,
      code: "DEFAULT",
      slug: "default",
      active: store.active !== false,
      open: store.open === true,
      openSynced: store.open === true,
    }, { serverTimestamps: ["createdAt", "updatedAt"] });
    return true;
  }

  async backfill(apply) {
    if (!apply) return this.scan();
    const createdDefault = await this.ensureDefaultBranch();
    const patched = { firestore: {}, rtdb: {} };
    for (const collection of FIRESTORE_COLLECTIONS) {
      patched.firestore[collection] = await this.migrateFirestoreCollection(collection, true);
    }
    for (const root of RTDB_ROOTS) patched.rtdb[root] = await this.migrateRtdbRoot(root, true);
    // Never infer success from writes alone. A complete second pass catches a
    // failed page, a concurrent legacy write, and an invalid record shape.
    const verification = await this.scan();
    return { apply: true, createdDefault, patched, verification };
  }

  async activate(confirmation) {
    if (confirmation !== "ACTIVATE_BRANCH_ISOLATION") {
      throw new Error("activation requires --confirm=ACTIVATE_BRANCH_ISOLATION");
    }
    const report = await this.scan();
    if (!report.readyToActivate) {
      const error = new Error(`activation blocked:\n- ${report.blockers.join("\n- ")}`);
      error.report = report;
      throw error;
    }
    await writeDocument(this.account, this.token, "config/store", {
      branchIsolationEnabled: true,
      branchIsolationMigration: "tools/migrate-branches.mjs",
    }, { serverTimestamps: ["branchIsolationActivatedAt"] });

    // Firestore closes first: if the paired write cannot be made, legacy direct
    // checkout fails closed instead of creating an order whose live mirror is
    // then rejected. The Worker uses the service account in both systems, so
    // current clients continue through the authoritative path. A retry is safe
    // and idempotent; never undo the first gate to make an old APK work again.
    try {
      await this.writeRtdbIsolationGate();
      const [store, rtdbEnabled] = await Promise.all([
        readDocument(this.account, this.token, "config/store"),
        this.readRtdbIsolationGate(),
      ]);
      const firestoreEnabled = store?.branchIsolationEnabled === true;
      if (!firestoreEnabled || !rtdbEnabled) {
        throw new Error(`read-back disagreed (Firestore=${firestoreEnabled}, RTDB=${rtdbEnabled})`);
      }
      return { activated: true, gates: { firestore: true, rtdb: true }, report };
    } catch (cause) {
      const detail = String(cause?.message || cause);
      throw new Error(
        `Firestore isolation was written, but the paired RTDB gate could not be activated and verified: ${detail}. ` +
        "Firestore remains closed to legacy checkout (fail closed). Repair access and rerun activation; " +
        "do not toggle the Firestore flag back to restore an old APK.",
      );
    }
  }
}

async function main() {
  const [command = "scan", ...args] = process.argv.slice(2);
  if (!["scan", "backfill", "activate"].includes(command)) {
    throw new Error("usage: migrate-branches.mjs scan|backfill|activate [--apply] [--confirm=…]");
  }
  const confirmation = args.find((arg) => arg.startsWith("--confirm="))?.slice("--confirm=".length) ?? "";
  const apply = args.includes("--apply");
  if (apply && confirmation !== "BACKFILL_DEFAULT") {
    throw new Error("a writing backfill requires --apply --confirm=BACKFILL_DEFAULT");
  }
  if (command === "scan" && (apply || confirmation)) throw new Error("scan is read-only; do not pass write flags");
  const account = serviceAccountFromEnv();
  const rtdbUrl = process.env.FIREBASE_RTDB_URL;
  if (!rtdbUrl?.startsWith("https://")) throw new Error("FIREBASE_RTDB_URL must be the project's https database URL");
  const token = await accessToken(account);
  const migration = new BranchMigration(account, token, rtdbUrl);
  const result = command === "scan"
    ? await migration.scan()
    : command === "backfill"
      ? await migration.backfill(apply)
      : await migration.activate(confirmation);
  console.log(JSON.stringify(result, null, 2));
  if (command === "backfill" && !apply) console.log("\nDry run only. No document or compatibility flag was changed.");
  if (command === "backfill" && apply && !result.verification.readyToActivate) {
    console.error("\nBackfill finished, but activation remains blocked. Read verification.blockers before retrying.");
    process.exitCode = 2;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    if (error.report) console.error(JSON.stringify(error.report, null, 2));
    console.error(error.message);
    process.exitCode = 1;
  });
}
