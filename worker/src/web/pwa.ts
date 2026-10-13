/**
 * The installable-web layer (P-014).
 *
 * A manifest, the two raster icons the manifest needs, and a service worker
 * whose only job is to keep the store page readable when the signal drops —
 * a customer standing in a tin-roofed kitchen should still see the menu they
 * loaded a minute ago. Nothing is cached that carries money or identity: the
 * order API, the session endpoint and the tracking polls always go to the
 * network.
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

/**
 * The service worker. Cache-first for the storefront shell, network-only for
 * anything that can spend money.
 */
export function handleServiceWorker(): Response {
  const source = `/* Undal HomeKitchen service worker — offline shell for the web store. */
const CACHE = "undal-web-v1";
const SHELL = ["/", "/store", "/download", "/manifest.webmanifest", "/favicon.svg"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  // Never cache money or identity.
  if (url.pathname.startsWith("/api/") || url.pathname.startsWith("/latest.json")) return;

  event.respondWith(
    caches.match(request).then((cached) => {
      const network = fetch(request)
        .then((response) => {
          if (response.ok && (request.destination === "document" || url.pathname.startsWith("/store"))) {
            const copy = response.clone();
            caches.open(CACHE).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(() => cached ?? caches.match("/store"));
      return cached || network;
    }),
  );
});
`;
  return new Response(source, {
    headers: {
      "content-type": "text/javascript; charset=utf-8",
      "cache-control": "public, max-age=3600",
      "service-worker-allowed": "/",
    },
  });
}
