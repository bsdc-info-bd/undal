/**
 * Search, crawlers and the machine-readable description of the kitchen.
 *
 * A single-kitchen delivery site in Sylhet wins its customers on two things:
 * somebody searching "home cooked food sylhet" and somebody sending a dish link
 * into a WhatsApp group. The first needs honest metadata — one canonical URL per
 * page, a sitemap a crawler can follow, and structured data that lets Google show
 * the menu, the price and the opening hours in the result itself. The second needs
 * the Open Graph card, which `layout.ts` already builds from the real Cloudinary
 * photo.
 *
 * Everything here is a pure string builder — the routes read live data and hand
 * it over, and the tests assert the output. The two exceptions are the crawler
 * endpoints at the bottom, which are the routes themselves: `robots.txt` and
 * `sitemap.xml` are one deployment each and belong with the builders they use.
 */
import type { Env } from "../env";
import { escapeHtml } from "../util/http";
import { webMenu } from "./store-data";

export const SITE_NAME = "Undal HomeKitchen";
export const DEFAULT_LOCALE = "en_GB";
/** The language a share card is read in by WhatsApp and Facebook in Bangladesh. */
export const OG_LOCALE_ALTERNATE = "bn_BD";

/** Escapes a value for a `<script type="application/ld+json">` body. */
export function jsonLdScript(value: unknown): string {
  const encoded = JSON.stringify(value)
    .replace(/</g, "\\u003c")
    .replace(/>/g, "\\u003e")
    .replace(/&/g, "\\u0026")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029");
  return `<script type="application/ld+json">${encoded}</script>`;
}

/** The origin without a trailing slash, from a request or a configured host. */
export function siteOrigin(request: Request): string {
  const url = new URL(request.url);
  return url.origin.replace(/\/+$/, "");
}

export function canonicalUrl(origin: string, path: string, query?: string): string {
  const cleanPath = path.startsWith("/") ? path : `/${path}`;
  const cleanQuery = query && query.length > 0 ? `?${query.replace(/^\?/, "")}` : "";
  return `${origin.replace(/\/+$/, "")}${cleanPath === "/" ? "/" : cleanPath.replace(/\/+$/, "")}${cleanQuery}`;
}

// ------------------------------------------------------------------ robots.txt

export function robotsTxt(origin: string): string {
  const base = origin.replace(/\/+$/, "");
  return [
    "# Undal HomeKitchen — everything a crawler needs to index the menu,",
    "# and the two places it must never spend a request on.",
    "User-agent: *",
    "Allow: /",
    "Disallow: /admin/",
    "Disallow: /api/",
    "Disallow: /notify/",
    "Disallow: /healthz",
    "Disallow: /latest.json",
    "",
    "# Order status pages are indexable: the link is the key, and a customer",
    "# searching their own order number should land on the live status.",
    "Allow: /order/",
    "",
    `Sitemap: ${base}/sitemap.xml`,
    "",
  ].join("\n");
}

// ------------------------------------------------------------------ sitemap.xml

export interface SitemapEntry {
  path: string;
  changefreq?: "always" | "hourly" | "daily" | "weekly" | "monthly" | "yearly";
  priority?: number;
  /** ISO date. A crawler skips what has not changed, so this is worth being right. */
  lastmod?: string;
}

