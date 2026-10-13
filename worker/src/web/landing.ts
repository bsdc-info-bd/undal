/**
 * The kitchen's own page: who they are, when they open, what delivery costs.
 *
 * Served at `/about` now that the root of the site is the storefront itself.
 */
import type { Env } from "../env";
import { fsGet } from "../firestore";
import { escapeHtml } from "../util/http";
import { formatPaisa } from "../util/money";
import { dhakaClock, storeStatus, type StoreConfig } from "../util/store-hours";
import { renderPage } from "./layout";

function slotLine(config: StoreConfig, weekday: string): string {
  const specials = config.specialDates ?? [];
  const clock = dhakaClock();
  const today = specials.find((entry) => entry.date === clock.dateKey);
  const slots = today
    ? today.mode === "closed"
      ? []
      : today.slots ?? []
    : config.weekly?.[weekday] ?? [];
  if (slots.length === 0) return "Closed today";
  return slots.map((slot) => `${escapeHtml(slot.open)} – ${escapeHtml(slot.close)}`).join(" · ");
}

export async function handleAboutPage(env: Env, request: Request): Promise<Response> {
  const config = (await fsGet(env, "config/store").catch(() => null)) as StoreConfig | null;
  const clock = dhakaClock();
  const status = storeStatus(config);
  const origin = new URL(request.url).origin;

  const storeName = escapeHtml(config?.storeName ?? "Undal HomeKitchen");
  const address = escapeHtml(config?.storeAddressText ?? "Sylhet, Bangladesh");
  const perKm = Number(config?.deliveryPerKmAmountPaisa ?? 0);
  const radius = Number(config?.radiusKm ?? 0);
  const minOrder = Number(config?.minOrderAmountPaisa ?? 0);

  const deliveryRows = [
    perKm > 0 ? `<dt>Delivery</dt><dd>${escapeHtml(formatPaisa(perKm))} per km (rounded up)</dd>` : "",
    radius > 0 ? `<dt>Radius</dt><dd>${radius} km from the kitchen</dd>` : "",
    minOrder > 0 ? `<dt>Minimum order</dt><dd>${escapeHtml(formatPaisa(minOrder))}</dd>` : "",
    `<dt>Payment</dt><dd>Cash on delivery only</dd>`,
  ]
    .filter(Boolean)
    .join("");

  const body = `
<section class="card">
  <span class="pill ${status.openNow ? "open" : "closed"}">${escapeHtml(status.headline)}</span>
  <h2 style="margin-top:10px">${storeName}</h2>
  <p class="muted" style="margin-top:0">${address}</p>
  <p>${escapeHtml(status.detail)}</p>
  <dl>
    <dt>Today (${escapeHtml(clock.weekday.toUpperCase())})</dt><dd>${slotLine(config ?? {}, clock.weekday)}</dd>
    ${deliveryRows}
  </dl>
</section>

<section class="card">
  <h2>Three ways to order</h2>
  <p class="muted">The menu, the prices and the cash-on-delivery rule are identical everywhere.</p>
  <a class="button" href="/store">Order from this browser</a>
  <a class="button secondary" href="/download">Install the Android app</a>
  <p class="smallprint">The app adds push notifications on every status change, the prep countdown, in-order chat with
  the kitchen and reorders — all of which the browser cannot do. Everything else, including the delivery fee and the
  order total, is the same.</p>
</section>

<section class="card">
  <h2>Share the kitchen</h2>
  <p class="muted">Send this link to anyone in Sylhet:</p>
  <p><code>${escapeHtml(origin)}</code></p>
  <a class="button secondary" href="https://wa.me/?text=${encodeURIComponent(`Undal HomeKitchen — home-cooked food delivered in Sylhet: ${origin}`)}">Share on WhatsApp</a>
</section>`;

  return renderPage({
    title: `${config?.storeName ?? "Undal HomeKitchen"} — home-cooked food in Sylhet`,
    description:
      "Undal HomeKitchen: single-kitchen food delivery in Sylhet with cash on delivery, km-based delivery fee and live order tracking.",
    body,
    ogUrl: origin,
    cacheSeconds: 30,
  });
}
