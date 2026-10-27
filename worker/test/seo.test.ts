/**
 * The search layer: robots, sitemap, canonicals and structured data.
 *
 * These are pure string builders, so the tests read like a specification of what
 * a crawler is told. Three things matter more than the rest and are asserted
 * explicitly:
 *
 *   · nothing in structured data can break out of its `<script>` tag — a dish
 *     named `</script><script>alert(1)</script>` is data, not markup;
 *   · money is published in taka with two decimals, because schema.org wants
 *     `450.00` where the app stores 45000 paisa;
 *   · the sitemap is built from the live catalogue, so a dish the kitchen
 *     published an hour ago is in it and one it deleted is not.
 */
import assert from "node:assert/strict";
import { test } from "node:test";
import type { Env } from "../src/env";
import {
  DEFAULT_LOCALE,
  OG_LOCALE_ALTERNATE,
  SITE_NAME,
  breadcrumbJsonLd,
  canonicalUrl,
  faqEntries,
  faqJsonLd,
  handleRobotsTxt,
  handleSitemapXml,
  itemJsonLd,
  jsonLdScript,
  menuJsonLd,
  openingHoursSpecification,
  organizationJsonLd,
  robotsTxt,
  siteOrigin,
  sitemapEntries,
  sitemapXml,
  taka,
  websiteJsonLd,
} from "../src/web/seo";
import type { WebMenu } from "../src/web/store-data";
import { FakeFirebase } from "./helpers/fake-firebase";

const ORIGIN = "https://undal.test";

const store = {
  storeName: "Undal HomeKitchen",
  storeAddressText: "Road 4, Zindabazar, Sylhet",
  storeLat: 24.8951,
  storeLng: 91.8682,
  radiusKm: 8,
  deliveryPerKmAmountPaisa: 2500,
  minOrderAmountPaisa: 15000,
  bannerUrl: "https://res.cloudinary.com/undal/banner.jpg",
  logoUrl: "https://res.cloudinary.com/undal/logo.png",
  open: true,
  weekly: {
    friday: [{ open: "12:00", close: "15:00" }, { open: "18:00", close: "22:30" }],
    saturday: [{ open: "18:00", close: "22:30" }],
  },
};

const menu: WebMenu = {
  // Fresh on purpose: `webMenu()` discards a cached snapshot older than a minute
  // and reads Firestore instead, which is the behaviour a stale fixture would
  // silently test in place of the sitemap.
  fetchedAt: Date.now(),
  store,
  sections: [{ id: "rice", name: "Rice plates", order: 1, active: true, iconUrl: "" }],
  subsections: [],
  campaigns: [],
  combos: [{
    id: "family-combo", kind: "combo", name: "Family combo", description: "Feeds four",
    amountPaisa: 120000, payablePaisa: 120000, discountAmountPaisa: 0, regularAmountPaisa: 140000,
    available: true, imageUrl: "", itemRefs: [], featured: false,
  }],
  dishes: [{
    id: "kacchi", kind: "dish", name: "Kacchi biryani", description: "Mutton, aged rice",
    amountPaisa: 32000, payablePaisa: 28000, discountAmountPaisa: 4000, available: true, veg: false,
    spiceLevel: 2, tags: ["signature"], orderCount: 12, categoryId: "rice", subcategoryId: "",
    imageUrl: "https://res.cloudinary.com/undal/kacchi.jpg", prepMinutes: 45, featured: true, priceFrom: false,
  }],
};

// ------------------------------------------------------------------- primitives

test("money is published in taka, and only when there is money to publish", () => {
  assert.equal(taka(45000), 450);
  assert.equal(taka(28050), 280.5);
  assert.equal(taka(0), undefined);
  assert.equal(taka(-500), undefined);
  assert.equal(taka(undefined), undefined);
  assert.equal(taka(Number.NaN), undefined);
});