export function sitemapXml(origin: string, entries: SitemapEntry[]): string {
  const base = origin.replace(/\/+$/, "");
  const urls = entries
    .map((entry) => {
      const loc = `${base}${entry.path.startsWith("/") ? entry.path : `/${entry.path}`}`;
      const parts = [`    <loc>${escapeHtml(loc)}</loc>`];
      if (entry.lastmod) parts.push(`    <lastmod>${escapeHtml(entry.lastmod.slice(0, 10))}</lastmod>`);
      if (entry.changefreq) parts.push(`    <changefreq>${entry.changefreq}</changefreq>`);
      if (typeof entry.priority === "number") parts.push(`    <priority>${entry.priority.toFixed(1)}</priority>`);
      return `  <url>\n${parts.join("\n")}\n  </url>`;
    })
    .join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`;
}

// ------------------------------------------------------------- crawler routes

/**
 * `GET /robots.txt` — served, not hand-uploaded, so it always names this
 * deployment's own sitemap URL. Cached for a day: crawlers re-read it rarely and
 * a Worker invocation for a static file is pure waste.
 */
export function handleRobotsTxt(request: Request): Response {
  return new Response(robotsTxt(siteOrigin(request)), {
    headers: {
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "public, max-age=86400",
    },
  });
}

/**
 * `GET /sitemap.xml` — the static pages plus every published dish and combo.
 *
 * Built from the same cached catalogue snapshot the storefront renders from, so
 * a new dish appears here within a minute of the kitchen publishing it and a
 * sold-out one leaves the same way. `lastmod` is the snapshot's own fetch time
 * rather than a fabricated date: it is the last moment we know the menu changed.
 */
export async function handleSitemapXml(
  env: Env,
  request: Request,
): Promise<Response> {
  const origin = siteOrigin(request);
  let items: Array<{ id: string }> = [];
  let lastmod: string | undefined;
  try {
    const menu = await webMenu(env);
    items = [...menu.dishes, ...menu.combos].map((item) => ({ id: item.id }));
    if (menu.fetchedAt > 0) lastmod = new Date(menu.fetchedAt).toISOString();
  } catch (error) {
    // The static pages are still worth publishing: a sitemap that names the
    // storefront beats no sitemap at all when the catalogue read fails.
    console.error("undal sitemap catalogue read failed", error);
  }
  return new Response(sitemapXml(origin, sitemapEntries(items, lastmod)), {
    headers: {
      "content-type": "application/xml; charset=utf-8",
      "cache-control": "public, max-age=3600",
    },
  });
}

// ---------------------------------------------------------------- structured data

export interface SeoStore {
  storeName?: string;
  storeAddressText?: string;
  storeLat?: number;
  storeLng?: number;
  radiusKm?: number;
  deliveryPerKmAmountPaisa?: number;
  minOrderAmountPaisa?: number;
  bannerUrl?: string;
  logoUrl?: string;
  weekly?: Record<string, Array<{ open: string; close: string }>>;
  open?: boolean;
}

/** BDT, as a plain number — schema.org wants `450` where the app stores 45000 paisa. */
export function taka(paisa: number | undefined): number | undefined {
  if (typeof paisa !== "number" || !Number.isFinite(paisa) || paisa <= 0) return undefined;
  return Math.round(paisa) / 100;
}

const DAY_ORDER = ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"];

/**
 * Opening hours in schema.org's own grammar.
 *
 * A day with no slots is `Closed`. A day with two slots (a lunch service and an
 * evening one) produces two entries, which is what a rich result needs to show
 * "closed 3–5pm" rather than a wrong single range.
 */
export function openingHoursSpecification(
  weekly: Record<string, Array<{ open: string; close: string }>> | undefined,
): Array<Record<string, unknown>> {
  const specifications: Array<Record<string, unknown>> = [];
  for (const day of DAY_ORDER) {
    const slots = weekly?.[day] ?? [];
    if (slots.length === 0) continue;
    for (const slot of slots) {
      specifications.push({
        "@type": "OpeningHoursSpecification",
        dayOfWeek: day.charAt(0).toUpperCase() + day.slice(1),
        opens: slot.open,
        closes: slot.close,
      });
    }
  }
  return specifications;
}

export function organizationJsonLd(origin: string, store: SeoStore | null): Record<string, unknown> {
  const graph: Record<string, unknown> = {
    "@context": "https://schema.org",
    "@type": "Restaurant",
    "@id": `${origin}/#restaurant`,
    name: store?.storeName || SITE_NAME,
    description:
      "One home kitchen in Sylhet, Bangladesh, cooking a short menu of Bengali home food and delivering it inside a fixed radius for cash on delivery.",
    url: `${origin}/`,
    servesCuisine: ["Bengali", "Home-style", "Bangladeshi"],
    priceRange: taka(store?.minOrderAmountPaisa) ? `৳${taka(store?.minOrderAmountPaisa)}+` : "৳",
    paymentAccepted: "Cash",
    currenciesAccepted: "BDT",
    publicAccess: false,
    hasMenu: { "@id": `${origin}/store#menu` },
  };
  if (store?.logoUrl) graph.image = [store.logoUrl];
  if (store?.bannerUrl) graph.image = [store.bannerUrl, ...(graph.image as string[] ?? [])];
  if (store?.storeLat && store?.storeLng) {
    graph.geo = {
      "@type": "GeoCoordinates",
      latitude: store.storeLat,
      longitude: store.storeLng,
    };
    graph.address = {
      "@type": "PostalAddress",
      addressLocality: "Sylhet",
      addressRegion: "Sylhet Division",
      addressCountry: "BD",
      streetAddress: store.storeAddressText || "Sylhet",
    };
  }
  const hours = openingHoursSpecification(store?.weekly);
  if (hours.length > 0) graph.openingHoursSpecification = hours;
  const areaServed = store?.radiusKm && store.radiusKm > 0 ? store.radiusKm : undefined;
  if (areaServed) {
    graph.areaServed = {
      "@type": "GeoCircle",
      geoMidpoint: { "@type": "GeoCoordinates", latitude: store?.storeLat ?? 0, longitude: store?.storeLng ?? 0 },
      geoRadius: String(areaServed * 1000),
    };
  }
  return graph;
}

