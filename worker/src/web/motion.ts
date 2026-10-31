/**
 * Motion and theme: the layer that makes the storefront feel like a product.
 *
 * Everything in `layout.ts` and `design.ts` is structure and light-mode colour.
 * This file adds the three things a modern shop is judged on in the first two
 * seconds, without adding a byte that a slow connection has to wait for:
 *
 *   · **A theme.** One token set, two palettes. Warm paper and kitchen green by
 *     day; the same identity on a deep charcoal-green at night, following the
 *     system preference until the customer says otherwise. Dark mode is written
 *     as an *additive* override: not one light-mode declaration in this
 *     repository changes value, so a phone that has never heard of
 *     `prefers-color-scheme` renders exactly what it rendered yesterday.
 *   · **Motion with a purpose.** Cards rise as they scroll into view, an add to
 *     the basket flies to the basket, a pressed button gives way under the thumb,
 *     the reading position is a line at the top of the screen. Every one of them
 *     answers a question — "did that work?", "where am I?", "what is new?" — and
 *     every one of them stops when the customer has asked for reduced motion.
 *   · **Fluid responsiveness.** Type, gutters and radii scale with the viewport
 *     instead of stepping at three breakpoints, the sticky bars respect the
 *     iPhone home indicator, and nothing anywhere is allowed to overflow
 *     sideways at 320 CSS pixels.
 *
 * The browser half of this lives in `motion-client.ts`, written in the oldest
 * JavaScript dialect the storefront supports; the CSS here is plain, prefixed
 * nowhere and feature-tested by its own absence — an unsupported declaration is
 * dropped by the browser and the page carries on without it.
 */

/** The palette, written once and used for both the explicit and the system choice. */
const DARK_TOKENS = `
--paper:#0e1512;--paper-2:#121a16;--surface:#161f1a;--surface-2:#1c2621;--surface-3:#222d27;
--ink:#eef4ef;--ink-2:#d6e0d8;--muted:#9fb0a5;--line:#2b3730;--line-2:#3a483f;
--brand:#4cbb7d;--brand-dark:#3ea268;--brand-ink:#07130c;--brand-soft:#1c3a2b;--brand-soft-line:#2c5540;
--accent:#f0a25c;--accent-ink:#f7c795;--accent-soft:#33261a;
--danger:#e8837a;--danger-soft:#3a211e;--warn:#e3b660;--warn-soft:#33291a;--ok:#6cc492;--ok-soft:#1b3327;
--shadow-soft:0 14px 40px #00000040;--shadow-lift:0 22px 54px #0000004d;--shadow-inset:inset 0 1px 0 #ffffff0a;
--glass:#161f1acc;--hero-a:#16241c;--hero-b:#101a15;--hero-glow:#2f6b4a33;
--receipt-bg:#0a110d;--receipt-ink:#e7efe8;--receipt-muted:#93a897;--receipt-line:#ffffff1f;
--skeleton-a:#1c2621;--skeleton-b:#26322b;--control:#8b9a90;
`;

const LIGHT_TOKENS = `
--paper:#faf8f2;--paper-2:#f4f1e8;--surface:#ffffff;--surface-2:#fffdf8;--surface-3:#f1f4e9;
--ink:#14261b;--ink-2:#2c4133;--muted:#5b6b60;--line:#e2e5da;--line-2:#cfd6c9;
--brand:#165b3b;--brand-dark:#10432c;--brand-ink:#ffffff;--brand-soft:#eaf2e9;--brand-soft-line:#cfe0cd;
--accent:#d97930;--accent-ink:#97510b;--accent-soft:#fff4dc;
--danger:#b3261e;--danger-soft:#fdecec;--warn:#9a6700;--warn-soft:#fff4dc;--ok:#0a3d20;--ok-soft:#e7f5ec;
--shadow-soft:0 12px 36px #20392c0f;--shadow-lift:0 22px 48px #20392c1f;--shadow-inset:inset 0 1px 0 #ffffffcc;
--glass:#ffffffd9;--hero-a:#f0f3e8;--hero-b:#e6eddb;--hero-glow:#dbe7cf;
--receipt-bg:#14261b;--receipt-ink:#eef3ec;--receipt-muted:#93a897;--receipt-line:#ffffff1f;
--skeleton-a:#eef1e8;--skeleton-b:#e2e7da;--control:#737d72;
`;

