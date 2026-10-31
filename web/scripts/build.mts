/**
 * Builds the Cloudflare Pages output directory: the customer website, rendered
 * once per public page, at deploy time, by the code that renders it in
 * production.
 *
 *     cd worker && npm run build:web
 *     cd worker && npm run build:web -- --origin https://undal.example --source sample
 *
 * There is no second implementation of the storefront here and no template
 * language. The build asks `worker/src/index.ts` for each URL — the same router,
 * the same pages, the same SEO head, the same CSP — and writes the bytes it is
 * answered with into `web/public/`. A page that renders in production renders
 * here; a route that 404s there 404s here, and the build says so instead of
 * writing an empty file that Pages would serve as a snapshot.
 *
 * Where the menu comes from decides what the build is allowed to publish:
 *
 *   `live`    Firebase credentials are present, so the build reads the kitchen's
 *             real menu over the Firestore REST API and renders it. This is what
 *             `deploy-pages.yml` requires.
 *   `sample`  No credentials. The build runs against the same fake backend the
 *             test suite and `tools/preview-web.mts` use, seeded from the
 *             published catalogue in `tools/undal-menu-catalog.json`, and every
 *             page it writes is stamped `data-prerender-source="sample"`. Those
 *             pages are for looking at the site on a laptop; `deploy-pages.yml`
 *             refuses to publish them, because a snapshot of a menu nobody is
 *             cooking is worse than no snapshot at all.
 *
 * A build with no credentials still produces a complete, deployable directory:
 * the artwork, the manifest, the service worker, the error pages and a manifest
 * of snapshots that is empty — and an empty manifest means the Pages Function
 * renders every request, which is exactly what the site did before this file
 * existed. Nothing here can produce a broken deployment.
 */
import { existsSync } from "node:fs";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import worker from "../../worker/src/index";
import type { Env } from "../../worker/src/env";
import { failurePage } from "../../worker/src/web/error-pages";
import { FakeFirebase } from "../../worker/test/helpers/fake-firebase";
import {
  FUNCTION_EXCLUDED_PATHS,
  SNAPSHOT_ASSET_PATHS,
  SNAPSHOT_PAGE_PATHS,
  pathsFromSitemap,
  snapshotFilePath,
  stampPrerendered,
  type PrerenderManifest,
} from "../src/prerender";

const here = dirname(fileURLToPath(import.meta.url));
const webRoot = resolve(here, "..");
const repoRoot = resolve(webRoot, "..");

const DEFAULT_ORIGIN = "https://undal-web.pages.dev";
const CATALOGUE_PATH = join(repoRoot, "tools", "undal-menu-catalog.json");
const USER_AGENT = "undal-prerender/1.0 (+https://github.com/bsdc-info-bd/undal)";

// ------------------------------------------------------------------- arguments

interface Options {
  origins: string[];
  output: string;
  source: "live" | "sample" | "auto";
}

function parseOptions(argv: string[]): Options {
  const origins: string[] = [];
  let output = "";
  let requested = "";
  for (const [index, argument] of argv.entries()) {
    if (argument === "--origin") origins.push(argv[index + 1] ?? "");
    else if (argument.startsWith("--origin=")) origins.push(argument.slice("--origin=".length));
    else if (argument === "--output") output = argv[index + 1] ?? "";
    else if (argument.startsWith("--output=")) output = argument.slice("--output=".length);
    else if (argument === "--source") requested = argv[index + 1] ?? "";
    else if (argument.startsWith("--source=")) requested = argument.slice("--source=".length);
    else if (argument.startsWith("--")) throw new Error(`build:web does not understand ${argument}`);
  }
  const fromEnv = (process.env.UNDAL_WEB_ORIGINS ?? process.env.UNDAL_WEB_ORIGIN ?? "")
    .split(",")
    .map((origin) => origin.trim().replace(/\/+$/, ""))
    .filter((origin) => origin.length > 0);
  const chosen = origins.map((origin) => origin.trim().replace(/\/+$/, "")).filter((origin) => origin.length > 0);
  const list = chosen.length > 0 ? chosen : fromEnv.length > 0 ? fromEnv : [DEFAULT_ORIGIN];
  for (const origin of list) {
    // An origin that is not absolute would render canonical URLs against
    // whatever host the build happened to guess, and the snapshots would tell
    // Google to index that guess.
    if (!/^https:\/\/[^\s/]+/.test(origin)) throw new Error(`build:web needs an absolute https origin, got "${origin}"`);
  }
  const resolvedOutput = resolve(output || process.env.UNDAL_WEB_OUTPUT || join(webRoot, "public"));
  assertSafeOutput(resolvedOutput);
  const source = requested === "live" || requested === "sample" ? requested : requested ? "" : "auto";
  if (requested && source === "") throw new Error(`build:web --source is "live" or "sample", got "${requested}"`);
  return { origins: list, output: resolvedOutput, source };
}

