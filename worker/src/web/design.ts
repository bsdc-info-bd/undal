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
`;
