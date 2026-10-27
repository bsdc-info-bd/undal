#!/usr/bin/env node
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const catalog = JSON.parse(await readFile(new URL("./undal-menu-catalog.json", import.meta.url), "utf8"));
assert.equal(catalog.source, "https://sylhetbites.pages.dev/undal/");
assert.equal(catalog.currency, "BDT");
assert.equal(catalog.items.length, 67, "the published source menu currently contains 67 dishes");
assert.ok(catalog.categories.length > 0);
const categoryIds = new Set(catalog.categories.map(({ id }) => id));
const dishIds = new Set();
for (const row of catalog.items) {
  assert.equal(row.length, 7, `unexpected field count in ${JSON.stringify(row)}`);
  const [id, name, sale, regular, categoryId, veg, spicy] = row;
  assert.match(id, /^[a-z0-9]+(?:-[a-z0-9]+)*$/);
  assert.ok(name.length >= 3, `${id} needs a customer-facing name`);
  assert.ok(Number.isInteger(sale) && sale > 0, `${id} needs a published selling price`);
  assert.ok(Number.isInteger(regular) && regular >= sale, `${id} has an invalid regular price`);
  assert.ok(categoryIds.has(categoryId), `${id} references unknown category ${categoryId}`);
  assert.equal(typeof veg, "boolean");
  assert.equal(typeof spicy, "boolean");
  assert.ok(!dishIds.has(id), `duplicate document id ${id}`);
  dishIds.add(id);
}
console.log(`Undal menu catalogue validated: ${dishIds.size} source-backed dishes, ${categoryIds.size} sections.`);