/**
 * The build empties the output directory before it writes to it, so the directory
 * it is allowed to empty is the interesting question.
 *
 * `web/public` is the answer in a deploy, and a temporary directory is the answer
 * in a test. What is never the answer is the repository, the Pages project or
 * anything with a `.git`, a `package.json` or a `src` inside it: a mis-typed
 * `--output` should cost an error message, not a checkout.
 */
function assertSafeOutput(resolvedOutput: string): void {
  if (resolvedOutput === repoRoot || resolvedOutput === webRoot) {
    throw new Error(`build:web will not write to ${resolvedOutput}`);
  }
  // An ancestor of the project would be emptied along with the project.
  if (webRoot.startsWith(resolvedOutput) || repoRoot.startsWith(resolvedOutput)) {
    throw new Error(`build:web will not write to ${resolvedOutput}: the project lives inside it`);
  }
  for (const marker of [".git", "package.json", "src", "app", "worker"]) {
    if (existsSync(join(resolvedOutput, marker))) {
      throw new Error(`build:web will not empty ${resolvedOutput}: it contains ${marker}`);
    }
  }
}

/** True when the environment carries a Firebase service account worth using. */
function hasLiveCredentials(): boolean {
  const json = process.env.FIREBASE_SA_JSON ?? "";
  const rtdb = process.env.FIREBASE_RTDB_URL ?? "";
  if (!json || !rtdb) return false;
  try {
    const parsed = JSON.parse(json) as { project_id?: string; client_email?: string; private_key?: string };
    return Boolean(parsed.project_id && parsed.client_email && parsed.private_key);
  } catch {
    return false;
  }
}

// -------------------------------------------------------------------- backends

/**
 * A KV namespace that lives for the length of the build.
 *
 * Pages builds run in Node, where there is no KV binding, and the router needs
 * one: the menu cache reads and writes it, and the rate limiter escalates to it.
 * Nothing a build writes there is worth keeping — it is thrown away with the
 * process — so an in-memory map is the honest binding, and the menu is read from
 * Firestore exactly once per build instead of once per page.
 */
function buildTimeKv(): Env["UNDAL_KV"] {
  const store = new Map<string, string>();
  return {
    get: async (key: string, type?: string) => {
      const value = store.get(key);
      if (value === undefined) return null;
      return type === "json" ? JSON.parse(value) : value;
    },
    put: async (key: string, value: string) => {
      store.set(key, value);
    },
    delete: async (key: string) => {
      store.delete(key);
    },
  } as unknown as Env["UNDAL_KV"];
}

/** The real bindings, from the environment the deploy workflow provides. */
function liveEnv(): Env {
  return {
    UNDAL_KV: buildTimeKv(),
    FIREBASE_SA_JSON: process.env.FIREBASE_SA_JSON ?? "",
    FIREBASE_RTDB_URL: process.env.FIREBASE_RTDB_URL ?? "",
    APP_SIGNING_SHA256: process.env.APP_SIGNING_SHA256 ?? "",
    RELEASE_WEBHOOK_SECRET: process.env.RELEASE_WEBHOOK_SECRET ?? "",
    CLOUDINARY_CLOUD_NAME: process.env.CLOUDINARY_CLOUD_NAME ?? "",
    APP_PACKAGE_NAME: process.env.APP_PACKAGE_NAME ?? "com.undal.syl.app",
    GITHUB_REPO_SLUG: process.env.GITHUB_REPO_SLUG ?? "bsdc-info-bd/undal",
    PROMO_TOPIC: process.env.PROMO_TOPIC ?? "promos_all",
    KITCHEN_TOPIC: process.env.KITCHEN_TOPIC ?? "undal_kitchen",
    FIREBASE_WEB_API_KEY: process.env.FIREBASE_WEB_API_KEY ?? "",
  } as Env;
}

/**
 * The fake backend the test suite uses, seeded from the published catalogue.
 *
 * The documents are written in the shape `tools/seed-config.mjs` and
 * `tools/seed-menu.mjs` write them, so the sample storefront renders the same
 * markup production renders — same fields, same defaults, same missing-photo
 * artwork. The one thing it does not have is a real kitchen behind it, which is
 * why the manifest records `sample` and the deploy workflow refuses it.
 */