test("the origin loses its trailing slash so canonicals never double it", () => {
  assert.equal(siteOrigin(new Request("https://undal.test/store")), "https://undal.test");
  assert.equal(canonicalUrl(ORIGIN, "/store"), "https://undal.test/store");
  assert.equal(canonicalUrl(ORIGIN, "store"), "https://undal.test/store");
  assert.equal(canonicalUrl(ORIGIN, "/"), "https://undal.test/");
  assert.equal(canonicalUrl(ORIGIN, "/store/", ""), "https://undal.test/store");
  assert.equal(canonicalUrl(ORIGIN, "/store", "?q=biryani"), "https://undal.test/store?q=biryani");
  assert.equal(canonicalUrl(ORIGIN, "/store", "q=biryani"), "https://undal.test/store?q=biryani");
  assert.equal(canonicalUrl("https://undal.test/", "/store"), "https://undal.test/store");
});

test("structured data cannot escape its script tag", () => {
  const script = jsonLdScript({ name: "</script><script>alert(1)</script>", note: "a & b \u2028 c" });
  assert.ok(script.startsWith('<script type="application/ld+json">'));
  assert.ok(script.endsWith("</script>"));
  assert.equal(script.split("</script>").length, 2, "exactly one closing tag: the real one");
  assert.match(script, /\\u003c\/script\\u003e/);
  assert.match(script, /\\u0026/);
  assert.match(script, /\\u2028/);
  // ...and it is still valid JSON to anything that parses it: \u003c is a JSON
  // escape, so decoding gives the original text back untouched.
  const openTag = '<script type="application/ld+json">';
  const inner = script.slice(openTag.length, -"</script>".length);
  assert.deepEqual(JSON.parse(inner), { name: "</script><script>alert(1)</script>", note: "a & b \u2028 c" });
});

// ------------------------------------------------------------------ robots.txt

test("robots.txt allows the menu and the order pages and refuses the machine rooms", () => {
  const text = robotsTxt(ORIGIN);
  assert.match(text, /^User-agent: \*/m);
  assert.match(text, /^Allow: \//m);
  for (const refused of ["/admin/", "/api/", "/notify/", "/healthz", "/latest.json"]) {
    assert.match(text, new RegExp(`^Disallow: ${refused.replace("/", "\\/")}$`, "m"), `${refused} is off limits`);
  }
  assert.match(text, /^Allow: \/order\//m);
  assert.match(text, /^Sitemap: https:\/\/undal\.test\/sitemap\.xml$/m);
});

test("GET /robots.txt serves it with the right type and a day of cache", async () => {
  const response = handleRobotsTxt(new Request("https://undal.test/robots.txt"));
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/plain/);
  assert.equal(response.headers.get("cache-control"), "public, max-age=86400");
  assert.match(await response.text(), /Sitemap: https:\/\/undal\.test\/sitemap\.xml/);
});

// ----------------------------------------------------------------- sitemap.xml

test("the sitemap lists every page, newest catalogue first", () => {
  const entries = sitemapEntries([{ id: "kacchi" }, { id: "family-combo" }, { id: "" }], "2026-10-01T12:00:00.000Z");
  const paths = entries.map((entry) => entry.path);
  for (const expected of ["/", "/store", "/about", "/download", "/track", "/login", "/account", "/basket", "/item/kacchi", "/item/family-combo"]) {
    assert.ok(paths.includes(expected), `${expected} should be in the sitemap`);
  }
  assert.equal(paths.filter((path) => path === "/item/").length, 0, "an item with no id makes no URL");
  assert.equal(entries[0].priority, 1.0);
  // The entry keeps the full timestamp; `sitemapXml` is what trims it to a date.
  assert.equal(entries.find((entry) => entry.path === "/item/kacchi")?.lastmod, "2026-10-01T12:00:00.000Z");
});

test("the sitemap is valid XML and escapes what needs escaping", () => {
  const xml = sitemapXml(ORIGIN, [
    { path: "/", changefreq: "daily", priority: 1, lastmod: "2026-10-01T12:00:00.000Z" },
    { path: "/item/a&b" },
  ]);
  assert.ok(xml.startsWith('<?xml version="1.0" encoding="UTF-8"?>'));
  assert.match(xml, /<urlset xmlns="http:\/\/www\.sitemaps\.org\/schemas\/sitemap\/0\.9">/);
  assert.equal(xml.split("<url>").length - 1, 2);
  assert.equal(xml.split("</url>").length - 1, 2);
  assert.match(xml, /<loc>https:\/\/undal\.test\/item\/a&amp;b<\/loc>/);
  assert.match(xml, /<lastmod>2026-10-01<\/lastmod>/);
  assert.match(xml, /<changefreq>daily<\/changefreq>/);
  assert.match(xml, /<priority>1\.0<\/priority>/);
});

test("GET /sitemap.xml is built from the cached catalogue", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    backend.kv.set("web:menu:v1", JSON.stringify(menu));
    const response = await handleSitemapXml(backend.env, new Request("https://undal.test/sitemap.xml"));
    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-type") ?? "", /^application\/xml/);
    assert.equal(response.headers.get("cache-control"), "public, max-age=3600");
    const xml = await response.text();
    assert.match(xml, /<loc>https:\/\/undal\.test\/item\/kacchi<\/loc>/);
    assert.match(xml, /<loc>https:\/\/undal\.test\/item\/family-combo<\/loc>/);
    assert.match(xml, /<loc>https:\/\/undal\.test\/about<\/loc>/);
    const today = new Date(menu.fetchedAt).toISOString().slice(0, 10);
    assert.match(xml, new RegExp(`<lastmod>${today}</lastmod>`), "the snapshot's own fetch time, not a fabricated date");
  } finally {
    restore();
  }
});