export function websiteJsonLd(origin: string): Record<string, unknown> {
  return {
    "@context": "https://schema.org",
    "@type": "WebSite",
    "@id": `${origin}/#website`,
    url: `${origin}/`,
    name: SITE_NAME,
    inLanguage: DEFAULT_LOCALE,
    publisher: { "@id": `${origin}/#restaurant` },
    potentialAction: {
      "@type": "SearchAction",
      target: { "@type": "EntryPoint", urlTemplate: `${origin}/store?q={search_term_string}` },
      "query-input": "required name=search_term_string",
    },
  };
}

export interface SeoItem {
  id: string;
  name: string;
  description?: string;
  payablePaisa?: number;
  amountPaisa?: number;
  imageUrl?: string;
  available?: boolean;
  kind?: string;
}

/** One dish, as a Product with an Offer — the shape that produces a price in search. */
export function itemJsonLd(origin: string, item: SeoItem, sectionName?: string): Record<string, unknown> {
  const price = taka(item.payablePaisa);
  const graph: Record<string, unknown> = {
    "@context": "https://schema.org",
    "@type": ["Product", "MenuItem"],
    "@id": `${origin}/item/${item.id}#item`,
    name: item.name,
    url: `${origin}/item/${item.id}`,
    description: item.description || `${item.name} from ${SITE_NAME}.`,
  };
  if (item.imageUrl) graph.image = [item.imageUrl];
  if (sectionName) graph.category = sectionName;
  if (item.kind === "combo") graph.category = "Combo";
  if (price !== undefined) {
    graph.offers = {
      "@type": "Offer",
      url: `${origin}/item/${item.id}`,
      price: price.toFixed(2),
      priceCurrency: "BDT",
      availability: item.available === false
        ? "https://schema.org/SoldOut"
        : "https://schema.org/InStock",
    };
  }
  return graph;
}

/** The whole menu as a `Menu` with sections — the shape Google's menu rich result reads. */
export function menuJsonLd(
  origin: string,
  sections: Array<{ id: string; name: string }>,
  items: Array<SeoItem & { categoryId?: string }>,
): Record<string, unknown> {
  const sectionName = new Map(sections.map((section) => [section.id, section.name]));
  const bySection = new Map<string, SeoItem[]>();
  for (const item of items) {
    const key = item.categoryId ?? "";
    const list = bySection.get(key) ?? [];
    list.push(item);
    bySection.set(key, list);
  }
  const menuSections = [...bySection.entries()].map(([key, list]) => ({
    "@type": "MenuSection",
    name: sectionName.get(key) ?? (key === "" ? "Combos" : "Menu"),
    hasMenuItems: list.map((item) => {
      const price = taka(item.payablePaisa);
      return {
        "@type": "MenuItem",
        name: item.name,
        url: `${origin}/item/${item.id}`,
        description: item.description ?? "",
        ...(item.imageUrl ? { image: item.imageUrl } : {}),
        ...(price !== undefined
          ? { offers: { "@type": "Offer", price: price.toFixed(2), priceCurrency: "BDT" } }
          : {}),
      };
    }),
  }));
  return {
    "@context": "https://schema.org",
    "@type": "Menu",
    "@id": `${origin}/store#menu`,
    name: "Undal menu",
    url: `${origin}/store`,
    inLanguage: DEFAULT_LOCALE,
    hasMenuSection: menuSections,
  };
}

