/**
 * The two pages a person sees when Undal cannot answer.
 *
 * Until now an unknown URL answered `{"ok":false,"error":"not-found"}` — the
 * right answer for the Android app, for a webhook and for a script, and
 * unreadable for somebody who followed a link out of a chat message. Both
 * statuses keep their JSON for those clients; a browser that says it accepts
 * HTML now gets a page with a way back to the menu.
 *
 * Two rules the negotiation lives by:
 *
 * 1. An API path is never HTML. The app parses `error` and `hint` out of the
 *    JSON body and shows the hint to a merchant; handing it markup would turn a
 *    readable failure into a parse failure.
 * 2. A path that names a file — `/latest.json`, `/sitemap.xml`, `/undal.apk` —
 *    is never HTML either, whatever the `accept` header says, because a client
 *    asking for a document type has already told us what it can read.
 *
 * Both pages are `noindex`: a 404 in the index is a wasted crawl, and a 500 in
 * the index is a lie about a page that works most of the time.
 */
import { renderPage } from "./layout";
import { escapeHtml } from "../util/http";

/** Paths that belong to a machine, whatever an `accept` header claims. */
const MACHINE_PREFIXES = ["/api/", "/notify/", "/admin/", "/healthz"];

/** True when a browser — not the app, not a webhook — is asking for a document. */
export function wantsHtml(request: Request, path: string): boolean {
  for (const prefix of MACHINE_PREFIXES) {
    if (path === prefix.replace(/\/$/, "") || path.startsWith(prefix)) return false;
  }
  const lastSegment = path.slice(path.lastIndexOf("/") + 1);
  if (lastSegment.indexOf(".") >= 0) return false;
  const accept = request.headers.get("accept") ?? "";
  return accept.indexOf("text/html") >= 0 || accept.indexOf("*/*") >= 0;
}

/**
 * A 404 that is still a shop: the four places a lost customer actually wants,
 * and nothing that pretends to know which dish they were after.
 */
export function notFoundPage(path: string): Response {
  return renderPage({
    title: "Page not found · Undal HomeKitchen",
    description:
      "That link does not lead anywhere in the Undal store. Today's menu, the kitchen's hours, your basket and order tracking are one tap away.",
    status: 404,
    cacheSeconds: 0,
    robots: "noindex,follow",
    body: `<section class="card error-state reveal">
<p class="eyebrow">Error 404</p>
<h2>This table is not laid.</h2>
<p class="lead">Nothing in the Undal store lives at <strong>${escapeHtml(path)}</strong>. The link may be
older than the menu, or a character may have gone missing on the way here.</p>
<nav class="chips" aria-label="Where to go instead">
<a href="/store">Today's menu</a>
<a href="/about">Hours and delivery</a>
<a href="/basket">Your basket</a>
<a href="/track">Track an order</a>
</nav>
<div class="actions">
<a class="button" href="/store">Back to the menu</a>
<a class="button secondary" href="/download">Get the Android app</a>
</div>
<p class="note">Delivery is inside our radius in Sylhet and payment is <strong>cash on delivery only</strong>.
If one of our own messages sent you here, tell us and the link will be fixed.</p>
</section>`,
  });
}

/**
 * A 500 that says whose fault it is, what survived, and what to do next. The
 * basket lives in a cookie on the customer's phone, so a failed render has not
 * lost anybody's order — which is worth a sentence, because the alternative is
 * a customer re-choosing dinner from memory.
 */
export function failurePage(path?: string, status = 500): Response {
  const retry = path && path.startsWith("/") && path.indexOf(".") < 0 ? path : "/store";
  return renderPage({
    title: "Something went wrong · Undal HomeKitchen",
    description:
      "Undal could not build this page just now. Your basket is still saved on this phone — try the page again, or open the menu.",
    // The status the failure actually had. A menu that could not be read is a 503
    // — "the kitchen is unreachable, try again in a minute" — and rounding it up
    // to a 500 would tell a monitor that Undal itself is broken.
    status,
    cacheSeconds: 0,
    robots: "noindex,follow",
    body: `<section class="card error-state reveal">
<p class="eyebrow">Error 500</p>
<h2>The kitchen dropped a plate.</h2>
<p class="lead">Our side of this page failed a moment ago — the menu could not be read, or an
upstream request did not come back. This is not something you did.</p>
<ul class="plain-list">
<li>Your basket is a cookie on this phone, so nothing you chose has been lost.</li>
<li>No payment is involved anywhere in Undal: orders are cash on delivery.</li>
<li>Trying again usually works, because the failure is almost always a moment, not a fault.</li>
</ul>
<div class="actions">
<a class="button" href="${escapeHtml(retry)}">Try this page again</a>
<a class="button secondary" href="/store">Go to the menu</a>
</div>
<p class="note">If it keeps failing, the app reaches the kitchen by another road:
<a href="/download">install Undal for Android</a>.</p>
</section>`,
  });
}