/** The shared motion vocabulary. Durations a person can feel, not a stopwatch. */
const MOTION_TOKENS = `
--ease-out:cubic-bezier(.22,.61,.36,1);--ease-spring:cubic-bezier(.34,1.56,.64,1);--ease-in-out:cubic-bezier(.65,0,.35,1);
--fast:.16s;--quick:.24s;--slow:.42s;--glacial:.9s;
--radius-sm:12px;--radius:18px;--radius-lg:26px;--radius-xl:34px;
--gutter:clamp(16px,4.2vw,40px);--measure:70ch;
`;

export const MOTION = `
/* === tokens ================================================================= */
:root{color-scheme:light dark;${LIGHT_TOKENS}${MOTION_TOKENS}}
:root[data-undal-theme="dark"]{${DARK_TOKENS}}
@media(prefers-color-scheme:dark){:root:not([data-undal-theme="light"]){${DARK_TOKENS}}}
@media(prefers-contrast:more){
 :root{--line:#4a554c;--muted:#3f4a42;--shadow-soft:none}
 :root[data-undal-theme="dark"]{--line:#c6d2c8;--muted:#dbe5dc}
}

/* === the theme-aware pass =================================================== */
/* Every surface the light theme paints white is painted from a token here, so the
   dark palette has somewhere to live. The light values are the values the light
   theme already had — this changes the night, not the day. */
body{background:var(--paper);color:var(--ink);
 transition:background-color var(--slow) var(--ease-out),color var(--slow) var(--ease-out)}
header{background:var(--surface-2);color:var(--ink);border-bottom-color:var(--line)}
.card,.item,.search,.mini-button,.qr,.saved-address,.coupon-banner,.checkout-summary,
.tabs a,.tabs button,.faq-item,.history-row,input,textarea,select,.notice{
 background-color:var(--surface);border-color:var(--line);color:var(--ink)}
input,textarea,select{background-color:var(--surface-2)}
.card,.item{box-shadow:var(--shadow-soft)}
nav.store{background:var(--glass);border-bottom:1px solid var(--line)}
nav.store a{color:var(--ink)}
nav.store a.active{background:var(--brand-soft);color:var(--brand)}
a{color:var(--brand)}
.muted,p.muted,.smallprint,.field-note,dt,.item p,.hero-note p,footer{color:var(--muted)}
.button,a.button{background:var(--brand);color:var(--brand-ink);border-color:transparent}
.button.secondary,a.button.secondary{background:var(--surface);color:var(--brand);border-color:var(--line-2)}
.button.ghost,a.button.ghost{background:transparent;color:var(--brand);border-color:var(--line-2)}
.chips a{background:var(--surface);border-color:var(--line);color:var(--ink)}
.chips a.active{background:var(--brand);border-color:var(--brand);color:var(--brand-ink)}
.tag{background:var(--brand-soft);color:var(--brand)}
.tag.off{background:var(--danger-soft);color:var(--danger)}
.tag.warn{background:var(--warn-soft);color:var(--warn)}
.notice{background:var(--warn-soft);border-color:var(--line);color:var(--ink)}
.notice.error{background:var(--danger-soft);color:var(--ink);border-left-color:var(--danger)}
.notice.ok{background:var(--ok-soft);color:var(--ink);border-left-color:var(--ok)}
.pill.open{background:var(--brand-soft);color:var(--brand)}
.pill.closed{background:var(--danger-soft);color:var(--danger)}
.field-error{color:var(--danger)}
input[aria-invalid=true],textarea[aria-invalid=true]{border-color:var(--danger);background:var(--danger-soft)}
.saved-address{background:var(--surface-2)}
.saved-address .marker{background:var(--brand-soft);color:var(--brand)}
.mini-button.danger{color:var(--danger);border-color:var(--danger)}
.checkout-summary{background:var(--surface-3)}
.coupon-banner{background:var(--accent-soft);border-color:var(--line)}
.coupon-banner .eyebrow{color:var(--accent-ink)}
.coupon-code{background:var(--surface);border-color:var(--line-2);color:var(--ink)}
.store-hero{background:
  radial-gradient(120% 120% at 92% 4%,var(--hero-glow) 0,transparent 62%),
  linear-gradient(125deg,var(--hero-a),var(--hero-b));
 border-color:var(--line);box-shadow:var(--shadow-inset)}
.store-hero p{color:var(--muted)}
.hero-note{background:var(--surface-2);border-color:var(--line);box-shadow:var(--shadow-lift)}
.hero-note h3,.hero-note p{color:inherit}
.hero-note p{color:var(--muted)}
.hero-note hr{border-top-color:var(--line)}
.food-art{background:radial-gradient(ellipse at center,var(--surface-2),var(--paper-2))}
.food-placeholder{background:radial-gradient(ellipse at center,var(--surface-2),var(--paper-2));color:var(--brand)}
.item-photo{background:var(--surface-3)}
.item .body,.item .card-tail{background:var(--surface)}
.price,.amount,.total{color:var(--brand)}
.was{color:var(--muted)}
section.receipt{background:var(--receipt-bg);color:var(--receipt-ink);box-shadow:var(--shadow-lift)}
.receipt-row,.receipt-rows{border-color:var(--receipt-line)}
.receipt-row.muted,.receipt-hero p,.receipt-note,.receipt-hero .receipt-sent{color:var(--receipt-muted)}
.receipt-row .what small{color:var(--receipt-muted)}
.receipt-hero h2{color:#fff}
.receipt-total{border-top-color:#ffffff3d}
footer{border-top-color:var(--line)}
footer .footer-links a{color:var(--ink)}
.steps .dot{border-color:var(--line-2)}
.steps li.done .dot{background:var(--brand);border-color:var(--brand)}
.steps li.now .dot{background:var(--accent);border-color:var(--accent)}
.status-dot{background:var(--brand)}
.status-dot.cancelled{background:var(--danger)}
.status-dot.placed{background:var(--accent)}
.status-dot.in_delivery{background:#5aa2d8}
.password-meter{background:var(--line)}
.skip-link{background:var(--surface);color:var(--ink);box-shadow:var(--shadow-lift)}
.tabs a.active,.tabs button.active{background:var(--brand-soft);border-color:var(--brand-soft-line);color:var(--brand)}
.header-action{border-color:var(--line);color:var(--ink);background:var(--surface)}
input::placeholder,textarea::placeholder{color:var(--muted)}
.faq-item summary{color:var(--ink)}
.faq-item summary::after{color:var(--brand)}
.faq-item[open] summary{color:var(--brand)}
.plain-list,.faq-item p{color:var(--muted)}
.plain-list li::marker{color:var(--brand)}
:root[data-undal-theme="dark"] .food-art>img,:root[data-undal-theme="dark"] .item-photo img{filter:saturate(1.03) brightness(.95)}
:root[data-undal-theme="dark"] header svg{background:var(--brand);box-shadow:0 0 0 1px #ffffff1f}
:root[data-undal-theme="dark"] .qr{background:#fff}
:root[data-undal-theme="dark"] .qr svg{filter:none}

/* === the reading position =================================================== */
.undal-progress{position:fixed;top:0;right:0;left:0;height:3px;z-index:60;pointer-events:none;
 background:transparent;opacity:0;transition:opacity var(--quick) var(--ease-out)}
.undal-progress.is-on{opacity:1}
.undal-progress span{display:block;height:100%;width:0;
 background:linear-gradient(90deg,var(--brand),var(--accent));
 box-shadow:0 0 12px var(--hero-glow);transition:width .1s linear}

/* === a header that knows it is sticky ======================================= */
header{position:relative;z-index:40;transition:box-shadow var(--quick) var(--ease-out)}
nav.store{position:sticky;top:0;z-index:35;
 transition:box-shadow var(--quick) var(--ease-out),border-color var(--quick) var(--ease-out),
  background-color var(--slow) var(--ease-out)}
@supports(backdrop-filter:blur(1px)) or (-webkit-backdrop-filter:blur(1px)){
 nav.store{backdrop-filter:saturate(1.4) blur(14px);-webkit-backdrop-filter:saturate(1.4) blur(14px)}
}
nav.store.is-stuck{box-shadow:0 10px 30px #20392c1f;border-bottom-color:var(--line-2)}
:root[data-undal-theme="dark"] nav.store.is-stuck{box-shadow:0 10px 30px #00000059}

.header-tools{margin-left:auto;display:flex;align-items:center;gap:8px;flex:none}
.header-tools .header-action{margin-left:0}
@media(max-width:640px){.header-tools{gap:4px}.undal-theme .undal-theme-label{display:none}.undal-theme{min-width:44px;padding:8px}}

/* the theme switch, in the header where a hand expects a control */
.undal-theme{margin-left:8px;display:inline-flex;align-items:center;justify-content:center;gap:8px;
 min-width:44px;min-height:44px;padding:8px 12px;border-radius:999px;cursor:pointer;
 background:var(--surface);border:1px solid var(--line);color:var(--ink);font-weight:700;font-size:.82rem;
 transition:transform var(--fast) var(--ease-spring),background-color var(--quick),border-color var(--quick)}
.undal-theme:hover{border-color:var(--brand);color:var(--brand)}
.undal-theme:active{transform:scale(.94)}
.undal-theme .undal-theme-icon{display:inline-block;transition:transform var(--slow) var(--ease-spring)}
.undal-theme.is-spinning .undal-theme-icon{transform:rotate(180deg) scale(1.12)}

/* === scroll reveals ========================================================= */
/* The card is on the page whether or not the script runs. '.reveal' only hides
   what JavaScript has promised to show, so a browser without IntersectionObserver
   — or with the script switched off — never loses a dish. */
.js-reveal .reveal{opacity:0;transform:translateY(14px) scale(.985);
 transition:opacity var(--slow) var(--ease-out),transform var(--slow) var(--ease-out);
 transition-delay:calc(var(--i,0) * 45ms);will-change:opacity,transform}
.js-reveal .reveal.is-visible{opacity:1;transform:none}
@media(hover:hover){
 .item{transition:transform var(--quick) var(--ease-spring),box-shadow var(--quick) var(--ease-out),
  border-color var(--quick)}
 .item:hover{transform:translateY(-6px);box-shadow:var(--shadow-lift);border-color:var(--brand-soft-line)}
 .item:hover .food-art>img,.item:hover .item-photo img{transform:scale(1.06)}
 .food-art>img,.item-photo img{transition:transform var(--slow) var(--ease-out)}
 .button:hover,a.button:hover{transform:translateY(-1px);box-shadow:0 10px 22px #165b3b26}
 .chips a:hover{transform:translateY(-1px)}
 .undal-theme:hover,.mini-button:hover,.tabs a:hover,.tabs button:hover{transform:translateY(-1px)}
}

/* === the picture, while it travels ========================================== */
/* A dish photo on a Bangladeshi mobile network arrives late. The space it will
   occupy shimmers instead of sitting empty, and the artwork underneath is what
   shows if it never arrives at all. */
.food-art{position:relative;overflow:hidden}
.food-art::before{content:"";position:absolute;top:0;right:0;bottom:0;left:0;z-index:0;
 background:linear-gradient(100deg,var(--skeleton-a) 20%,var(--skeleton-b) 40%,var(--skeleton-a) 60%);
 background-size:220% 100%;animation:undal-shimmer 1.5s linear infinite}
.food-art>img,.food-art>.food-placeholder{position:relative;z-index:1}
.food-art.is-painted::before,.food-art.no-photo::before{animation:none;opacity:0}
@keyframes undal-shimmer{from{background-position:120% 0}to{background-position:-120% 0}}

/* === press feedback ========================================================= */
.button,a.button,.mini-button,.chips a,.tabs a,.tabs button,.qty button,.undal-theme{position:relative;overflow:hidden}
.button:active,a.button:active{transform:scale(.985)}
.qty button:active{transform:scale(.9)}
.undal-ripple{position:absolute;border-radius:50%;pointer-events:none;transform:scale(0);
 background:currentColor;opacity:.24;animation:undal-ripple .6s var(--ease-out) forwards}
@keyframes undal-ripple{to{transform:scale(2.6);opacity:0}}

/* === toasts ================================================================= */
.undal-toasts{position:fixed;left:0;right:0;bottom:0;z-index:80;display:flex;flex-direction:column;
 align-items:center;gap:10px;padding:16px var(--gutter) calc(16px + env(safe-area-inset-bottom));
 pointer-events:none}
.undal-toast{pointer-events:auto;max-width:min(560px,100%);display:flex;align-items:center;gap:12px;
 padding:13px 18px;border-radius:16px;background:var(--surface);color:var(--ink);
 border:1px solid var(--line);box-shadow:var(--shadow-lift);font-size:.92rem;font-weight:600;
 transform:translateY(140%);opacity:0;transition:transform var(--slow) var(--ease-spring),opacity var(--quick)}
.undal-toast.is-shown{transform:none;opacity:1}
.undal-toast .undal-toast-mark{flex:none;width:26px;height:26px;border-radius:50%;display:grid;place-content:center;
 background:var(--brand-soft);color:var(--brand);font-size:.9rem}
.undal-toast.is-error .undal-toast-mark{background:var(--danger-soft);color:var(--danger)}
.undal-toast button{flex:none;background:transparent;border:0;color:var(--muted);font-size:1.1rem;
 cursor:pointer;min-width:32px;min-height:32px;border-radius:10px}
.undal-toast button:hover{color:var(--ink);background:var(--surface-3)}

/* === the basket, always in reach ============================================ */
.undal-basketbar{position:fixed;left:0;right:0;bottom:0;z-index:55;display:none;
 padding:10px var(--gutter) calc(10px + env(safe-area-inset-bottom));
 background:var(--glass);border-top:1px solid var(--line);
 transform:translateY(120%);transition:transform var(--slow) var(--ease-spring)}
@supports(backdrop-filter:blur(1px)) or (-webkit-backdrop-filter:blur(1px)){
 .undal-basketbar{backdrop-filter:saturate(1.5) blur(16px);-webkit-backdrop-filter:saturate(1.5) blur(16px)}
}
.undal-basketbar.is-shown{display:block;transform:none}
.undal-basketbar a{display:flex;align-items:center;gap:12px;text-decoration:none;color:var(--brand-ink);
 background:var(--brand);border-radius:16px;padding:13px 18px;font-weight:800;min-height:52px;
 box-shadow:0 12px 28px #165b3b33}
.undal-basketbar .undal-basketbar-count{display:inline-grid;place-content:center;min-width:28px;height:28px;
 padding:0 8px;border-radius:999px;background:var(--brand-ink);color:var(--brand);font-size:.85rem}
.undal-basketbar .undal-basketbar-total{margin-left:auto;font-variant-numeric:tabular-nums}
.undal-basketbar.is-bumped a{animation:undal-basket-bump .45s var(--ease-spring)}
@keyframes undal-basket-bump{0%{transform:scale(1)}40%{transform:scale(1.045)}100%{transform:scale(1)}}
@media(max-width:40rem){.undal-basketbar.is-shown{display:block}main{padding-bottom:96px}}

/* a badge that jumps when the basket changes, on the nav and on the bar */
#undal-cart-count.is-bumped{animation:undal-badge-bump .5s var(--ease-spring)}
@keyframes undal-badge-bump{0%{transform:scale(1)}35%{transform:scale(1.4)}70%{transform:scale(.92)}100%{transform:scale(1)}}
nav.store a.cart{transition:transform var(--fast) var(--ease-spring),background-color var(--quick)}
nav.store a.cart.is-bumped{transform:scale(1.06)}

/* === the flight of a dish into the basket =================================== */
.undal-flight{position:fixed;z-index:90;width:16px;height:16px;border-radius:50%;pointer-events:none;
 background:var(--accent);box-shadow:0 0 0 4px var(--accent-soft),0 8px 18px #00000029;
 transition:transform .62s var(--ease-in-out),opacity .62s var(--ease-out)}

/* === the hero, alive but not busy =========================================== */
.store-hero{isolation:isolate}
.store-hero::after{content:"";position:absolute;top:-20%;right:-10%;bottom:auto;left:auto;width:min(58vw,520px);
 aspect-ratio:1;height:min(58vw,520px);border-radius:50%;z-index:-1;filter:blur(6px);
 background:radial-gradient(circle at 40% 40%,var(--hero-glow),transparent 68%);
 animation:undal-drift 16s var(--ease-in-out) infinite alternate}
@keyframes undal-drift{from{transform:translate3d(0,0,0) scale(1)}to{transform:translate3d(-5%,6%,0) scale(1.09)}}
.hero-note{transition:transform var(--slow) var(--ease-spring),box-shadow var(--slow)}
@media(hover:hover){.hero-note:hover{transform:rotate(0deg) translateY(-4px);box-shadow:var(--shadow-lift)}}
.hero-mark{display:inline-block;transform-origin:50% 80%;animation:undal-steam 4.6s var(--ease-in-out) infinite}
@keyframes undal-steam{0%,100%{transform:translateY(0) rotate(-2deg)}50%{transform:translateY(-7px) rotate(3deg)}}
.store-hero h2{animation:undal-rise var(--slow) var(--ease-out) both}
.store-hero .eyebrow{animation:undal-rise var(--slow) var(--ease-out) .05s both}
.store-hero p{animation:undal-rise var(--slow) var(--ease-out) .1s both}
.store-hero .button{animation:undal-rise var(--slow) var(--ease-out) .16s both}
.hero-note{animation:undal-rise var(--slow) var(--ease-out) .22s both}
@keyframes undal-rise{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:none}}

/* the honest facts about this kitchen, in a row a thumb can read */
.undal-facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,9.5rem),1fr));
 gap:10px;margin:0 0 22px;padding:0}
.undal-facts div{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);
 padding:13px 15px;box-shadow:var(--shadow-soft);transition:transform var(--quick) var(--ease-spring),border-color var(--quick)}
.undal-facts dt{font-size:.7rem;font-weight:800;letter-spacing:.13em;text-transform:uppercase;color:var(--muted);margin:0 0 4px}
.undal-facts dd{margin:0;font-size:.95rem;font-weight:700;color:var(--ink);line-height:1.35}
@media(hover:hover){.undal-facts div:hover{transform:translateY(-3px);border-color:var(--brand-soft-line)}}

/* === search, chips and the menu heading ===================================== */
.search{transition:box-shadow var(--quick) var(--ease-out),border-color var(--quick)}
.search:focus-within{border-color:var(--brand);box-shadow:0 0 0 4px var(--brand-soft)}
.search input{transition:transform var(--fast)}
.chips{scroll-snap-type:x proximity;-webkit-overflow-scrolling:touch}
.chips a{scroll-snap-align:start;transition:transform var(--fast) var(--ease-spring),background-color var(--quick),
 color var(--quick),border-color var(--quick)}
.menu-heading h2{text-wrap:balance}
h1,h2,h3{text-wrap:balance}
p{text-wrap:pretty}

/* === the order ladder, moving =============================================== */
.steps li{transition:opacity var(--quick)}
.steps .dot{transition:background-color var(--slow) var(--ease-out),border-color var(--slow),
 transform var(--quick) var(--ease-spring),box-shadow var(--slow)}
.steps li.done .dot{transform:scale(1.06)}
.steps li.now .dot{box-shadow:0 0 0 0 var(--accent);animation:undal-halo 2.4s var(--ease-out) infinite}
@keyframes undal-halo{0%{box-shadow:0 0 0 0 #d9793066}70%{box-shadow:0 0 0 12px #d9793000}100%{box-shadow:0 0 0 0 #d9793000}}
.steps li.now{position:relative}
.steps li.now::before{content:"";position:absolute;left:-14px;top:8px;bottom:8px;width:3px;border-radius:99px;
 background:linear-gradient(180deg,var(--accent),transparent)}

/* === the tracking receipt, printed ========================================== */
section.receipt{transition:transform var(--slow) var(--ease-out)}
.receipt-tick{animation:undal-pop .5s var(--ease-spring) both}
@keyframes undal-pop{from{transform:scale(.4);opacity:0}to{transform:scale(1);opacity:1}}
.receipt-row{animation:undal-rise .34s var(--ease-out) both}

/* === forms that answer back ================================================= */
input,textarea,select{transition:border-color var(--quick),box-shadow var(--quick),background-color var(--quick)}
input:focus-visible,textarea:focus-visible,select:focus-visible{border-color:var(--brand);
 box-shadow:0 0 0 4px var(--brand-soft)}
label{transition:color var(--quick)}
.field-error,.field-note{animation:undal-rise var(--quick) var(--ease-out) both}

/* === pages turning ========================================================== */
@view-transition{navigation:auto}
::view-transition-old(root){animation:undal-fade-out .18s var(--ease-out) both}
::view-transition-new(root){animation:undal-fade-in .26s var(--ease-out) both}
@keyframes undal-fade-out{to{opacity:.4}}
@keyframes undal-fade-in{from{opacity:.4;transform:translateY(6px)}to{opacity:1;transform:none}}

/* === the small screens ====================================================== */
@media(max-width:40rem){
 :root{--radius-lg:22px;--radius-xl:26px}
 .store-hero{border-radius:var(--radius-lg)}
 .store-hero::after{width:70vw;opacity:.85}
 .undal-facts{grid-template-columns:repeat(auto-fit,minmax(min(100%,8rem),1fr));gap:8px}
 .undal-toasts{padding-bottom:calc(84px + env(safe-area-inset-bottom))}
 header h1{font-size:1.02rem}
}
@media(min-width:64rem){
 main,footer{padding-inline:var(--gutter)}
}
@media(prefers-reduced-motion:reduce){
 *,*::before,*::after{animation-duration:.001ms!important;animation-iteration-count:1!important;
  transition-duration:.001ms!important;scroll-behavior:auto!important}
 .js-reveal .reveal{opacity:1;transform:none}
 .store-hero::after,.hero-mark{animation:none}
 .undal-flight,.undal-ripple{display:none}
}
@media print{
 .undal-progress,.undal-toasts,.undal-basketbar,.undal-theme,nav.store{display:none!important}
 body{background:#fff;color:#000}
}
`;

/**
 * The inline script that decides the theme before the first paint.
 *
 * It runs in '<head>', in the oldest JavaScript dialect the storefront supports,
 * and it does exactly one thing: read the customer's saved choice (or the system
 * preference) and put it on '<html>'. Without it, every dark-mode visit flashes
 * white for the hundred milliseconds it takes the main script to arrive — which
 * is the whole reason "dark mode" gets switched off.
 */
export const THEME_BOOT_SCRIPT = `
(function () {
  var root = document.documentElement;
  var saved = "";
  try { saved = window.localStorage.getItem("undal-theme") || ""; } catch (error) { saved = ""; }
  if (saved !== "light" && saved !== "dark") {
    saved = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }
  root.setAttribute("data-undal-theme", saved);
  var meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", saved === "dark" ? "#0e1512" : "#165b3b");
  if (saved === "dark") root.className = (root.className ? root.className + " " : "") + "undal-dark";
})();
`;
