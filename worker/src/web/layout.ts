/**
 * Shared HTML shell for every page the Worker serves.
 * Inline CSS only: no external asset can fail on a Bangladeshi mobile network.
 */
import { DESIGN } from "./design";
import { MOTION, THEME_BOOT_SCRIPT } from "./motion";
import { MOTION_SCRIPT, themeToggle } from "./motion-client";
import { escapeHtml, securityHeaders } from "../util/http";
import { DEFAULT_LOCALE, OG_LOCALE_ALTERNATE, SITE_NAME } from "./seo";

export interface PageOptions {
  title: string;
  description: string;
  body: string;
  ogImage?: string;
  ogUrl?: string;
  cacheSeconds?: number;
  status?: number;
  /** Extra navigational markup under the header (store pages). */
  nav?: string;
  /** Absolute URL of this page. One per page: the signal a crawler trusts most. */
  canonical?: string;
  /** `noindex,follow` for a page that is a key rather than a destination. */
  robots?: string;
  /** Serialised `<script type="application/ld+json">` blocks for the head. */
  jsonLd?: string;
}

const STYLE = `
:root{color-scheme:light dark;--brand:#0B5A2B;--brand-dark:#073D1D;--accent:#E8871E;
--paper:#FFFBF4;--ink:#14261B;--muted:#5b6b60;--line:#e3e7e0}
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:var(--paper);color:var(--ink);line-height:1.55}
header{background:var(--brand);color:#fff;padding:20px 18px;display:flex;align-items:center;gap:12px}
header svg{width:40px;height:40px;flex:none}
header h1{font-size:1.15rem;margin:0;font-weight:650}
header p{margin:2px 0 0;font-size:.8rem;opacity:.85}
main{max-width:680px;margin:0 auto;padding:18px}
.card{background:#fff;border:1px solid var(--line);border-radius:14px;padding:16px;margin:0 0 14px;box-shadow:0 1px 2px rgba(0,0,0,.04)}
.pill{display:inline-block;padding:4px 10px;border-radius:999px;font-size:.75rem;font-weight:700;letter-spacing:.02em}
.pill.open{background:#e4f3e7;color:#0b5a2b}
.pill.closed{background:#fdecec;color:#b3261e}
h2{font-size:1rem;margin:0 0 8px}
dl{margin:0;display:grid;grid-template-columns:auto 1fr;gap:6px 14px;font-size:.92rem}
dt{color:var(--muted)}
dd{margin:0;font-weight:600}
a.button{display:block;text-align:center;background:var(--brand);color:#fff;text-decoration:none;font-weight:700;padding:14px;border-radius:12px;margin:10px 0 4px}
a.button.secondary{background:#fff;color:var(--brand);border:1.5px solid var(--brand)}
img.hero{width:100%;border-radius:12px;display:block;margin-bottom:12px}
.muted{color:var(--muted);font-size:.85rem}
nav.store{background:#fff;border-bottom:1px solid var(--line);position:sticky;top:0;z-index:5}
nav.store .inner{max-width:680px;margin:0 auto;display:flex;gap:14px;padding:10px 18px;font-size:.85rem;overflow-x:auto}
nav.store a{color:var(--ink);text-decoration:none;font-weight:600;white-space:nowrap}
nav.store a.active{color:var(--brand)}
nav.store a.cart{margin-left:auto;background:var(--brand);color:#fff;padding:5px 12px;border-radius:999px}
footer{max-width:680px;margin:0 auto;padding:6px 18px 34px;color:var(--muted);font-size:.78rem}
.grid{display:grid;gap:12px;grid-template-columns:repeat(auto-fill,minmax(210px,1fr))}
.item{display:flex;flex-direction:column;background:#fff;border:1px solid var(--line);border-radius:14px;overflow:hidden;text-decoration:none;color:inherit}
.item img{width:100%;aspect-ratio:4/3;object-fit:cover;background:#f2f4f0}
.item .body{padding:10px 12px;display:flex;flex-direction:column;gap:4px;flex:1}
.item h3{margin:0;font-size:.95rem}
.item p{margin:0;font-size:.8rem;color:var(--muted)}
.price{font-weight:700;font-size:.95rem}
.was{color:var(--muted);text-decoration:line-through;font-size:.8rem;font-weight:500;margin-left:6px}
.tag{display:inline-block;font-size:.68rem;font-weight:700;padding:2px 7px;border-radius:999px;background:#e4f3e7;color:#0b5a2b}
.tag.off{background:#fdecec;color:#b3261e}
.tag.warn{background:#fff4dc;color:#9a6700}
.chips{display:flex;gap:8px;overflow-x:auto;padding:2px 0 10px}
.chips a{padding:6px 12px;border-radius:999px;border:1px solid var(--line);background:#fff;text-decoration:none;color:var(--ink);font-size:.82rem;white-space:nowrap}
.chips a.active{background:var(--brand);border-color:var(--brand);color:#fff}
.search{display:flex;gap:8px;margin:0 0 12px}
.search input{flex:1;padding:12px;border-radius:12px;border:1px solid var(--line);font-size:1rem;background:#fff;color:var(--ink)}
input,textarea,select{width:100%;padding:12px;border-radius:12px;border:1px solid var(--line);font-size:1rem;background:#fff;color:var(--ink);font-family:inherit}
label{display:block;font-size:.82rem;font-weight:600;margin:10px 0 4px}
.row{display:flex;justify-content:space-between;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid var(--line)}
.row:last-child{border-bottom:none}
.total{font-size:1.15rem;font-weight:700}
.steps{list-style:none;padding:0;margin:0}
.steps li{display:flex;gap:10px;align-items:flex-start;padding:6px 0;font-size:.9rem}
.steps .dot{width:12px;height:12px;border-radius:50%;border:2px solid var(--line);margin-top:4px;flex:none}
.steps li.done .dot{background:var(--brand);border-color:var(--brand)}
.steps li.now{font-weight:700}
.steps li.now .dot{border-color:var(--accent);background:var(--accent)}
.qty{display:inline-flex;align-items:center;gap:8px}
.qty button{width:34px;height:34px;border-radius:10px;border:1px solid var(--line);background:#fff;font-size:1.1rem;font-weight:700;color:var(--ink)}
.notice{background:#fff4dc;border:1px solid #f0d9a8;color:#5c3d00;padding:12px;border-radius:12px;font-size:.88rem;margin:0 0 12px}
.notice.error{background:#fdecec;border-color:#f3c6c2;color:#8c1d18}
.notice.ok{background:#e7f5ec;border-color:#bfe4cb;color:#0a3d20}
.qr{background:#fff;padding:12px;border:1px solid var(--line);border-radius:14px;display:inline-block}
.qr svg{display:block;width:220px;height:220px}
.smallprint{font-size:.78rem;color:var(--muted)}
@keyframes undalPulse{0%,100%{opacity:1}50%{opacity:.45}}
.pulse{animation:undalPulse 1.4s ease-in-out infinite}
`;

