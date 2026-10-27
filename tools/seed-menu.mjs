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
const PRICE_FROM_IDS = new Set([
  "chicken-kabab", "sujir-borra", "deshi-ghorowa-niramish-bhoj",
  "chattogramer-shutki-milon", "chhatrabash-dim-dal", "choto-macher-jhal-bhoj",
  "padmar-jatka-ayojon", "palli-gramer-shidol-oitijhyo", "pabda-dim-rajokiyo-ayojon",
]);

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

const menuDocuments = await listCollection("menuItems");
const itemIds = new Set(menuDocuments.map((document) => document.id));
const itemNames = new Set(menuDocuments.map((document) => normalizeName(document.name)));
let created = 0;
let kept = 0;
for (const [id, name, saleTaka, regularTaka, catalogCategoryId, veg, spicy] of catalog.items) {
  const path = `menuItems/${id}`;
  if (itemIds.has(id) || itemNames.has(normalizeName(name))) {
    kept += 1;
    console.log(`kept    ${path} (matching dish already present; vendor changes are preserved)`);
    continue;
  }
  const imageFile = IMAGE_FILE_BY_ID[id] ?? id;
  const imageUrl = `${catalog.imageBase}${imageFile}.webp`;
  const amountPaisa = regularTaka * 100;
  const payablePaisa = saleTaka * 100;
  const tags = [ ...(veg ? ["vegetarian"] : []), ...(spicy ? ["spicy"] : []) ];
  await writeDocument(account, token, path, {
    name,
    description: "",
    amountPaisa,
    discountAmountPaisa: amountPaisa - payablePaisa,
    available: false,
    veg,
    spiceLevel: spicy ? 2 : 0,
    tags,
    orderCount: 0,
    categoryId: categoryIdFor.get(catalogCategoryId),
    subcategoryId: "",
    imageUrl,
    featured: false,
    priceFrom: PRICE_FROM_IDS.has(id),
    sourceUrl: catalog.source,
  }, { serverTimestamps: ["createdAt", "updatedAt"] });
  itemIds.add(id);
  itemNames.add(normalizeName(name));
  created += 1;
  console.log(`created ${path}`);
}

console.log(`\nMenu seed finished: ${created} created, ${kept} kept, ${catalog.items.length} catalog dishes.`);
console.log("New dishes start unavailable; the kitchen must verify photos, live prices, options and stock before enabling them.");
console.log("Items with a source 'from' price use that published starting price; set the correct options in the vendor editor.");
