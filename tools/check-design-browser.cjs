/** Optional local QA against the SAMPLE preview, never production.
 * Install playwright-core and @sparticuz/chromium outside the checkout; point
 * UNDAL_BROWSER_MODULES at that directory. See docs/DESIGN-UPGRADE-PROGRESS.md.
 * This checks reflow and real no-JavaScript basket forms, not payment/FCM delivery.
 */
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const path = require('node:path');
const fs = require('node:fs');
const load = process.env.UNDAL_BROWSER_MODULES
  ? createRequire(path.resolve(process.env.UNDAL_BROWSER_MODULES, 'package.json')) : require;
const { chromium: playwright } = load('playwright-core');
const moduleChromium = load('@sparticuz/chromium');
const chromium = moduleChromium.default ?? moduleChromium;
const origin = process.env.UNDAL_PREVIEW_ORIGIN ?? 'http://127.0.0.1:8790';
assert.match(origin, /^http:\/\/(127\.0\.0\.1|localhost):\d+$/, 'Only run this test against a local sample preview');
const screenshotDir = process.env.UNDAL_DESIGN_SCREENSHOTS;
const paths = ['/store', '/item/kacchi', '/basket', '/checkout', '/login', '/about', '/track?id=previewOrder1', '/download', '/account', '/track'];
const launch = async () => playwright.launch({ args: chromium.args, executablePath: await chromium.executablePath(), headless: true });
async function reflow(page, route, width, largeText = false) {
  await page.setViewportSize({ width, height: 900 });
  await page.goto(origin + route, { waitUntil: 'domcontentloaded' });
  if (largeText) await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
  const size = await page.evaluate(() => ({ viewport: innerWidth, content: document.documentElement.scrollWidth }));
  assert.ok(size.content <= size.viewport + 1, `Horizontal overflow: ${route}, ${width}px, large text=${largeText}: ${JSON.stringify(size)}`);
}
(async () => {
  if (process.env.UNDAL_BROWSER_FONT) {
    const fontDirectory = process.env.FONTCONFIG_PATH ?? '/tmp/fonts';
    process.env.FONTCONFIG_PATH = fontDirectory;
    fs.mkdirSync(fontDirectory, { recursive: true });
    fs.copyFileSync(process.env.UNDAL_BROWSER_FONT, path.join(fontDirectory, path.basename(process.env.UNDAL_BROWSER_FONT)));
  }
  let browser = await launch();
  let count = 0;
  try {
    const page = await browser.newPage({ reducedMotion: 'reduce' });
    // Network-independent QA: third-party Firebase/map calls are not certified by this test.
    await page.route('**/*', route => route.request().url().startsWith(origin + '/') ? route.continue() : route.abort());
    for (const width of [320, 375, 768, 1024, 1440]) {
      for (const route of paths) { await reflow(page, route, width); count++; }
    }
    for (const width of [320, 768, 1440]) {
      for (const route of ['/store', '/item/kacchi', '/checkout']) {
        await reflow(page, route, width, true); count++;
      }
    }
    await page.goto(origin + '/login');
    await page.locator('#tab-signin').focus();
    await page.locator('#tab-signin').press('ArrowRight');
    assert.equal(await page.locator('#tab-signup').getAttribute('aria-selected'), 'true');
    assert.equal(await page.locator('#tab-signin').getAttribute('tabindex'), '-1');
    await page.locator('#tab-signup').press('End');
    assert.equal(await page.locator('#tab-reset').getAttribute('aria-selected'), 'true');
    await page.locator('#tab-reset').press('Home');
    assert.equal(await page.locator('#tab-signin').getAttribute('aria-selected'), 'true');
    await page.route('**/api/store/order/**', route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"ok":false}' }));
    await page.goto(origin + '/order/previewOrder1');
    await page.locator('#undal-connection').waitFor({ state: 'visible' });
    assert.match(await page.locator('#undal-connection').innerText(), /Could not refresh/);
    if (screenshotDir) {
      fs.mkdirSync(screenshotDir, { recursive: true });
      for (const [route, width, file] of [['/store', 1440, 'menu-desktop.png'], ['/item/kacchi', 375, 'item-mobile.png']]) {
        await reflow(page, route, width);
        await page.screenshot({ path: path.join(screenshotDir, file), fullPage: true });
      }
    }
  } finally { await browser.close(); }
  // A fresh process is necessary for the optional single-process Chromium build.
  browser = await launch();
  try {
    const page = await browser.newPage({ javaScriptEnabled: false, viewport: { width: 375, height: 900 } });
    await page.goto(origin + '/item/kacchi');
    await page.locator('form.undal-add-wide button[type=submit]').click();
    await page.waitForURL(/\/basket(?:\?|$)/);
    assert.equal((await page.locator('.nojs .qty > span').first().textContent()).trim(), '1');
    await page.locator('.nojs form[action="/basket/add"] button').first().click();
    await page.waitForURL(/\/basket(?:\?|$)/);
    assert.equal((await page.locator('.nojs .qty > span').first().textContent()).trim(), '2');
    await page.locator('.nojs form[action="/basket/remove"] button').first().click();
    await page.waitForURL(/\/basket(?:\?|$)/);
    assert.match(await page.locator('main').innerText(), /Your basket is empty/);
    await page.goto(origin + '/account');
    assert.match(await page.locator('noscript').innerText(), /Your account needs JavaScript/);
    await page.goto(origin + '/checkout');
    assert.equal(await page.locator('form#undal-checkout-form[action="/checkout/place"][method="post"] button[type="submit"]').count(), 1);
  } finally { await browser.close(); }
  console.log(`Design browser QA: ${count} reflow cases pass; keyboard tabs, refresh recovery, no-JavaScript account fallback/basket forms/checkout form pass. Sample data only.`);
})().catch(error => { console.error(error); process.exitCode = 1; });