const MARK_PATHS = `<path fill="#FFFDF7" d="M24,54 L84,54 C84,76 71,88 54,88 C37,88 24,76 24,54 Z"/>
<path fill="#E8871E" d="M29,50 C31,33 41,25 54,25 C67,25 77,33 79,50 Z"/>
<path fill="#FFFDF7" d="M50,17 L58,17 L58,24 L50,24 Z"/>
<path fill="#FFFDF7" d="M43,4 L47,4 L47,13 L43,13 Z"/>
<path fill="#FFFDF7" d="M53,8 L57,8 L57,14 L53,14 Z"/>`;

const MARK = `<svg viewBox="0 0 108 108" role="img" aria-label="Undal HomeKitchen">${MARK_PATHS}</svg>`;

export function renderPage(options: PageOptions): Response {
  const title = escapeHtml(options.title);
  const description = escapeHtml(options.description);
  const ogImage = options.ogImage ? `<meta property="og:image" content="${escapeHtml(options.ogImage)}">` : "";
  const ogUrl = options.ogUrl ? `<meta property="og:url" content="${escapeHtml(options.ogUrl)}">` : "";
  const canonical = options.canonical ? `<link rel="canonical" href="${escapeHtml(options.canonical)}">` : "";
  const robots = options.robots ? `<meta name="robots" content="${escapeHtml(options.robots)}">` : "";

  const document = `<!doctype html>
<html lang="en-GB">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>${title}</title>
<meta name="description" content="${description}">
${canonical}
${robots}
<meta name="theme-color" content="#165b3b">
<meta name="theme-color" media="(prefers-color-scheme: dark)" content="#0e1512">
<meta name="color-scheme" content="light dark">
<meta name="format-detection" content="telephone=no">
<meta property="og:type" content="website">
<meta property="og:site_name" content="${SITE_NAME}">
<meta property="og:locale" content="${DEFAULT_LOCALE}">
<meta property="og:locale:alternate" content="${OG_LOCALE_ALTERNATE}">
<meta property="og:title" content="${title}">
<meta property="og:description" content="${description}">
${ogImage}
${ogUrl}
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="${title}">
<meta name="twitter:description" content="${description}">
${options.ogImage ? `<meta name="twitter:image" content="${escapeHtml(options.ogImage)}">` : ""}
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="preconnect" href="https://tile.openstreetmap.org" crossorigin>
<link rel="preconnect" href="https://res.cloudinary.com" crossorigin>
<link rel="preconnect" href="https://sylhetbites.pages.dev" crossorigin>
<link rel="dns-prefetch" href="https://sylhetbites.pages.dev">
${options.jsonLd ?? ""}
<script>${THEME_BOOT_SCRIPT}</script>
<style>${STYLE}
${DESIGN}
${MOTION}</style>
</head>
<body>
<script>${MOTION_SCRIPT}</script>
<a class="skip-link" href="#main-content">Skip to content</a>
<header>
<a class="brand-link" href="/store">${MARK}
<div>
<h1>${SITE_NAME}</h1>
<p>Home-cooked food · Sylhet · cash on delivery</p>
</div></a>
<div class="header-tools">
<a class="header-action" href="/download">Get the Android app ↗</a>
${themeToggle()}
</div>
</header>
${options.nav ?? ""}
<main id="main-content">
${options.body}
</main>
<footer>
<nav class="footer-links" aria-label="More about Undal">
<a href="/store">Menu</a>
<a href="/about">The kitchen, hours and delivery</a>
<a href="/basket">Your basket</a>
<a href="/track">Track an order</a>
<a href="/account">Your account</a>
<a href="/login">Sign in</a>
<a href="/download">Install the app</a>
<a href="/app">Open in the app</a>
</nav>
Undal HomeKitchen · Sylhet · Delivery inside our radius only · <strong>Cash on delivery only</strong> —
no card, no wallet, no online payment anywhere in this app.<br>
Ordering from a phone browser works; live status pushes, in-order chat with the kitchen and the
Undal order sound are app-only. <a href="/download">Install the app</a> · <a href="/app">Open Undal</a>
<p class="smallprint">Map data © OpenStreetMap contributors, used to place your delivery pin.</p>
</footer>
</body>
</html>`;

  return new Response(document, {
    status: options.status ?? 200,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": options.cacheSeconds === 0 ? "no-store" : `public, max-age=${options.cacheSeconds ?? 60}`,
      ...securityHeaders(),
    },
  });
}

export function favicon(): Response {
  const icon = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" rx="20" fill="#0B5A2B"/>${MARK_PATHS}</svg>`;
  return new Response(icon, {
    headers: { "content-type": "image/svg+xml", "cache-control": "public, max-age=86400" },
  });
}