async function sampleEnv(): Promise<{ env: Env; backend: FakeFirebase }> {
  const catalog = JSON.parse(await readFile(CATALOGUE_PATH, "utf8")) as {
    source: string;
    imageBase: string;
    categories: Array<{ id: string; name: string }>;
    items: Array<[string, string, number, number, string, boolean, boolean]>;
  };
  const backend = new FakeFirebase();
  const weekdays = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];
  backend.setDocument("config/store", {
    storeName: "Undal HomeKitchen",
    open: false,
    autoClosed: false,
    manualOverrideNote: "The kitchen opens shortly — please check back in a few minutes.",
    weekly: Object.fromEntries(weekdays.map((day) => [day, [{ open: "09:00", close: "22:00" }]])),
    specialDates: [],
    deliveryPerKmAmountPaisa: 2000,
    radiusKm: 5,
    minOrderAmountPaisa: 15000,
    storeLat: 24.8949,
    storeLng: 91.8687,
    storeAddressText: "Zindabazar, Sylhet",
    bannerUrl: "",
    logoUrl: "",
  });
  for (const [index, category] of catalog.categories.entries()) {
    backend.setDocument(`categories/${category.id}`, { name: category.name, order: index, active: true, iconUrl: "" });
  }
  for (const [id, name, saleTaka, regularTaka, categoryId, veg, spicy] of catalog.items) {
    const amountPaisa = regularTaka * 100;
    backend.setDocument(`menuItems/${id}`, {
      name,
      description: "",
      amountPaisa,
      discountAmountPaisa: amountPaisa - saleTaka * 100,
      available: true,
      veg,
      spiceLevel: spicy ? 2 : 0,
      tags: [...(veg ? ["vegetarian"] : []), ...(spicy ? ["spicy"] : [])],
      orderCount: 0,
      categoryId,
      subcategoryId: "",
      imageUrl: "",
      featured: false,
      sourceUrl: catalog.source,
    });
  }
  backend.kv.set(
    "release:latest",
    JSON.stringify({
      versionName: "0.0.0",
      versionCode: 0,
      url: "",
      notes: "",
      publishedAt: new Date().toISOString(),
      mandatory: false,
    }),
  );
  // The interceptor is what makes the fake Firestore answer: without it the
  // build would send real HTTPS requests to a project that does not exist.
  backend.install();
  return { env: backend.env, backend };
}

// ------------------------------------------------------------------- rendering

interface Rendered {
  path: string;
  status: number;
  body: Buffer;
  contentType: string;
}

const executionContext = {
  waitUntil: (promise: Promise<unknown>) => {
    void promise.catch(() => undefined);
  },
  passThroughOnException: () => undefined,
} as unknown as ExecutionContext;

/** Asks the real router for one URL and returns whatever it answered. */
async function render(env: Env, origin: string, path: string): Promise<Rendered> {
  const request = new Request(`${origin}${path}`, {
    headers: {
      accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      "user-agent": USER_AGENT,
      "accept-language": "en-GB,en;q=0.9",
    },
  });
  const response = await worker.fetch(request, env, executionContext);
  const contentType = response.headers.get("content-type") ?? "";
  return { path, status: response.status, body: Buffer.from(await response.arrayBuffer()), contentType };
}

// ----------------------------------------------------------------------- build

