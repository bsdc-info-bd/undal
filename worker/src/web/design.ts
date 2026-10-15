import { MAP_STYLE } from "./map-client";

/** Shared, dependency-free light theme. All pages inherit these responsive primitives. */
export const DESIGN = `
:root{color-scheme:light;--brand:#165b3b;--brand-dark:#10432c;--accent:#d97930;--paper:#f8f9f5;--ink:#20392c;--muted:#617065;--line:#e0e7df}
html{scroll-behavior:smooth;scroll-padding-top:90px}
body{font-size:16px;-webkit-font-smoothing:antialiased}
a{color:var(--brand);text-underline-offset:4px}
button,input,select,textarea{font:inherit}
button,a,input,select,textarea{-webkit-tap-highlight-color:transparent}
:focus-visible{outline:3px solid #d97930;outline-offset:4px}
.skip-link{position:absolute;top:-80px;left:16px;z-index:100;background:#fff;padding:12px 20px;border-radius:12px}
.skip-link:focus{top:12px}
header{background:#fff;color:var(--ink);padding:20px max(24px,calc((100vw - 1160px)/2));border-bottom:1px solid var(--line);gap:14px}
header svg{background:var(--brand);border-radius:15px;width:48px;height:48px;padding:3px}
header h1{font-size:1.15rem;letter-spacing:-.035em}
header p{color:var(--muted);opacity:1}
.brand-link{display:flex;align-items:center;gap:14px;color:inherit;text-decoration:none}
.header-action{margin-left:auto;text-decoration:none;font-size:.85rem;font-weight:700;padding:10px 16px;border:1px solid var(--line);border-radius:999px;white-space:nowrap}
main,footer{max-width:1208px;padding:28px 24px;margin-inline:auto}
main{min-height:60vh}
main>*{min-width:0}
main>section.card{max-width:780px;margin-inline:auto}
.card{padding:24px;border-radius:24px;box-shadow:0 8px 32px #20392c05;margin-bottom:20px}
h2{font-size:1.3rem;letter-spacing:-.025em;line-height:1.3}
p{overflow-wrap:anywhere}
nav.store{background:#ffffffed;backdrop-filter:blur(16px)}
nav.store .inner{max-width:1208px;padding:10px 24px;gap:8px;align-items:center}
nav.store a{padding:10px 16px;border-radius:999px;min-height:44px;display:flex;align-items:center}
nav.store a.active{background:#eaf2e9;color:var(--brand)}
nav.store a.cart{padding:10px 20px}
.button,a.button{display:flex;justify-content:center;align-items:center;gap:8px;min-height:48px;text-align:center;background:var(--brand);color:#fff;text-decoration:none;font-weight:700;padding:13px 20px;border:1px solid transparent;border-radius:14px;margin:10px 0 4px;cursor:pointer;transition:background .16s,transform .16s}
.button.secondary,a.button.secondary{background:#fff;color:var(--brand);border:1px solid var(--line)}
button:disabled{opacity:.55;cursor:not-allowed}
input,textarea,select{min-width:0;min-height:48px;border-radius:14px}
input:focus,textarea:focus,select:focus{border-color:var(--brand)}
input[type=checkbox],input[type=radio]{width:auto;min-height:0}
.search{background:#fff;padding:8px;border:1px solid var(--line);border-radius:20px;margin:24px 0 16px}
.search input{border:0;background:transparent;width:0}
.chips{gap:10px;margin-bottom:6px;scrollbar-width:thin}
.chips a{padding:10px 16px;min-height:44px;font-weight:600}
.grid{grid-template-columns:repeat(auto-fill,minmax(min(100%,250px),1fr));gap:22px;margin-top:24px}
.item{border-radius:24px;box-shadow:0 6px 24px #20392c05;transition:transform .2s,box-shadow .2s}
.item .body{padding:20px;gap:10px}
.item h3{font-size:1.08rem;letter-spacing:-.02em}
.item p{font-size:.88rem;line-height:1.65}
.item img{height:auto;aspect-ratio:4/3}
.price{font-size:1.15rem;color:var(--brand)}
.tag{padding:4px 9px;font-size:.72rem}
.food-placeholder{aspect-ratio:4/3;display:grid;place-content:center;text-align:center;background:radial-gradient(ellipse at center,#fff9e7,#ecf0e4);color:var(--brand);gap:8px}
.food-placeholder span{font-size:3rem}.food-placeholder small{font-weight:600;letter-spacing:.12em;text-transform:uppercase;font-size:.65rem}
.store-hero{display:grid;grid-template-columns:1.3fr 1fr;align-items:center;gap:40px;padding:clamp(24px,5vw,64px);background:#edf2e6;border:1px solid #e1e8d9;border-radius:32px;margin-bottom:24px;overflow:hidden;position:relative}
.eyebrow{display:block;font-size:.72rem;font-weight:800;letter-spacing:.17em;text-transform:uppercase;color:var(--brand);margin-bottom:16px}
.store-hero h2{font-size:clamp(2.1rem,4.5vw,3.8rem);letter-spacing:-.055em;line-height:1.08;margin:0 0 20px;max-width:650px}
.store-hero h2 em{font-style:normal;color:var(--brand)}
.store-hero p{font-size:1rem;color:var(--muted);max-width:440px;margin:0 0 24px}
.store-hero .button{display:inline-flex;margin:0}
.hero-note{background:#ffffffb8;border:1px solid #fff;border-radius:24px;padding:30px;transform:rotate(2deg);box-shadow:0 16px 40px #294c2410}
.hero-note .hero-mark{font-size:3.5rem;display:block;margin-bottom:12px}
.hero-note h3{font-size:1.5rem;letter-spacing:-.04em;margin:0 0 12px}
.hero-note p{font-size:.9rem;margin:0}.hero-note hr{border:0;border-top:1px solid var(--line);margin:22px 0}
.menu-heading{display:flex;align-items:end;justify-content:space-between;gap:16px;margin-top:32px}
.menu-heading h2{font-size:1.8rem;margin:0}.menu-heading p{margin:0}
.row{flex-wrap:wrap;padding:14px 0}.row>*{min-width:0}
.qty button{min-width:44px;min-height:44px;cursor:pointer}
.notice{padding:16px 20px;border-radius:16px;line-height:1.6}
.steps li{padding:12px 0;gap:14px}.steps .dot{width:14px;height:14px}
footer{border-top:1px solid var(--line);font-size:.8rem;line-height:1.8;margin-top:32px;padding-bottom:max(32px,env(safe-area-inset-bottom))}
@media(hover:hover){.item:hover{transform:translateY(-4px);box-shadow:0 14px 32px #20392c12}.button:hover{background:var(--brand-dark);color:#fff}nav.store a:hover{background:#eaf2e9;color:var(--brand)}}
@media(min-width:900px){main>section.card:only-of-type{margin-top:20px}img.hero{max-height:480px;object-fit:cover}}
@media(max-width:640px){header{padding:16px;gap:10px}header p{font-size:.7rem}header h1{font-size:1rem}.header-action{display:none}main,footer{padding:20px 16px}nav.store .inner{padding:8px 12px;gap:2px}nav.store a{padding:10px 12px;font-size:.8rem}nav.store a.cart{padding:10px 14px}.store-hero{grid-template-columns:1fr;border-radius:24px;gap:24px}.hero-note{transform:none;padding:20px}.hero-note .hero-mark{display:none}.hero-note hr{margin:14px 0}.hero-note h3{font-size:1.15rem}.card{padding:20px;border-radius:20px}.grid{gap:16px}.menu-heading{align-items:start;flex-direction:column;gap:6px}.search .button{padding-inline:14px!important}dl{grid-template-columns:1fr}dd{margin-bottom:8px}.qr svg{max-width:100%;height:auto}}
@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}*,*::before,*::after{animation:none!important;transition:none!important}}

/* --- footer navigation: the internal links a crawler follows ---------------- */
footer .footer-links{display:flex;flex-wrap:wrap;gap:6px 18px;margin:0 0 14px;font-size:.85rem}
footer .footer-links a{color:var(--ink);text-decoration:none;font-weight:600}
footer .footer-links a:hover{color:var(--brand)}

/* --- account: profile, addresses, history ---------------------------------- */
.account-grid{display:grid;gap:20px;grid-template-columns:repeat(auto-fit,minmax(min(100%,320px),1fr))}
.field-note{font-size:.8rem;color:var(--muted);margin:4px 2px 0;line-height:1.5}
.field-error{font-size:.8rem;color:#8c1d18;margin:4px 2px 0}
input[aria-invalid=true],textarea[aria-invalid=true]{border-color:#b3261e;background:#fffafa}
.saved-addresses{list-style:none;margin:0;padding:0;display:grid;gap:12px}
.saved-address{display:flex;gap:14px;align-items:flex-start;border:1px solid var(--line);border-radius:18px;padding:16px;background:#fffdf9}
.saved-address .marker{width:38px;height:38px;flex:none;border-radius:12px;background:#eaf2e9;color:var(--brand);display:grid;place-content:center;font-weight:800}
.saved-address h3{margin:0 0 4px;font-size:1rem}
.saved-address p{margin:0;font-size:.85rem;color:var(--muted)}
.saved-address .actions{margin-left:auto;display:flex;gap:8px;flex-wrap:wrap}
.mini-button{border:1px solid var(--line);background:#fff;color:var(--ink);border-radius:12px;padding:9px 14px;font-weight:700;font-size:.82rem;cursor:pointer;min-height:40px}
.mini-button.danger{color:#8c1d18;border-color:#f3c6c2}
.mini-button:disabled{opacity:.5;cursor:not-allowed}
.history-list{list-style:none;margin:0;padding:0;display:grid;gap:10px}
.history-row{display:flex;gap:12px;align-items:center;border-bottom:1px solid var(--line);padding:12px 2px}
.history-row:last-child{border-bottom:0}
.history-row .who{font-weight:700}
.history-row .when{font-size:.8rem;color:var(--muted)}
.history-row .amount{margin-left:auto;font-weight:700;color:var(--brand);white-space:nowrap}
.status-dot{width:10px;height:10px;border-radius:50%;flex:none;background:var(--brand)}
.status-dot.cancelled{background:#b3261e}
.status-dot.placed{background:#d97930}
.status-dot.in_delivery{background:#1d6fa5}
.tabs{display:flex;gap:8px;overflow-x:auto;margin:0 0 18px;scrollbar-width:thin}
.tabs a,.tabs button{white-space:nowrap;padding:10px 16px;border-radius:999px;border:1px solid var(--line);background:#fff;
color:var(--ink);font-weight:600;font-size:.85rem;text-decoration:none;min-height:44px;cursor:pointer}
.tabs a.active,.tabs button.active{background:#eaf2e9;border-color:#cfe0cd;color:var(--brand)}
.auth-split{display:grid;gap:22px;grid-template-columns:repeat(auto-fit,minmax(min(100%,320px),1fr))}
.password-meter{height:6px;border-radius:99px;background:var(--line);overflow:hidden;margin:6px 2px 0}
.password-meter span{display:block;height:100%;width:0;background:#b3261e;transition:width .2s,background .2s}
.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap;border:0}
/* --- the about page: hero, the order's journey, facts, FAQ, small print ----- */
section.hero{background:#edf2e6;border:1px solid #e1e8d9;border-radius:32px;padding:clamp(24px,5vw,52px);margin-bottom:24px;overflow:hidden}
section.hero h1{font-size:clamp(1.75rem,4vw,2.9rem);letter-spacing:-.045em;line-height:1.12;margin:0 0 16px;max-width:24ch}
section.hero .pill{margin-bottom:14px}
.lead{font-size:1.02rem;line-height:1.72;color:var(--muted);max-width:66ch;margin:0 0 12px}
.note{font-size:.9rem;line-height:1.6;color:var(--muted);margin:10px 0 0}
.actions{display:flex;flex-wrap:wrap;gap:12px;margin-top:20px}
.actions .button,.actions a.button{margin:0;flex:0 1 auto}
.button.ghost,a.button.ghost{background:transparent;color:var(--ink);border:1px solid var(--line)}
.steps h3{margin:0 0 5px;font-size:1.02rem;letter-spacing:-.01em}
.steps p{margin:0;font-size:.9rem;line-height:1.68;color:var(--muted)}
.step-number{flex:none;width:34px;height:34px;border-radius:12px;background:#eaf2e9;color:var(--brand);font-weight:800;display:grid;place-content:center;margin-top:2px}
.split{display:grid;gap:20px;grid-template-columns:repeat(auto-fit,minmax(min(100%,320px),1fr));margin-bottom:20px}
.split>.card{margin-bottom:0}
.facts{grid-template-columns:1fr;gap:0}
.facts>div{display:grid;grid-template-columns:minmax(128px,.38fr) 1fr;gap:4px 14px;padding:11px 0;border-bottom:1px solid var(--line)}
.facts>div:last-child{border-bottom:0}
.facts dt{font-size:.74rem;text-transform:uppercase;letter-spacing:.09em;color:var(--muted);font-weight:800}
.facts dd{margin:0;font-size:.93rem}
.faq-item{border-bottom:1px solid var(--line)}
.faq-item:last-child{border-bottom:0}
.faq-item summary{cursor:pointer;font-weight:700;padding:14px 2px;list-style:none;display:flex;gap:14px;align-items:center;min-height:44px;line-height:1.4}
.faq-item summary::-webkit-details-marker{display:none}
.faq-item summary::after{content:"+";margin-left:auto;flex:none;font-size:1.35rem;font-weight:400;color:var(--brand);line-height:1}
.faq-item[open] summary::after{content:"–"}
.faq-item[open] summary{color:var(--brand)}
.faq-item p{margin:0 0 16px;font-size:.92rem;line-height:1.72;color:var(--muted);max-width:70ch}
.plain-list{margin:0;padding-left:20px;display:grid;gap:11px;font-size:.93rem;line-height:1.68;color:var(--muted)}
.plain-list li::marker{color:var(--brand)}
@media(hover:hover){.faq-item summary:hover{color:var(--brand)}.button.ghost:hover,a.button.ghost:hover{background:#eaf2e9;color:var(--brand)}}
@media(max-width:640px){section.hero{border-radius:24px;padding:22px}.split{grid-template-columns:1fr;gap:0}.split>.card{margin-bottom:20px}.facts>div{grid-template-columns:1fr;gap:2px}.actions{gap:10px}.actions .button,.actions a.button{flex:1 1 100%}.step-number{width:30px;height:30px;border-radius:10px}}

/* --- the tracking page: the bill, printed in the app's own ink -------------- */
section.receipt{max-width:780px;margin:0 auto 20px;background:#14261b;color:#eef3ec;border-radius:26px;
padding:clamp(20px,4vw,34px);box-shadow:0 18px 44px #14261b26}
section.receipt .receipt-hero{text-align:center;margin:0 0 20px}
.receipt-tick{width:58px;height:58px;margin:0 auto 14px;border-radius:50%;background:#165b3b;color:#eaf7ef;
font-size:1.6rem;line-height:58px;box-shadow:0 0 0 10px #165b3b26}
.receipt-hero h2{margin:0 0 6px;color:#fff;font-size:clamp(1.25rem,3.4vw,1.7rem);letter-spacing:-.03em}
.receipt-hero p{margin:0;font-size:.9rem;color:#b9cbbd}
.receipt-hero .receipt-sent{margin-top:8px;font-size:.78rem;color:#93a897}
.receipt-rows{border-top:1px solid #ffffff1f;padding-top:6px}
.receipt-waiting{margin:14px 0;color:#b9cbbd;font-size:.9rem}
.receipt-row{display:flex;gap:14px;align-items:baseline;padding:9px 0;border-bottom:1px dashed #ffffff1f;font-size:.92rem}
.receipt-row .what{min-width:0;overflow-wrap:anywhere}
.receipt-row .what small{display:block;color:#93a897;font-size:.76rem;margin-top:2px}
.receipt-row .amount{margin-left:auto;white-space:nowrap;font-variant-numeric:tabular-nums;font-weight:600}
.receipt-row.muted{color:#b9cbbd}
.receipt-row.save .amount{color:#7fd39b}
.receipt-total{display:flex;gap:14px;align-items:baseline;justify-content:space-between;border-top:2px solid #ffffff3d;
margin-top:10px;padding-top:14px;font-weight:800;font-size:1.02rem;letter-spacing:-.01em}
.receipt-total .amount{font-size:1.35rem;font-variant-numeric:tabular-nums;white-space:nowrap}
.receipt-note{margin:14px 0 0;font-size:.78rem;line-height:1.6;color:#93a897;font-style:italic;text-align:center}
@media(max-width:640px){section.receipt{border-radius:20px;padding:20px 18px}.receipt-tick{width:50px;height:50px;
line-height:50px;font-size:1.4rem}.receipt-total{font-size:.95rem}.receipt-total .amount{font-size:1.15rem}}

${MAP_STYLE}`;
