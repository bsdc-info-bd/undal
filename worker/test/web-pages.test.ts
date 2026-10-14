import assert from "node:assert/strict";
import { test } from "node:test";
import { renderPage } from "../src/web/layout";
import { handleStoreFront, handleOrderTracking } from "../src/web/store-pages";
import type { Env } from "../src/env";
import type { WebMenu } from "../src/web/store-data";

const menu: WebMenu = {
  fetchedAt: Date.now(), store: null, sections: [], subsections: [], campaigns: [], combos: [],
  dishes: [{ id: "rice", kind: "dish", name: "Rice <script>alert(1)</script>",
    description: "Home cooked", amountPaisa: 12000, payablePaisa: 12000,
    discountAmountPaisa: 0, available: true, veg: true, spiceLevel: 0,
    tags: [], orderCount: 1, categoryId: "rice", subcategoryId: "", imageUrl: "",
    prepMinutes: 20, featured: true }],
};
const env = { UNDAL_KV: { get: async () => menu } } as unknown as Env;

test("shared shell escapes metadata, provides accessible navigation and a light responsive theme", async () => {
  const response = renderPage({ title: '<script>"bad"</script>', description: "Food & care", body: "<p>Hello</p>", cacheSeconds: 0 });
  const html = await response.text();
  assert.match(html, /<title>&lt;script&gt;/);
  assert.match(html, /href="#main-content"/);
  assert.match(html, /id="main-content"/);
  assert.match(html, /prefers-reduced-motion/);
  assert.match(html, /grid-template-columns:repeat\(auto-fill,minmax\(min\(100%,250px\),1fr\)\)/);
  assert.match(html, /\.button,a\.button/);
  assert.equal(response.headers.get("cache-control"), "no-store");
});

test("storefront renders cached catalogue safely without external services", async () => {
  const response = await handleStoreFront(env, new Request("https://undal.test/store"));
  const html = await response.text();
  assert.equal(response.status, 200);
  assert.match(html, /A little taste/);
  assert.match(html, /aria-current="page"/);
  assert.match(html, /Rice &lt;script&gt;/);
  assert.doesNotMatch(html, /Rice <script>/);
  assert.match(html, /food-placeholder/);
  assert.match(html, /href="\/item\/rice"/);
});

test("search retains the query and offers an empty-state recovery link", async () => {
  const html = await (await handleStoreFront(env, new Request("https://undal.test/store?q=not-a-dish"))).text();
  assert.match(html, /value="not-a-dish"/);
  assert.match(html, /Nothing matches that search/);
  assert.match(html, /Show the whole menu/);
});

test("tracking uses the actual taka symbol rather than a literal unicode escape", async () => {
  const html = await (await handleOrderTracking("abc123")).text();
  assert.match(html, /replace\(\/\^\/, "৳ "\)/);
});