async function main(): Promise<void> {
  const options = parseOptions(process.argv.slice(2));
  console.log(`build:web — output ${options.output}, origins ${options.origins.join(", ")}.`);
  const live = hasLiveCredentials();
  const source: "live" | "sample" = options.source === "sample" ? "sample" : options.source === "live" ? "live" : live ? "live" : "sample";
  if (options.source === "live" && !live) {
    throw new Error(
      "build:web --source live needs FIREBASE_SA_JSON and FIREBASE_RTDB_URL in the environment.\n" +
        "Repair: pass the same secrets deploy-worker.yml uses, or build with --source sample.",
    );
  }
  if (source === "sample") {
    console.log("build:web — no Firebase credentials in the environment; rendering the published catalogue as a sample.");
    console.log("build:web — these pages are stamped data-prerender-source=\"sample\" and deploy-pages.yml refuses to publish them.");
  }

  const { env } = source === "live" ? { env: liveEnv() } : await sampleEnv();
  const builtAt = new Date().toISOString();
  const origin = options.origins[0] ?? DEFAULT_ORIGIN;

  // The route list comes from the sitemap, so a page Google is told about and a
  // page that is prerendered cannot drift apart: a new dish in the menu appears
  // in both without anybody editing this file.
  const sitemap = await render(env, origin, "/sitemap.xml");
  if (sitemap.status !== 200) {
    throw new Error(
      `build:web could not read ${origin}/sitemap.xml (HTTP ${sitemap.status}), so it does not know which pages exist.\n` +
        "Repair: check the Firebase credentials and the Firestore indexes, then run this build again.",
    );
  }
  const sitemapPaths = pathsFromSitemap(sitemap.body.toString("utf8"), origin);
  const pages = new Set<string>([...SNAPSHOT_PAGE_PATHS, ...sitemapPaths.filter((path) => path.startsWith("/item/"))]);
  const assets = new Set<string>([...SNAPSHOT_ASSET_PATHS, "/sitemap.xml"]);
  console.log(
    `build:web — ${pages.size} pages (${[...pages].filter((path) => path.startsWith("/item/")).length} of them dishes), ` +
      `${assets.size} files, ${options.origins.length} origin(s), source ${source}.`,
  );

  await rm(options.output, { recursive: true, force: true });
  await mkdir(options.output, { recursive: true });

  const manifestPages: Record<string, string> = {};
  const failures: string[] = [];
  let itemCount = 0;

  for (const [originIndex, buildOrigin] of options.origins.entries()) {
    const folder = originIndex === 0 ? options.output : join(options.output, "_snapshots", String(originIndex));
    for (const path of [...pages, ...assets]) {
      const file = snapshotFilePath(path);
      if (!file) continue;
      const rendered = await render(env, buildOrigin, path);
      if (rendered.status !== 200) {
        const core = (SNAPSHOT_PAGE_PATHS as readonly string[]).indexOf(path) >= 0 || path === "/sitemap.xml";
        failures.push(`${buildOrigin}${path} → HTTP ${rendered.status}`);
        if (core) {
          throw new Error(
            `build:web could not render ${buildOrigin}${path} (HTTP ${rendered.status}).\n` +
              "A core page that does not render is not a build worth publishing: the snapshots would be missing the menu.",
          );
        }
        continue;
      }
      const body = rendered.contentType.includes("text/html")
        ? Buffer.from(stampPrerendered(rendered.body.toString("utf8"), builtAt, source), "utf8")
        : rendered.body;
      const target = join(folder, file);
      await mkdir(dirname(target), { recursive: true });
      await writeFile(target, body);
      if (originIndex === 0) {
        manifestPages[path] = file;
        if (path.startsWith("/item/")) itemCount += 1;
      }
    }
  }

  // The two error pages Pages itself serves when a Function cannot answer at all.
  // They are rendered by the same code the router uses, so the 404 a customer
  // sees from Pages and the one they see from the Function are one design.
  const notFound = await render(env, origin, "/a-page-that-does-not-exist");
  await writeFile(join(options.output, "404.html"), notFound.body);
  const failure = failurePage("/store");
  await writeFile(join(options.output, "500.html"), Buffer.from(await failure.text(), "utf8"));

  // Hand-written rules first, then one canonical redirect per snapshot: `/store/`
  // and `/store/index.html` are the page at `/store`, and only one of the three
  // should be in an index.
  const redirects = (await readFile(join(webRoot, "static", "_redirects"), "utf8")).trimEnd();
  const generated = [...pages]
    .filter((path) => path !== "/")
    .sort()
    .map((path) => `${path}/index.html ${path} 301`)
    .join("\n");
  await writeFile(join(options.output, "_redirects"), `${redirects}\n\n# Generated by web/scripts/build.mts — one canonical URL per snapshot.\n${generated}\n`);
  await writeFile(join(options.output, "_headers"), await readFile(join(webRoot, "static", "_headers"), "utf8"));
  await writeFile(
    join(options.output, "_routes.json"),
    `${JSON.stringify({ version: 1, include: ["/*"], exclude: [...FUNCTION_EXCLUDED_PATHS] }, null, 2)}\n`,
  );

  const manifest: PrerenderManifest = {
    version: 1,
    builtAt,
    source,
    origins: options.origins,
    pages: manifestPages,
    items: itemCount,
  };
  await writeFile(join(options.output, "prerender-manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);

  const written = Object.keys(manifestPages).length;
  console.log(`build:web — wrote ${written} snapshots for ${origin}${options.origins.length > 1 ? ` (+${options.origins.length - 1} more origin(s))` : ""}.`);
  console.log(`build:web — assets, error pages, _headers, _redirects, _routes.json and prerender-manifest.json are in ${options.output}.`);
  if (failures.length > 0) {
    console.log(`build:web — ${failures.length} page(s) did not render and were skipped:`);
    for (const entry of failures.slice(0, 20)) console.log(`  · ${entry}`);
    if (failures.length > 20) console.log(`  · …and ${failures.length - 20} more`);
  }
  console.log(`build:web — source: ${source}. A snapshot older than PRERENDER_MAX_AGE_SECONDS is rendered live instead.`);
}

await main();
