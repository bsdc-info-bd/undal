/**
 * Deep links and share cards (PART N-009 · N-010 · N-015).
 *
 * Every shared link follows the same pattern: if the app is installed the page
 * hands the device an `intent://` URL that resolves to `undal://…`; if it is
 * not, the visitor stays on the web page and is offered the APK.
 */
import type { Env } from "../env";
import { fsGet } from "../firestore";
import type { FsRecord } from "../firestore";
import { escapeHtml } from "../util/http";
import { formatPaisa } from "../util/money";
import { renderPage } from "./layout";

function cloudinaryTransform(url: string, transform: string): string {
  const marker = "/image/upload/";
  const index = url.indexOf(marker);
  if (index === -1) return url;
  const cut = index + marker.length;
  return `${url.slice(0, cut)}${transform}/${url.slice(cut)}`;
}

function intentUrl(env: Env, targetPath: string, fallbackUrl: string): string {
  return `intent://${targetPath}#Intent;scheme=undal;package=${env.APP_PACKAGE_NAME};S.browser_fallback_url=${encodeURIComponent(fallbackUrl)};end`;
}

function openButton(intent: string, label: string): string {
  return `<a class="button" href="${escapeHtml(intent)}">${escapeHtml(label)}</a>`;
}

/** `/app` — hand the visitor to the installed app, or show the download card. */
export function handleSmartOpen(env: Env, request: Request, targetPath: string): Response {
  const origin = new URL(request.url).origin;
  const fallback = targetPath.length > 0 ? `${origin}/${targetPath}` : `${origin}/`;
  const intent = intentUrl(env, targetPath, fallback);

  const body = `
<section class="card">
  <h2>Opening Undal</h2>
  <p class="muted">If Undal HomeKitchen is installed on this phone, the app opens now. Otherwise you stay right here.</p>
  ${openButton(intent, "Open the Undal app")}
  <a class="button secondary" href="/download">Get the app (Android)</a>
</section>`;

  return renderPage({
    title: "Open Undal HomeKitchen",
    description: "Open the Undal HomeKitchen app or install it in one tap.",
    body,
    ogUrl: `${origin}/app`,
    cacheSeconds: 0,
  });
}

/** `/order/{id}` — no order data is exposed to unauthenticated visitors. */
export function handleOrderOpenPage(env: Env, request: Request, orderId: string): Response {
  const origin = new URL(request.url).origin;
  const cleanId = orderId.replace(/[^A-Za-z0-9_-]/g, "").slice(0, 120);
  const intent = intentUrl(env, `order/${cleanId}`, `${origin}/app`);

  const body = `
<section class="card">
  <h2>Your Undal order</h2>
  <p class="muted">The link you followed is the key to this one order. The tracking page shows its status, the cash
  amount and the kitchen's note — never your phone number or your address.</p>
  <a class="button" href="/order/${escapeHtml(cleanId)}">Watch this order's status</a>
  ${openButton(intent, "Open the full order in the app")}
  <a class="button secondary" href="/download">Install Undal</a>
  <p class="smallprint">The app adds the prep countdown, the in-order chat with the kitchen and a push on every
  status change. The web page stays honest about what it does not have.</p>
</section>`;

  return renderPage({
    title: `Undal order ${escapeHtml(cleanId.slice(-6))} — status`,
    description: "Follow this Undal order's status on the web, or open it in the app for live tracking.",
    body,
    cacheSeconds: 0,
  });
}

/** `/dish/{id}` and `/combo/{id}` — rich share cards with a real menu photo. */
export async function handleItemSharePage(
  env: Env,
  request: Request,
  kind: "dish" | "combo",
  rawId: string,
): Promise<Response> {
  const origin = new URL(request.url).origin;
  const id = rawId.replace(/[^A-Za-z0-9_-]/g, "").slice(0, 120);
  const collection = kind === "dish" ? "menuItems" : "combos";
  const record = (await fsGet(env, `${collection}/${id}`).catch(() => null)) as FsRecord | null;

  if (!record) {
    return renderPage({
      title: "Not on the menu",
      description: "This Undal dish is no longer on the menu — browse the live menu in the app.",
      body: `
<section class="card">
  <h2>This item is no longer on the menu</h2>
  <p class="muted">The kitchen updates the menu from the app, so this dish or combo may have been renamed or removed.</p>
  <a class="button" href="/download">Get the Undal app</a>
  <a class="button secondary" href="/">See the live store</a>
</section>`,
      cacheSeconds: 30,
    });
  }

  const name = String(record.name ?? (kind === "dish" ? "Undal dish" : "Undal combo"));
  const description = String(record.desc ?? "Home-cooked in Sylhet, delivered cash on delivery.");
  const amountPaisa = Number(record.amountPaisa ?? 0);
  const compareAt = Number(record.compareAtAmountPaisa ?? record.regularAmountPaisa ?? 0);
  const imageUrl = typeof record.imageUrl === "string" ? record.imageUrl : "";
  const heroImage = imageUrl.length > 0 ? cloudinaryTransform(imageUrl, "w_1200,c_fill,q_auto,f_auto") : "";
  const shareImage = imageUrl.length > 0 ? cloudinaryTransform(imageUrl, "w_1200,h_630,c_fill,q_auto,f_auto") : "";
  const available = record.available !== false;
  const targetPath = `${kind}/${id}`;
  const intent = intentUrl(env, targetPath, `${origin}/${targetPath}`);

  const savings =
    compareAt > amountPaisa && amountPaisa > 0
      ? `<p class="muted" style="margin:2px 0 0">Regular ${escapeHtml(formatPaisa(compareAt))} · you save ${escapeHtml(
          formatPaisa(compareAt - amountPaisa),
        )}</p>`
      : "";

  const body = `
<section class="card">
  ${heroImage ? `<img class="hero" src="${escapeHtml(heroImage)}" alt="${escapeHtml(name)}">` : ""}
  <h2>${escapeHtml(name)}</h2>
  <p class="muted" style="margin-top:0">${escapeHtml(description)}</p>
  <p style="font-size:1.35rem;font-weight:750;margin:6px 0 0">${escapeHtml(formatPaisa(amountPaisa))}</p>
  ${savings}
  ${available ? "" : `<p class="muted">Sold out right now — back soon.</p>`}
  ${available ? `<a class="button" href="/item/${escapeHtml(id)}">Add it to a basket on the web</a>` : ""}
  ${openButton(intent, available ? "Order in the Undal app" : "Get the Undal app")}
  <a class="button secondary" href="/download">Install Undal (Android)</a>
  <p class="muted">Cash on delivery · delivery fee is per km from the kitchen · the same price in the app and on the web</p>
</section>`;

  return renderPage({
    title: `${name} — Undal HomeKitchen`,
    description: description.slice(0, 180),
    body,
    ogImage: shareImage.length > 0 ? shareImage : undefined,
    ogUrl: `${origin}/${targetPath}`,
    cacheSeconds: 60,
  });
}
