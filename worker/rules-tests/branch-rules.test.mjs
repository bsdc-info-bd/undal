import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { after, before, beforeEach, test } from "node:test";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";

const PROJECT_ID = "demo-undal";
const host = (value, fallback) => {
  const [hostname, port] = (value || fallback).split(":");
  return { host: hostname, port: Number(port) };
};

let env;
before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      ...host(process.env.FIRESTORE_EMULATOR_HOST, "127.0.0.1:8080"),
      rules: readFileSync(new URL("../../firebase/firestore.rules", import.meta.url), "utf8"),
    },
    database: {
      ...host(process.env.FIREBASE_DATABASE_EMULATOR_HOST, "127.0.0.1:9000"),
      rules: readFileSync(new URL("../../firebase/database.rules.json", import.meta.url), "utf8"),
    },
  });
});

after(async () => {
  await env?.cleanup();
});

const northClaims = {
  vendor: true,
  branchIds: ["north"],
  branchAccess: { north: true },
};
const southClaims = {
  vendor: true,
  branchIds: ["south"],
  branchAccess: { south: true },
};
const defaultClaims = {
  vendor: true,
  branchIds: ["default"],
  branchAccess: { default: true },
};

const northDb = () => env.authenticatedContext("north-staff", northClaims).firestore();
const southDb = () => env.authenticatedContext("south-staff", southClaims).firestore();
const legacyDb = () => env.authenticatedContext("legacy-staff", { vendor: true }).firestore();
const defaultDb = () => env.authenticatedContext("default-staff", defaultClaims).firestore();
const customerDb = () => env.authenticatedContext("customer-1").firestore();
const northRtdb = () => env.authenticatedContext("north-staff", northClaims).database();
const southRtdb = () => env.authenticatedContext("south-staff", southClaims).database();
const customerRtdb = () => env.authenticatedContext("customer-1").database();

function order(id, branchId, customerId = "customer-1") {
  return {
    status: "placed",
    customerId,
    branchId,
    createdAt: new Date("2026-11-06T10:00:00.000Z"),
    updatedAt: new Date("2026-11-06T10:00:00.000Z"),
  };
}

beforeEach(async () => {
  await Promise.all([env.clearFirestore(), env.clearDatabase()]);
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.doc("config/store").set({ acceptingOrders: true, branchIsolationEnabled: false });
    await db.doc("orders/north-order").set(order("north-order", "north"));
    await db.doc("orders/south-order").set(order("south-order", "south"));
    await db.doc("orders/default-order").set(order("default-order", "default"));
    await db.doc("orders/legacy-order").set({
      status: "placed",
      customerId: "customer-1",
      createdAt: new Date("2026-11-05T10:00:00.000Z"),
      updatedAt: new Date("2026-11-05T10:00:00.000Z"),
    });
    await db.doc("vendor/north-staff").set({ role: "vendor", name: "North staff", branchIds: ["north"] });
    await db.doc("vendor/south-staff").set({ role: "vendor", name: "South staff", branchIds: ["south"] });
    await db.doc("branches/north").set({ name: "North", active: true, open: true });
    await db.doc("branches/south").set({ name: "South", active: true, open: true });
    await db.doc("coupons/NORTH").set({ branchId: "north", active: true });
    await db.doc("coupons/SOUTH").set({ branchId: "south", active: true });
    await db.doc("presenceDays/north_2026-11-06").set({ branchId: "north", samplesOnline: 1 });
    await db.doc("presenceDays/south_2026-11-06").set({ branchId: "south", samplesOnline: 1 });
    await db.doc("presenceDays/2026-11-06").set({ samplesOnline: 1 });

    const rtdb = context.database();
    const mirror = (branchId) => ({
      customerId: "customer-1",
      branchId,
      status: "placed",
      updatedAt: Date.now(),
    });
    await rtdb.ref("orderLive/north-order").set(mirror("north"));
    await rtdb.ref("orderLive/south-order").set(mirror("south"));
    await rtdb.ref("orderLive/default-order").set(mirror("default"));
    await rtdb.ref("orderLive/legacy-order").set({ customerId: "customer-1", status: "placed", updatedAt: Date.now() });
    await rtdb.ref("chats/north-order").set({ customerId: "customer-1", branchId: "north", createdAt: Date.now() });
    await rtdb.ref("chats/north-order/messages/existing").set({ sender: "customer", text: "Hello", ts: 1 });
    await rtdb.ref("chats/south-order").set({ customerId: "customer-2", branchId: "south", createdAt: Date.now() });
  });
});

test("Firestore order reads and the real branch feed are isolated", async () => {
  const db = northDb();
  await assertSucceeds(db.doc("orders/north-order").get());
  await assertFails(db.doc("orders/south-order").get());
  await assertFails(db.doc("orders/default-order").get());
  const feed = await assertSucceeds(
    db.collection("orders").where("branchId", "==", "north").orderBy("createdAt", "desc").limit(120).get(),
  );
  assert.deepEqual(feed.docs.map((document) => document.id), ["north-order"]);
  await assertFails(db.collection("orders").limit(120).get());
  await assertFails(southDb().collection("orders").where("branchId", "==", "north").limit(120).get());
});

