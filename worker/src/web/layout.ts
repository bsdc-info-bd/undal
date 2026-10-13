/**
 * Shared HTML shell for every page the Worker serves.
 * Inline CSS only: no external asset can fail on a Bangladeshi mobile network.
 */
import { escapeHtml, securityHeaders } from "../util/http";

export interface PageOptions {
  title: string;
  description: string;
  body: string;
  ogImage?: string;
  ogUrl?: string;
  cacheSeconds?: number;
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
footer{max-width:680px;margin:0 auto;padding:6px 18px 34px;color:var(--muted);font-size:.78rem}
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

  const document = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title>
<meta name="description" content="${description}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="Undal HomeKitchen">
<meta property="og:title" content="${title}">
<meta property="og:description" content="${description}">
${ogImage}
${ogUrl}
<meta name="twitter:card" content="summary_large_image">
<meta name="theme-color" content="#0B5A2B">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<style>${STYLE}</style>
</head>
<body>
<header>
${MARK}
<div>
<h1>Undal HomeKitchen</h1>
<p>Home-cooked food · Sylhet · cash on delivery</p>
</div>
</header>
<main>
${options.body}
</main>
<footer>
Undal HomeKitchen · Zindabazar, Sylhet · Delivery inside our radius only · Cash on delivery
</footer>
</body>
</html>`;

  return new Response(document, {
    status: 200,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": `public, max-age=${options.cacheSeconds ?? 60}`,
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