test("a catalogue the Worker cannot read still produces a sitemap of the fixed pages", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const env = {
      UNDAL_KV: { get: async () => { throw new Error("kv unavailable"); } },
      FIREBASE_SA_JSON: "{ not json",
    } as unknown as Env;
    const response = await handleSitemapXml(env, new Request("https://undal.test/sitemap.xml"));
    assert.equal(response.status, 200);
    const xml = await response.text();
    assert.match(xml, /<loc>https:\/\/undal\.test\/store<\/loc>/);
    assert.doesNotMatch(xml, /<loc>https:\/\/undal\.test\/item\//);
  } finally {
    restore();
  }
});

// ------------------------------------------------------------- structured data

test("the kitchen is published as a Restaurant inside a GeoCircle it actually serves", () => {
  const graph = organizationJsonLd(ORIGIN, store);
  assert.equal(graph["@type"], "Restaurant");
  assert.equal(graph["@id"], "https://undal.test/#restaurant");
  assert.equal(graph.name, "Undal HomeKitchen");
  assert.deepEqual(graph.servesCuisine, ["Bengali", "Home-style", "Bangladeshi"]);
  assert.equal(graph.currenciesAccepted, "BDT");
  assert.equal(graph.priceRange, "৳150+");
  const area = graph.areaServed as Record<string, any>;
  assert.equal(area["@type"], "GeoCircle");
  assert.equal(area.geoRadius, "8000", "schema.org wants metres");
  assert.equal(area.geoMidpoint.latitude, 24.8951);
  const hours = graph.openingHoursSpecification as Array<Record<string, unknown>>;
  assert.equal(hours.length, 3, "a two-service Friday is two entries, not one wrong range");
});

test("opening hours follow the week, and a day with no slots is simply absent", () => {
  const hours = openingHoursSpecification(store.weekly);
  assert.deepEqual(hours.map((entry) => `${entry.dayOfWeek} ${entry.opens}-${entry.closes}`), [
    "Friday 12:00-15:00",
    "Friday 18:00-22:30",
    "Saturday 18:00-22:30",
  ]);
  assert.deepEqual(openingHoursSpecification({}), []);
  assert.deepEqual(openingHoursSpecification(undefined), []);
});

test("the site graph points at the kitchen and offers search", () => {
  const graph = websiteJsonLd(ORIGIN);
  assert.equal(graph["@type"], "WebSite");
  assert.equal(graph.name, SITE_NAME);
  assert.equal(graph.inLanguage, DEFAULT_LOCALE);
  const action = graph.potentialAction as Record<string, any>;
  assert.equal(action["@type"], "SearchAction");
  assert.equal(action.target.urlTemplate, "https://undal.test/store?q={search_term_string}");
});