test("the legacy gate closes and default access requires a refreshed assignment", async () => {
  const db = legacyDb();
  await assertSucceeds(db.collection("orders").limit(50).get());
  await env.withSecurityRulesDisabled((context) =>
    context.firestore().doc("config/store").update({ branchIsolationEnabled: true }));

  // Empty claims are a pre-migration compatibility path only. Once isolation
  // is active they must not turn into implicit default (or unscoped) access.
  const unassigned = legacyDb();
  await assertFails(unassigned.doc("orders/north-order").get({ source: "server" }));
  await assertFails(unassigned.doc("orders/default-order").get({ source: "server" }));
  await assertFails(unassigned.collection("orders").limit(50).get({ source: "server" }));
  await assertFails(
    unassigned.collection("orders").where("branchId", "==", "default").limit(50).get({ source: "server" }),
  );

  const assigned = defaultDb();
  await assertFails(assigned.doc("orders/north-order").get({ source: "server" }));
  const defaults = await assertSucceeds(
    assigned.collection("orders").where("branchId", "==", "default").orderBy("createdAt", "desc").limit(120).get({ source: "server" }),
  );
  assert.deepEqual(defaults.docs.map((document) => document.id), ["default-order"]);
  await assertSucceeds(assigned.doc("orders/legacy-order").get({ source: "server" }));
});

test("customers can list only their own orders and cannot create a client-priced order", async () => {
  const db = customerDb();
  const history = await assertSucceeds(
    db.collection("orders").where("customerId", "==", "customer-1").orderBy("createdAt", "desc").limit(20).get(),
  );
  assert.equal(history.size, 4);
  await assertSucceeds(db.doc("orders/north-order").get());
  await assertFails(db.doc("orders/south-order").update({ status: "accepted" }));
  await assertFails(db.doc("orders/invented").set(order("invented", "north")));
});

test("staff assignments are server-managed and vendor profiles are private", async () => {
  const db = northDb();
  await assertSucceeds(db.doc("vendor/north-staff").get());
  await assertFails(db.doc("vendor/south-staff").get());
  await assertFails(db.doc("vendor/north-staff").update({ branchIds: ["south"] }));
  await assertFails(db.collection("vendor").limit(10).get());
});

test("branch configuration writes and coupon feeds cannot cross branches", async () => {
  const db = northDb();
  await assertSucceeds(db.doc("branches/north").update({ open: false, updatedAt: new Date() }));
  await assertFails(db.doc("branches/south").update({ open: false, updatedAt: new Date() }));
  const coupons = await assertSucceeds(db.collection("coupons").where("branchId", "==", "north").limit(80).get());
  assert.deepEqual(coupons.docs.map((document) => document.id), ["NORTH"]);
  await assertFails(db.collection("coupons").limit(80).get());
});

test("presence history uses authorized direct reads, not a shared collection query", async () => {
  await assertSucceeds(northDb().doc("presenceDays/north_2026-11-06").get());
  await assertFails(northDb().doc("presenceDays/south_2026-11-06").get());
  await assertFails(northDb().collection("presenceDays").limit(31).get());
  await env.withSecurityRulesDisabled((context) =>
    context.firestore().doc("config/store").update({ branchIsolationEnabled: true }));
  await assertFails(legacyDb().doc("presenceDays/2026-11-06").get({ source: "server" }));
  await assertSucceeds(defaultDb().doc("presenceDays/2026-11-06").get({ source: "server" }));
});

test("RTDB presence and orderLive root queries enforce branchAccess", async () => {
  const db = northRtdb();
  const beat = { lastSeen: Date.now(), online: true, device: "vendor-app" };
  await assertSucceeds(db.ref("presence/branches/north/vendor").set(beat));
  await assertFails(db.ref("presence/branches/south/vendor").set(beat));
  await assertFails(db.ref("presence/vendor").set(beat));
  await assertSucceeds(env.authenticatedContext("default-staff", defaultClaims).database().ref("presence/vendor").set(beat));

  const live = await assertSucceeds(db.ref("orderLive").orderByChild("branchId").equalTo("north").once("value"));
  assert.deepEqual(Object.keys(live.val()), ["north-order"]);
  await assertFails(db.ref("orderLive").orderByChild("branchId").equalTo("south").once("value"));
  await assertFails(db.ref("orderLive").once("value"));
  await assertFails(db.ref("orderLive/south-order").once("value"));
});

test("RTDB mirrors preserve ownership and customers cannot advance an order", async () => {
  const db = northRtdb();
  await assertSucceeds(db.ref("orderLive/north-order").update({ status: "accepted", updatedAt: Date.now() }));
  await assertFails(db.ref("orderLive/north-order").update({ branchId: "south" }));
  await assertFails(southRtdb().ref("orderLive/north-order").update({ status: "accepted" }));
  await assertSucceeds(customerRtdb().ref("orderLive/north-order").once("value"));
  await assertFails(customerRtdb().ref("orderLive/north-order").update({ status: "ready" }));
});

test("chat sends are immutable, role-bound and branch-bound", async () => {
  const db = northRtdb();
  await assertSucceeds(db.ref("chats/north-order").once("value"));
  await assertSucceeds(db.ref("chats/north-order/messages/vendor-reply").set({ sender: "vendor", text: "Cooking", ts: 2 }));
  await assertFails(southRtdb().ref("chats/north-order/messages/intruder").set({ sender: "vendor", text: "Wrong kitchen", ts: 2 }));

  const customer = customerRtdb();
  await assertSucceeds(customer.ref("chats/north-order/messages/customer-reply").set({ sender: "customer", text: "Thanks", ts: 3 }));
  await assertFails(customer.ref("chats/north-order/messages/forged").set({ sender: "vendor", text: "Forged", ts: 4 }));
  await assertFails(customer.ref("chats/north-order/messages/existing").update({ text: "Changed later" }));
  await assertFails(customer.ref("chats/south-order").once("value"));
});
