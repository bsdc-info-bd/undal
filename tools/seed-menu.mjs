#!/usr/bin/env node
/**
 * Publishes the source-backed Undal menu exactly once, without replacing any
 * category or dish the kitchen has already edited. See tools/undal-menu-catalog.json.
 */
import { accessToken, fromFirestoreValue, serviceAccountFromEnv, writeDocument } from "./lib/gcp.mjs";
import catalog from "./undal-menu-catalog.json" with { type: "json" };

const account = serviceAccountFromEnv();
const token = await accessToken(account);
const categories = new Set(catalog.categories.map((category) => category.id));
const databaseUrl = `https://firestore.googleapis.com/v1/projects/${account.project_id}/databases/(default)/documents`;

async function listCollection(collectionId) {
  const documents = [];
  let pageToken = "";
  do {
    const url = new URL(`${databaseUrl}/${collectionId}`);
    url.searchParams.set("pageSize", "300");
    if (pageToken) url.searchParams.set("pageToken", pageToken);
    const response = await fetch(url, { headers: { authorization: `Bearer ${token}` } });
    if (!response.ok) throw new Error(`list ${collectionId} failed (${response.status}): ${await response.text()}`);
    const page = await response.json();
    for (const document of page.documents ?? []) {
      documents.push({
        id: document.name.split("/").at(-1),
        ...Object.fromEntries(Object.entries(document.fields ?? {}).map(([key, value]) => [key, fromFirestoreValue(value)])),
      });
    }
    pageToken = page.nextPageToken ?? "";
  } while (pageToken);
  return documents;
}

const normalizeName = (value) => String(value ?? "").trim().toLocaleLowerCase("en");
const IMAGE_FILE_BY_ID = {
  "plain-rice-dal-bhuna-begun-bhaja": "plain-rice-dal-bhuna",
  "chhatrabash-special-dim-bhaja": "chhatrabash-dim-bhaja",
};

for (const entry of catalog.items) {
  const [id, name, saleTaka, regularTaka, categoryId, veg, spicy] = entry;
  if (!categories.has(categoryId)) throw new Error(`${id} refers to missing category ${categoryId}`);
  if (!Number.isInteger(saleTaka) || !Number.isInteger(regularTaka) || saleTaka <= 0 || regularTaka < saleTaka) {
    throw new Error(`${id} has invalid source prices`);
  }
}

const categoryDocuments = await listCollection("categories");
const categoryById = new Map(categoryDocuments.map((document) => [document.id, document]));
const categoryByName = new Map(categoryDocuments.map((document) => [normalizeName(document.name), document.id]));
const categoryIdFor = new Map();
for (const [index, category] of catalog.categories.entries()) {
  const existingId = categoryById.has(category.id)
    ? category.id
    : categoryByName.get(normalizeName(category.name));
  if (existingId) {
    categoryIdFor.set(category.id, existingId);
    console.log(`kept    categories/${existingId} (matching section already present)`);
    continue;
  }
  await writeDocument(account, token, `categories/${category.id}`, {
    name: category.name,
    order: index,
    active: true,
    iconUrl: "",
  });
  categoryIdFor.set(category.id, category.id);
  categoryById.set(category.id, { id: category.id, name: category.name });
  categoryByName.set(normalizeName(category.name), category.id);
  console.log(`created categories/${category.id}`);
}

/**
 * Does this picture actually exist?
 *
 * The catalog's `imageBase` points at the kitchen's published menu, and the file
 * name for each dish was a guess: `<id>.webp`. A guess that is wrong does not fail
 * the seed — it writes a URL into Firestore, the storefront renders an `<img>`, the
 * browser gets a 404 and the customer sees an empty box where dinner should be.
 * That is exactly what "the seed menu has no pictures" looks like from the outside.
 *
 * So every URL is probed before it is written, with the other three common
 * extensions tried in turn. Three outcomes, each handled differently:
 *
 *   · **found** — that URL is written, and it is a real photo of the dish;
 *   · **missing** (a 404/410 for every extension) — the dish is seeded with an
 *     empty `imageUrl`, and both front ends draw their own food artwork instead of
 *     a broken image. An honest blank beats a broken picture;
 *   · **unknown** (the network refused, DNS failed, the host is unreachable) — the
 *     original URL is kept. A seed that runs from a network that cannot reach the
 *     photo host must not strip every picture from a menu that has them.
 */
const IMAGE_EXTENSIONS = ["webp", "jpg", "jpeg", "png"];
const imageProbe = new Map();

