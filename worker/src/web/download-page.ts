/**
 * The install page (P-016 · P-017).
 *
 * Three honest blocks: scan-and-install with a QR code drawn in the Worker, the
 * exact steps for a phone that has never allowed a direct APK, and a plain list
 * of what is app-only. Nothing here promises something the web store cannot do.
 */
import type { Env } from "../env";
import { escapeHtml } from "../util/http";
import { renderPage } from "./layout";
import { qrSvg } from "../util/qr";
import { menuAgeSeconds } from "../routes/store";

export async function handleDownloadPage(env: Env, request: Request): Promise<Response> {
  const origin = new URL(request.url).origin;
  const slug = env.GITHUB_REPO_SLUG || "bsdc-info-bd/undal";
  const apkUrl = `https://github.com/${slug}/releases/latest/download/undal.apk`;
  const releaseUrl = `https://github.com/${slug}/releases/latest`;
  const cacheAge = await menuAgeSeconds(env).catch(() => -1);

  const qr = qrSvg(origin, { moduleSize: 4, quietZone: 3, dark: "#14261B" });
  const apkQr = qrSvg(apkUrl, { moduleSize: 3, quietZone: 3, dark: "#14261B" });

  const body = `
<section class="card">
  <span class="pill open">Free · no account in a store · no payment in the app</span>
  <h2 style="margin-top:10px">Install Undal HomeKitchen</h2>
  <p class="muted">One APK, about ten megabytes, built and signed by this repository's own workflow. The kitchen and the customer share the same app: your account decides which side you see.</p>
  <a class="button" href="/download">Download undal.apk</a>
  <a class="button secondary" href="${escapeHtml(releaseUrl)}">See the release notes</a>
  ${
    cacheAge >= 0
      ? `<p class="smallprint">Web menu snapshot is ${cacheAge} second(s) old — the app always reads live.</p>`
      : ""
  }
</section>

<section class="card">
  <h2>Scan with the other phone</h2>
  <p class="muted">Hold the kitchen phone under this code, or scan it from any Android camera.</p>
  <div class="qr">${qr}</div>
  <p class="smallprint">That code is the store link <code>${escapeHtml(origin)}</code> — scanning it opens the menu in the browser, where the install button and this page live.</p>
  <h2 style="margin-top:16px">Or scan the APK directly</h2>
  <div class="qr">${apkQr}</div>
  <p class="smallprint">That one downloads the file straight from GitHub Releases.</p>
</section>

<section class="card">
  <h2>Installing an APK for the first time</h2>
  <ol class="steps">
    <li><span class="dot"></span><span>Tap the download button above, or scan the QR code with the phone that should run the app.</span></li>
    <li><span class="dot"></span><span>Android will say the file comes from an unknown source. Tap <strong>Settings</strong> in that message, then turn on <strong>Allow from this source</strong> for Chrome (or Files).</span></li>
    <li><span class="dot"></span><span>Go back and tap the downloaded file again. Choose <strong>Install</strong>. Play Protect may warn that the app is not from the Play Store — tap <strong>Install anyway</strong>. This is normal for a direct APK.</span></li>
    <li><span class="dot"></span><span>Open Undal and allow notifications. The kitchen phone must also allow battery-unrestricted background work — the app walks you through it on first login.</span></li>
    <li><span class="dot"></span><span>Later updates install over the top of the old app. Never uninstall first: your login and your cart survive, and the server keeps your orders either way.</span></li>
  </ol>
  <p class="smallprint">The download link never changes: <code>${escapeHtml(apkUrl)}</code></p>
</section>

<section class="card">
  <h2>What the web store can and cannot do</h2>
  <p class="muted">We would rather you know before you install.</p>
  <div class="row"><span>Sit in a browser, order and pay cash on delivery</span><span><strong>Works here</strong></span></div>
  <div class="row"><span>Browse the full menu, search, combos, live prices</span><span><strong>Works here</strong></span></div>
  <div class="row"><span>Watch an order's status on a tracking link</span><span><strong>Works here</strong></span></div>
  <div class="row"><span>Install to the home screen and read it offline</span><span id="pwa-state">Checking…</span></div>
  <div class="row"><span>Push notification when the kitchen accepts, packs or sends</span><span><strong>App only</strong></span></div>
  <div class="row"><span>Live order tracking with the prep countdown</span><span><strong>App only</strong></span></div>
  <div class="row"><span>Chat with the kitchen inside the order</span><span><strong>App only</strong></span></div>
  <div class="row"><span>The Undal order sound that keeps ringing until somebody answers</span><span><strong>Kitchen app only</strong></span></div>
  <div class="row"><span>Reorder your last order in one tap</span><span><strong>App only</strong></span></div>
  <div class="row"><span>Cash on delivery, exactly the same price as the app</span><span><strong>Both</strong></span></div>
</section>

<section class="card">
  <h2>Which app am I?</h2>
  <p class="muted">The kitchen account is created by the repository's own bootstrap workflow and carries a <code>vendor</code> claim. Signing in with it shows the kitchen console; any other account shows the customer store. There is no switch and no hidden menu — the account decides, always.</p>
  <a class="button secondary" href="/app">Already installed? Open Undal</a>
</section>

<script>
(function () {
  var holder = document.getElementById("pwa-state");
  var installable = "serviceWorker" in navigator && "manifest" in document;
  holder.textContent = installable ? "Works here — menu and install" : "Needs a newer browser";
  if (!("serviceWorker" in navigator)) return;
  navigator.serviceWorker.register("/sw.js").catch(function () { /* private mode, or blocked */ });
})();
</script>`;

  return renderPage({
    title: "Install Undal HomeKitchen — Sylhet",
    description:
      "Download the Undal HomeKitchen APK, scan the QR code, and see exactly which features are app-only.",
    body,
    nav: `<nav class="store"><div class="inner">
<a href="/store">Menu</a>
<a class="active" href="/download">Install</a>
<a href="/track">Track an order</a>
<a class="cart" href="/basket">Basket <span id="undal-cart-count" style="display:none"></span></a>
</div></nav>`,
    cacheSeconds: 120,
  });
}
