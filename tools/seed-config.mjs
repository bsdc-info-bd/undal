#!/usr/bin/env node
/**
 * Publishes the bootstrap configuration documents (PART O4).
 *
 * These are REAL starting values, not sample data: they are the kitchen's actual
 * identity, hours and delivery economics, and the owner edits every one of them
 * from inside the app (store settings, schedule builder, delivery settings).
 *
 * Existing documents are never overwritten, so re-running is safe.
 */
import { accessToken, readDocument, serviceAccountFromEnv, writeDocument } from "./lib/gcp.mjs";

const account = serviceAccountFromEnv();
const token = await accessToken(account);

const WEEKDAY_KEYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];
const STANDARD_HOURS = [{ open: "09:00", close: "22:00" }];

const storeDefaults = {
  storeName: "Undal HomeKitchen",
  open: false,
  autoClosed: false,
  manualOverrideNote: "The kitchen opens shortly — please check back in a few minutes.",
  weekly: Object.fromEntries(WEEKDAY_KEYS.map((day) => [day, STANDARD_HOURS])),
  specialDates: [],
  deliveryPerKmAmountPaisa: 2000,
  radiusKm: 5,
  minOrderAmountPaisa: 15000,
  storeLat: 24.8949,
  storeLng: 91.8687,
  storeAddressText: "Zindabazar, Sylhet",
  bannerUrl: "",
  logoUrl: "",
};

const promosDefaults = {
  enabled: false,
  slot: "18:00",
  lastIndex: 0,
  messages: [
    {
      title: "Lunch is cooking",
      body: "Fresh kacchi and bhuna plates leave the Undal kitchen today. Order before 3 pm.",
      imageUrl: "",
    },
    {
      title: "Dinner without the queue",
      body: "Dinner from the Undal kitchen reaches your door hot. Cash on delivery, always.",
      imageUrl: "",
    },
    {
      title: "Family packs are back",
      body: "Feeding a full house? The Undal combo packs give you more plates for less.",
      imageUrl: "",
    },
  ],
};

const documents = [
  { path: "config/store", fields: storeDefaults },
  { path: "config/promos", fields: promosDefaults },
  { path: "counters/orders", fields: { value: 1000 } },
];

for (const document of documents) {
  const existing = await readDocument(account, token, document.path);
  if (existing) {
    console.log(`kept    ${document.path} (already published)`);
    continue;
  }
  await writeDocument(account, token, document.path, document.fields, {
    serverTimestamps: ["createdAt", "updatedAt"],
  });
  console.log(`created ${document.path}`);
}

console.log("");
console.log("Next in the app: log in as the kitchen, move the store pin to your exact location,");
console.log("set the real per-km fee and radius, then open the store from the dashboard.");