export function breadcrumbJsonLd(origin: string, crumbs: Array<{ name: string; path: string }>): Record<string, unknown> {
  return {
    "@context": "https://schema.org",
    "@type": "BreadcrumbList",
    itemListElement: crumbs.map((crumb, index) => ({
      "@type": "ListItem",
      position: index + 1,
      name: crumb.name,
      item: `${origin}${crumb.path}`,
    })),
  };
}

/**
 * The questions a first-time customer actually asks, answered with the site's own
 * rules. Every answer here is a fact the code enforces elsewhere in this Worker:
 * cash only, one kitchen, radius-checked delivery, km-rounded fee.
 */
export interface FaqEntry {
  question: string;
  answer: string;
}

/**
 * The FAQ as plain text, shared by the structured data and the `/about` page so
 * a crawler and a reader are never told two different things.
 */
export function faqEntries(store: SeoStore | null): FaqEntry[] {
  const perKm = taka(store?.deliveryPerKmAmountPaisa);
  const radius = store?.radiusKm ?? 0;
  const minimum = taka(store?.minOrderAmountPaisa);
  const questions: FaqEntry[] = [
    {
      question: "How do I pay for an Undal order?",
      answer:
        "Cash on delivery, and only cash on delivery. There is no card, no mobile wallet and no online payment anywhere in Undal. You pay the rider in taka when the food arrives.",
    },
    {
      question: "Where does Undal HomeKitchen deliver?",
      answer: radius > 0
        ? `Inside ${radius} km of the kitchen in Sylhet. The checkout measures the distance from the pin you drop on the map and tells you plainly if you are outside it, instead of taking an order the kitchen cannot serve.`
        : "Inside a fixed radius of the kitchen in Sylhet. The checkout measures the distance from the pin you drop on the map and tells you plainly if you are outside it.",
    },
    {
      question: "How is the delivery charge worked out?",
      answer: perKm
        ? `৳${perKm} for every whole kilometre from the kitchen to your pin, rounded up. The fee is shown before you place the order and is measured again on the server when you do.`
        : "A per-kilometre charge from the kitchen to your pin, rounded up to whole kilometres. It is shown before you place the order.",
    },
    {
      question: "Do I need the Android app to order?",
      answer:
        "No. The whole menu, the basket and the checkout work in a phone browser. The app adds live status pushes, in-order chat with the kitchen, the prep countdown and reorders, which a browser tab cannot do once it is closed.",
    },
    {
      question: "Can I follow my order without an account?",
      answer:
        "Yes. Every order has a tracking link and the link is the key: anybody holding it can watch that one order from placed to delivered. It never shows your phone number or your address.",
    },
  ];
  if (minimum) {
    questions.push({
      question: "Is there a minimum order?",
      answer: `Yes — the kitchen cooks from ৳${minimum} upwards. The basket says how much more you need before the checkout button will work.`,
    });
  }
  return questions;
}

export function faqJsonLd(origin: string, store: SeoStore | null): Record<string, unknown> {
  return {
    "@context": "https://schema.org",
    "@type": "FAQPage",
    "@id": `${origin}/about#faq`,
    mainEntity: faqEntries(store).map((entry) => ({
      "@type": "Question",
      name: entry.question,
      acceptedAnswer: { "@type": "Answer", text: entry.answer },
    })),
  };
}

/**
 * Every page a crawler should know about, in the order it should find them.
 *
 * Built from the live catalogue so a new dish appears in the sitemap the minute
 * the kitchen publishes it — no manual list to forget.
 */
export function sitemapEntries(
  items: Array<{ id: string; kind?: string }>,
  lastmod?: string,
): SitemapEntry[] {
  const entries: SitemapEntry[] = [
    { path: "/", changefreq: "daily", priority: 1.0, lastmod },
    { path: "/store", changefreq: "daily", priority: 0.9, lastmod },
    { path: "/about", changefreq: "weekly", priority: 0.7, lastmod },
    { path: "/download", changefreq: "weekly", priority: 0.7 },
    { path: "/track", changefreq: "monthly", priority: 0.4 },
    { path: "/login", changefreq: "monthly", priority: 0.3 },
    { path: "/account", changefreq: "monthly", priority: 0.3 },
    { path: "/basket", changefreq: "monthly", priority: 0.3 },
  ];
  for (const item of items) {
    if (!item.id) continue;
    entries.push({ path: `/item/${item.id}`, changefreq: "weekly", priority: 0.6, lastmod });
  }
  return entries;
}