test("a dish is a Product and a MenuItem with its real price and availability", () => {
  const graph = itemJsonLd(ORIGIN, {
    id: "kacchi", name: "Kacchi biryani", description: "Mutton, aged rice",
    payablePaisa: 28000, amountPaisa: 32000, imageUrl: "https://res.cloudinary.com/undal/kacchi.jpg",
    available: true, kind: "dish",
  }, "Rice plates");
  assert.deepEqual(graph["@type"], ["Product", "MenuItem"]);
  assert.equal(graph.url, "https://undal.test/item/kacchi");
  assert.equal(graph.category, "Rice plates");
  assert.deepEqual(graph.image, ["https://res.cloudinary.com/undal/kacchi.jpg"]);
  const offers = graph.offers as Record<string, string>;
  assert.equal(offers.price, "280.00", "what the customer pays, not the struck-through price");
  assert.equal(offers.priceCurrency, "BDT");
  assert.equal(offers.availability, "https://schema.org/InStock");

  const soldOut = itemJsonLd(ORIGIN, { id: "x", name: "X", payablePaisa: 1000, available: false, kind: "combo" });
  assert.equal((soldOut.offers as Record<string, string>).availability, "https://schema.org/SoldOut");
  assert.equal(soldOut.category, "Combo", "a combo is its own category whatever section it sits in");

  const priceless = itemJsonLd(ORIGIN, { id: "y", name: "Y" });
  assert.equal(priceless.offers, undefined, "no price means no offer, rather than an offer for zero");
});

test("the menu graph groups dishes under the sections they belong to", () => {
  const graph = menuJsonLd(
    ORIGIN,
    [{ id: "rice", name: "Rice plates" }],
    [
      { id: "kacchi", name: "Kacchi biryani", payablePaisa: 28000, categoryId: "rice" },
      { id: "family-combo", name: "Family combo", payablePaisa: 120000 },
    ],
  );
  assert.equal(graph["@type"], "Menu");
  const sections = graph.hasMenuSection as Array<Record<string, any>>;
  const names = sections.map((section) => section.name);
  assert.ok(names.includes("Rice plates"));
  assert.ok(names.includes("Combos"), "an item with no section still gets a home");
  const rice = sections.find((section) => section.name === "Rice plates");
  assert.equal(rice.hasMenuItems[0].offers.price, "280.00");
  assert.equal(rice.hasMenuItems[0].url, "https://undal.test/item/kacchi");
});

test("breadcrumbs are absolute and numbered from one", () => {
  const graph = breadcrumbJsonLd(ORIGIN, [
    { name: "Home", path: "/" },
    { name: "Menu", path: "/store" },
    { name: "Kacchi biryani", path: "/item/kacchi" },
  ]);
  assert.equal(graph["@type"], "BreadcrumbList");
  const items = graph.itemListElement as Array<Record<string, unknown>>;
  assert.deepEqual(items.map((item) => item.position), [1, 2, 3]);
  assert.deepEqual(items.map((item) => item.item), [
    "https://undal.test/",
    "https://undal.test/store",
    "https://undal.test/item/kacchi",
  ]);
});

test("the FAQ a crawler reads is the FAQ a person reads", () => {
  const entries = faqEntries(store);
  assert.ok(entries.length >= 5);
  for (const entry of entries) {
    assert.ok(entry.question.endsWith("?"), `a question should read like one: ${entry.question}`);
    assert.ok(entry.answer.length > 40);
  }
  assert.match(entries[0].answer, /Cash on delivery/, "the payment answer is the rule the code enforces");
  assert.match(
    entries.find((entry) => /delivery charge/.test(entry.question))?.answer ?? "",
    /৳25 for every whole kilometre/,
  );
  assert.match(
    entries.find((entry) => /Where does Undal/.test(entry.question))?.answer ?? "",
    /Inside 8 km/,
  );
  assert.ok(
    entries.some((entry) => /minimum order/i.test(entry.question) && /৳150/.test(entry.answer)),
    "the minimum is published when the kitchen has set one",
  );

  const graph = faqJsonLd(ORIGIN, store);
  assert.equal(graph["@type"], "FAQPage");
  assert.equal(graph["@id"], "https://undal.test/about#faq", "the about page renders this very list");
  const questions = graph.mainEntity as Array<Record<string, any>>;
  assert.deepEqual(questions.map((question) => question.name), entries.map((entry) => entry.question));
  assert.equal(questions[0].acceptedAnswer["@type"], "Answer");

  // Without a store document the answers stay honest rather than quoting zeroes.
  const bare = faqEntries(null);
  assert.doesNotMatch(bare.map((entry) => entry.answer).join(" "), /৳0/);
});
