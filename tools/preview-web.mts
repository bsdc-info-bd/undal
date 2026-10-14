/**
 * A local preview of the real website, with no Firebase project behind it.
 *
 *     cd worker && npx tsx ../tools/preview-web.mts [port]
 *
 * This runs the *actual* Worker — `worker/src/index.ts`, its router, its pages,
 * its rate limits and its CSP — inside plain Node, in front of the same fake
 * backend the test suite uses (`worker/test/helpers/fake-firebase.ts`). Nothing
 * here is a separate implementation of the storefront: if a page renders in this
 * preview it renders in production, and if the router 404s here it 404s there.
 *
 * What it is for: looking at the site, clicking the checkout map, creating an
 * account and watching the Worker's own JSON come back, without deploying and
 * without spending a Firebase project. What it is not: a load test, a real
 * catalogue, or a substitute for the device and deployment checks in
 * `docs/ALERT-RELIABILITY.md`.
 *
 * The catalogue below is a sample menu for one kitchen in Sylhet. It is served
 * from the fake KV snapshot under the same key and the same 60-second freshness
 * rule the Worker uses in production, so the pages take the cached path they
 * normally take.
 */
import { createServer } from "node:http";
import worker from "../worker/src/index";
import { FakeFirebase } from "../worker/test/helpers/fake-firebase";

const PORT = Number(process.argv[2] ?? process.env.PORT ?? 8787);

// ---------------------------------------------------------------- the catalogue

const now = Date.now();

const store = {
  storeName: "Undal HomeKitchen",
  open: true,
  autoClosed: false,
  manualOverrideNote: "",
  weekly: {
    saturday: [{ open: "11:30", close: "15:00" }, { open: "18:00", close: "22:30" }],
    sunday: [{ open: "11:30", close: "15:00" }, { open: "18:00", close: "22:30" }],
    monday: [{ open: "18:00", close: "22:30" }],
    tuesday: [{ open: "18:00", close: "22:30" }],
    wednesday: [{ open: "18:00", close: "22:30" }],
    thursday: [{ open: "18:00", close: "22:30" }],
    friday: [{ open: "12:00", close: "15:00" }, { open: "18:00", close: "23:00" }],
  },
  specialDates: [],
  deliveryPerKmAmountPaisa: 2500,
  radiusKm: 8,
  minOrderAmountPaisa: 15000,
  storeLat: 24.8951,
  storeLng: 91.8682,
  storeAddressText: "Road 4, Zindabazar, Sylhet 3100",
  bannerUrl: "",
  logoUrl: "",
};

const dish = (
  id: string,
  name: string,
  description: string,
  amountPaisa: number,
  discountPaisa: number,
  categoryId: string,
  extras: Record<string, unknown> = {},
): Record<string, unknown> => ({
  id,
  kind: "dish",
  name,
  description,
  amountPaisa,
  payablePaisa: amountPaisa - discountPaisa,
  discountAmountPaisa: discountPaisa,
  available: true,
  veg: false,
  spiceLevel: 1,
  tags: [],
  orderCount: 4,
  categoryId,
  subcategoryId: "",
  imageUrl: "",
  prepMinutes: 30,
  featured: false,
  ...extras,
});

const menu = {
  fetchedAt: now,
  store,
  sections: [
    { id: "rice", name: "Rice and biryani", order: 1, active: true, iconUrl: "" },
    { id: "curry", name: "Curries and bhorta", order: 2, active: true, iconUrl: "" },
    { id: "bread", name: "Bread and breakfast", order: 3, active: true, iconUrl: "" },
    { id: "sweet", name: "Something sweet", order: 4, active: true, iconUrl: "" },
  ],
  subsections: [],
  campaigns: [],
  combos: [{
    id: "family-friday",
    kind: "combo",
    name: "Friday family table",
    description: "Kacchi for four, a bhorta, a salad and a jug of borhani. Cooked from 12:00.",
    amountPaisa: 128000,
    payablePaisa: 118000,
    discountAmountPaisa: 0,
    regularAmountPaisa: 128000,
    available: true,
    imageUrl: "",
    itemRefs: [
      { menuItemId: "kacchi", qty: 4 },
      { menuItemId: "bhorta-aloo", qty: 1 },
      { menuItemId: "borhani", qty: 1 },
    ],
    featured: true,
  }],
  dishes: [
    dish("kacchi", "Kacchi biryani", "Aged polao rice, mutton, yoghurt and whole spice, sealed and cooked on dum. Served with aloo and borhani.", 32000, 4000, "rice", { spiceLevel: 2, featured: true, prepMinutes: 45, tags: ["signature"], orderCount: 41 }),
    dish("morog-polao", "Chicken polao", "Chicken cooked in ghee and garlic, then finished with the rice. Comes with a cucumber salad.", 26000, 0, "rice", { spiceLevel: 1, prepMinutes: 35, orderCount: 22 }),
    dish("khichuri", "Monsoon khichuri", "Rice and moong dal cooked soft with ginger and green chilli. The plate we make when it rains.", 18000, 0, "rice", { veg: true, spiceLevel: 1, prepMinutes: 30 }),
    dish("bhorta-aloo", "Aloo bhorta", "Mashed potato with mustard oil, fried onion and green chilli.", 9000, 0, "curry", { veg: true, spiceLevel: 2, prepMinutes: 15 }),
    dish("ilish-bhaja", "Ilish bhaja", "Hilsa fried in turmeric and salt, crisp at the edge. Served with steamed rice.", 38000, 0, "curry", { spiceLevel: 1, prepMinutes: 25, featured: true }),
    dish("dal-moshur", "Moshur dal", "Red lentils tempered with panch phoron. Refills are free at our table.", 7000, 0, "curry", { veg: true, spiceLevel: 0, prepMinutes: 20 }),
    dish("porota", "Porota", "Flaky paratha cooked on a tawa with a little ghee. Two per portion.", 6000, 0, "bread", { veg: true, spiceLevel: 0, prepMinutes: 12 }),
    dish("dim-bhuna", "Dim bhuna", "Egg cooked down in onion, ginger and roasted chilli until the masala clings.", 14000, 1000, "bread", { spiceLevel: 2, prepMinutes: 20 }),
    dish("borhani", "Borhani", "Spiced yoghurt drink, cold. The thing that puts out the biryani.", 8000, 0, "sweet", { veg: true, spiceLevel: 0, prepMinutes: 5 }),
    dish("firni", "Firni", "Rice pudding set with cardamom and a little condensed milk, served cold.", 11000, 0, "sweet", { veg: true, spiceLevel: 0, prepMinutes: 10 }),
  ],
};