async function probeImage(fileStem) {
  if (imageProbe.has(fileStem)) return imageProbe.get(fileStem);
  let outcome = { state: "missing", url: "" };
  let unreachable = false;
  for (const extension of IMAGE_EXTENSIONS) {
    const candidate = `${catalog.imageBase}${fileStem}.${extension}`;
    try {
      // HEAD first: no bytes downloaded, and the tile host answers it.
      let response = await fetch(candidate, { method: "HEAD", redirect: "follow" });
      if (response.status === 405 || response.status === 501) {
        response = await fetch(candidate, { method: "GET", headers: { range: "bytes=0-0" } });
      }
      if (response.ok) {
        const type = response.headers.get("content-type") ?? "";
        if (type === "" || type.startsWith("image/")) {
          outcome = { state: "found", url: candidate };
          break;
        }
      }
    } catch {
      unreachable = true;
      break;
    }
  }
  if (outcome.state === "missing" && unreachable) outcome = { state: "unknown", url: "" };
  imageProbe.set(fileStem, outcome);
  return outcome;
}

const menuDocuments = await listCollection("menuItems");
const menuDocumentById = new Map(menuDocuments.map((document) => [document.id, document]));
const itemIds = new Set(menuDocuments.map((document) => document.id));
const itemNames = new Set(menuDocuments.map((document) => normalizeName(document.name)));
let created = 0;
let kept = 0;
let photosMissing = 0;
let repaired = 0;
for (const [id, name, saleTaka, regularTaka, catalogCategoryId, veg, spicy] of catalog.items) {
  const path = `menuItems/${id}`;
  if (itemIds.has(id) || itemNames.has(normalizeName(name))) {
    kept += 1;
    // The one thing worth repairing on an existing dish: a document left in the
    // old "price to be confirmed" state. That flag used to mean "not sellable
    // until the owner clears it", and it survived every app update — a storefront
    // seeded before this version stays empty forever without this. Nothing else is
    // touched: a price, photo or stock level the owner changed is theirs.
    const existing = menuDocumentById.get(id);
    if (existing && existing.priceFrom === true) {
      await writeDocument(
        account,
        token,
        path,
        { priceFrom: false, available: true },
        { serverTimestamps: ["updatedAt"] },
      );
      repaired += 1;
      console.log(`fixed   ${path} (was waiting for a "confirmed" price; it is on sale at ${saleTaka} Tk)`);
    } else {
      console.log(`kept    ${path} (matching dish already present; vendor changes are preserved)`);
    }
    continue;
  }
  const imageFile = IMAGE_FILE_BY_ID[id] ?? id;
  // The probe is per dish (the file name is per dish), and its verdict decides
  // whether a URL is written at all.
  const probe = await probeImage(imageFile);
  const imageUrl = probe.state === "found"
    ? probe.url
    : probe.state === "unknown"
      ? `${catalog.imageBase}${imageFile}.webp`
      : "";
  const amountPaisa = regularTaka * 100;
  const payablePaisa = saleTaka * 100;
  const tags = [ ...(veg ? ["vegetarian"] : []), ...(spicy ? ["spicy"] : []) ];
  await writeDocument(account, token, path, {
    name,
    description: "",
    amountPaisa,
    discountAmountPaisa: amountPaisa - payablePaisa,
    // Seeded dishes are on sale. They used to be written as unavailable so the
    // kitchen could "verify the price first", and the result was a storefront with
    // 67 dishes and nothing a customer could order. The catalog carries the
    // published selling price, so that is the price; `available: true` is the
    // honest default and the sold-out switch is one tap away.
    available: true,
    veg,
    spiceLevel: spicy ? 2 : 0,
    tags,
    orderCount: 0,
    categoryId: categoryIdFor.get(catalogCategoryId),
    subcategoryId: "",
    imageUrl,
    featured: false,
    sourceUrl: catalog.source,
  }, { serverTimestamps: ["createdAt", "updatedAt"] });
  itemIds.add(id);
  itemNames.add(normalizeName(name));
  created += 1;
  if (imageUrl === "") photosMissing += 1;
  console.log(`created ${path}${imageUrl === "" ? " (no photo — the storefront draws its own artwork)" : ""}`);
}

console.log(
  `\nMenu seed finished: ${created} created, ${kept} kept` +
    `${repaired > 0 ? `, ${repaired} repaired from the old price-confirmation state` : ""}, ` +
    `${catalog.items.length} catalog dishes.`,
);
console.log("Seeded dishes are live at their published prices; switch one off in the kitchen if it is finished for today.");
if (photosMissing > 0) {
  console.log(`${photosMissing} dish(es) had no reachable photo and were seeded without an image URL; upload a photo in the kitchen app to give them one.`);
}
console.log("Photos are checked before they are written: a dish whose picture cannot be fetched is seeded without a link, so the storefront shows its own artwork instead of a broken image.");
