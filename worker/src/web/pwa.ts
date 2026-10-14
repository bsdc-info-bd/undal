/**
 * The installable-web layer (P-014).
 *
 * A manifest, the two raster icons the manifest needs, and a service worker
 * whose only job is to keep the store page readable when the signal drops — a
 * customer standing in a tin-roofed kitchen should still see the menu they
 * loaded a minute ago. Nothing is cached that carries money or identity: the
 * order API, the session endpoint and the tracking polls always go to the
 * network.
 *
 * Network-first for pages, and that is a repair
 * --------------------------------------------
 * The first version of this worker served documents **cache-first**. On a phone
 * that had visited the store before — or installed it — the browser answered
 * every navigation from its own cache, so a deploy that fixed the sign-in form
 * reached a tablet (fresh install, empty cache) and never reached the phone that
 * was actually using it. Page reloads looked fine and the site kept doing what
 * the old code did. The pages a customer can *act* on are now fetched from the
 * network first and only fall back to the cache when the network fails; static
 * pictures and icons stay cache-first, because those never carry a bug fix.
 *
 * The script itself is written in the oldest dialect a phone browser can run.
 * A service worker that throws a syntax error is a service worker that never
 * installs — and the phone then keeps running the *previous* one, forever.
 */
import { undalIcon } from "./png";

export function handleManifest(): Response {
  const body = {
    name: "Undal HomeKitchen",
    short_name: "Undal",
    description: "Home-cooked food from one Sylhet kitchen, cash on delivery.",
    start_url: "/store?source=pwa",
    scope: "/",
    display: "standalone",
    orientation: "portrait",
    background_color: "#FFFBF4",
    theme_color: "#0B5A2B",
    categories: ["food", "shopping"],
    icons: [
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      { src: "/icons/icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
  };
  return new Response(JSON.stringify(body), {
    headers: {
      "content-type": "application/manifest+json; charset=utf-8",
      "cache-control": "public, max-age=86400",
    },
  });
}

export async function handleIcon(path: string): Promise<Response> {
  const match = /^\/icons\/icon-(maskable-)?(\d{3})\.png$/.exec(path);
  if (!match) return new Response("not found", { status: 404 });
  const size = Number(match[2]);
  if (![180, 192, 256, 384, 512].includes(size)) {
    return new Response("unsupported size", { status: 404 });
  }
  const variant = match[1] ? "maskable" : "any";
  const png = await undalIcon({ size, variant });
  return new Response(png, {
    headers: {
      "content-type": "image/png",
      "cache-control": "public, max-age=604800, immutable",
      "content-length": String(png.length),
    },
  });
}

/** Bump this when the caching rules change: one string, and every phone re-caches. */
export const SERVICE_WORKER_CACHE = "undal-web-v2";

/** Pages that must never be served from a cache, in either direction. */
export const SERVICE_WORKER_LIVE_PATHS = ["/basket", "/checkout", "/login", "/account", "/track", "/order/"];

/**
 * The service worker. Network-first for anything a customer acts on, cache-first
 * for the pictures and icons around it, and never for money or identity.
 */
export function handleServiceWorker(): Response {
  const source = `/* Undal HomeKitchen service worker — offline shell for the web store. */
/* v2: pages are network-first. v1 answered navigations from the cache, which is
   how a phone that had once loaded the store kept running the old storefront
   after every deploy while a fresh tablet showed the new one. */
var CACHE = ${JSON.stringify(SERVICE_WORKER_CACHE)};
var SHELL = ["/", "/store", "/download", "/manifest.webmanifest", "/favicon.svg"];
var LIVE = ${JSON.stringify(SERVICE_WORKER_LIVE_PATHS)};

function isLive(path) {
  for (var i = 0; i < LIVE.length; i++) {
    if (path === LIVE[i] || path.indexOf(LIVE[i]) === 0) return true;
  }
  return false;
}

function isStatic(path) {
  if (path.indexOf("/icons/") === 0) return true;
  if (path === "/favicon.svg") return true;
  if (path === "/manifest.webmanifest") return true;
  if (path.indexOf("/latest.json") === 0) return false;
  return false;
}

self.addEventListener("install", function (event) {
  event.waitUntil(
    caches.open(CACHE).then(function (cache) {
      return cache.addAll(SHELL);
    }).then(function () {
      return self.skipWaiting();
    }).catch(function () {
      return self.skipWaiting();
    }),
  );
});

self.addEventListener("activate", function (event) {
  event.waitUntil(
    caches.keys().then(function (keys) {
      var stale = [];
      for (var i = 0; i < keys.length; i++) {
        if (keys[i] !== CACHE) stale.push(keys[i]);
      }
      return Promise.all(stale.map(function (key) { return caches.delete(key); }));
    }).then(function () {
      return self.clients.claim();
    }),
  );
});

self.addEventListener("fetch", function (event) {
  var request = event.request;
  if (request.method !== "GET") return;
  var url;
  try { url = new URL(request.url); } catch (error) { return; }
  if (url.origin !== self.location.origin) return;
  var path = url.pathname;
  // Never cache money or identity, and never serve them from a cache either.
  if (path.indexOf("/api/") === 0 || path.indexOf("/notify/") === 0) return;
  if (isLive(path)) return;

  if (isStatic(path)) {
    event.respondWith(
      caches.match(request).then(function (cached) {
        if (cached) return cached;
        return fetch(request).then(function (response) {
          if (response && response.ok) {
            var copy = response.clone();
            caches.open(CACHE).then(function (cache) { cache.put(request, copy); });
          }
          return response;
        });
      }),
    );
    return;
  }

  // Everything else — the menu above all — is fetched fresh, and only an
  // offline phone is answered from the cache.
  event.respondWith(
    fetch(request).then(function (response) {
      if (response && response.ok && (request.destination === "document" || path === "/store" || path === "/")) {
        var copy = response.clone();
        caches.open(CACHE).then(function (cache) { cache.put(request, copy); });
      }
      return response;
    }).catch(function () {
      return caches.match(request).then(function (cached) {
        if (cached) return cached;
        return caches.match("/store").then(function (storePage) {
          if (storePage) return storePage;
          return new Response(
            "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<title>Undal — offline</title><p style='font:16px system-ui;padding:24px'>" +
            "You are offline. The Undal menu will come back the moment the signal does.</p>",
            { headers: { "content-type": "text/html; charset=utf-8" } },
          );
        });
      });
    }),
  );
});
`;
  return new Response(source, {
    headers: {
      "content-type": "text/javascript; charset=utf-8",
      // A service worker must not be cached for long, or a fix cannot reach the
      // browser that is holding the old one. The browser's own 24-hour cap is
      // longer than this on purpose.
      "cache-control": "no-cache, must-revalidate",
      "service-worker-allowed": "/",
    },
  });
}