// ------------------------------------------------------------------- the server

const backend = new FakeFirebase();
backend.kv.set("web:menu:v1", JSON.stringify(menu));
backend.kv.set("release:latest", JSON.stringify({
  versionName: "1.4.0",
  versionCode: 14,
  url: "https://github.com/bsdc-info-bd/undal/releases/latest/download/undal.apk",
  notes: "Kitchen alert ladder, web account and the checkout map.",
  publishedAt: new Date(now).toISOString(),
  mandatory: false,
}));
backend.setDocument("config/store", store);
backend.setDocument("vendor/kitchen-1", { role: "vendor", name: "Undal HomeKitchen", email: "kitchen@example.com" });
backend.setDocument("orders/preview-1", {
  shortId: "UND-1042",
  status: "accepted",
  customerId: "preview-customer",
  customerName: "Ayesha Rahman",
  phone: "01712345678",
  subtotalAmountPaisa: 32000,
  deliveryAmountPaisa: 7500,
  discountAmountPaisa: 4000,
  totalAmountPaisa: 35500,
  prepMinutes: 45,
  items: [{ id: "kacchi", name: "Kacchi biryani", qty: 1, payablePaisa: 28000 }],
  distanceKm: 3,
  createdAt: new Date(now - 6 * 60_000).toISOString(),
  updatedAt: new Date(now - 60_000).toISOString(),
  timeline: [{ status: "placed" }, { status: "accepted" }],
});
// A kitchen device, so an alert sent from this preview has somewhere to go.
backend.setDocument("fcmTokens/kitchen-1/tokens/preview-token-0123456789abcdef", {
  platform: "android",
  appVersion: "1.4.0",
  updatedAt: new Date(now).toISOString(),
});
backend.setRtdb("orderLive/preview-1", { status: "accepted", updatedAt: now });

const restoreFetch = backend.install();
const env = backend.env;

/** Node's IncomingMessage → the standard Request the Worker expects. */
async function toRequest(nodeRequest: import("node:http").IncomingMessage): Promise<Request> {
  const headers = new Headers();
  for (const [key, value] of Object.entries(nodeRequest.headers)) {
    if (typeof value === "string") headers.set(key, value);
    else if (Array.isArray(value)) for (const entry of value) headers.set(key, entry);
  }
  // Behind a proxy the public origin is in the forwarded headers, and the Worker
  // builds every canonical URL, Open Graph tag and sitemap entry from it.
  const scheme = headers.get("x-forwarded-proto")?.split(",")[0]?.trim() || "http";
  const host = headers.get("x-forwarded-host")?.split(",")[0]?.trim() || headers.get("host") || `localhost:${PORT}`;
  const url = new URL(nodeRequest.url ?? "/", `${scheme}://${host}`);
  const method = (nodeRequest.method ?? "GET").toUpperCase();
  const body = method === "GET" || method === "HEAD" || method === "OPTIONS"
    ? undefined
    : Buffer.concat(await new Promise<Buffer[]>((resolve, reject) => {
        const chunks: Buffer[] = [];
        nodeRequest.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
        nodeRequest.on("end", () => resolve(chunks));
        nodeRequest.on("error", reject);
      }));
  return new Request(url.toString(), { method, headers, body, duplex: "half" } as RequestInit);
}

const server = createServer(async (nodeRequest, nodeResponse) => {
  const startedAt = Date.now();
  try {
    const request = await toRequest(nodeRequest);
    const response = await worker.fetch(request, env, {} as never);
    const buffer = Buffer.from(await response.arrayBuffer());
    const headers: Record<string, string> = {};
    response.headers.forEach((value, key) => {
      headers[key] = value;
    });
    nodeResponse.writeHead(response.status, { ...headers, "content-length": String(buffer.length) });
    nodeResponse.end(buffer);
    console.log(`${response.status} ${(Date.now() - startedAt)}ms ${nodeRequest.method} ${nodeRequest.url}`);
  } catch (error) {
    console.error("preview failure", error);
    nodeResponse.writeHead(500, { "content-type": "text/plain; charset=utf-8" });
    nodeResponse.end(`The preview hit an error: ${error instanceof Error ? error.message : String(error)}`);
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Undal web preview on http://0.0.0.0:${PORT}`);
  console.log("Pages: / /store /item/kacchi /basket /checkout /login /account /about /track?id=preview-1 /order/preview-1 /download /robots.txt /sitemap.xml /healthz");
  console.log("Every request is served by worker/src/index.ts against an in-memory Firebase.");
});

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    restoreFetch();
    server.close(() => process.exit(0));
  });
}
