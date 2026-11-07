import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { Window } from "happy-dom";

const overview = {
  ok: true,
  branches: [
    { id: "default", name: "Undal HomeKitchen", active: true, open: true, acceptingOrders: true, radiusKm: 5 },
    { id: "north", name: "North kitchen", active: true, open: true, acceptingOrders: true, radiusKm: 4 },
  ],
  orders: [
    {
      id: "order-north", shortId: "UND-NORTH", branchId: "north", branchName: "North kitchen",
      customerName: "North <svg onload=window.pwned=true>", phone: "01", items: [{ qty: 1 }],
      status: "placed", totalAmountPaisa: 1000, createdAt: "2026-11-06T10:00:00.000Z",
    },
    {
      id: "order-default", shortId: "UND-DEFAULT", branchId: "default", branchName: "Undal HomeKitchen",
      customerName: "Default customer", phone: "02", items: [{ qty: 2 }],
      status: "accepted", totalAmountPaisa: 2000, createdAt: "2026-11-06T09:00:00.000Z",
    },
  ],
  menuItems: [
    { id: "global", name: "Shared kacchi", branchId: "*", categoryId: "rice", amountPaisa: 1000, available: true },
    { id: "north-only", name: "North special", branchId: "north", categoryId: "rice", amountPaisa: 1200, available: true },
  ],
  categories: [{ id: "rice", name: "Rice" }],
  deliveryRequests: [],
  staff: [],
};

function expose(window) {
  for (const [key, value] of Object.entries({
    window,
    document: window.document,
    sessionStorage: window.sessionStorage,
    location: window.location,
    history: window.history,
    navigator: window.navigator,
    requestAnimationFrame: window.requestAnimationFrame.bind(window),
    cancelAnimationFrame: window.cancelAnimationFrame.bind(window),
    ResizeObserver: window.ResizeObserver,
  })) {
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
}

const click = (window, element) => element.dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
const change = (window, element) => element.dispatchEvent(new window.Event("change", { bubbles: true }));

test("admin SPA keeps menu/order branch filters independent and escapes live data", async (context) => {
  const window = new Window({ url: "https://admin.undal.test/#not-a-real-route" });
  context.after(() => window.happyDOM.close());
  expose(window);
  window.document.write(readFileSync(new URL("../../admin/public/index.html", import.meta.url), "utf8"));
  window.sessionStorage.setItem("undal_admin_token", "short-lived-test-token");
  window.sessionStorage.setItem("undal_admin_uid", "super-admin-test");
  const requests = [];
  globalThis.fetch = async (path, options) => {
    requests.push({ path, options });
    assert.equal(path, "/api/admin/overview");
    assert.equal(options.headers.authorization, "Bearer short-lived-test-token");
    return { ok: true, status: 200, json: async () => structuredClone(overview) };
  };

  await import(`../../admin/public/app.js?test=${Date.now()}`);
  for (let attempt = 0; attempt < 50 && !window.document.querySelector(".metric-grid"); attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  const document = window.document;
  assert.ok(document.querySelector(".metric-grid"), "the restored session rendered the dashboard");
  assert.equal(window.location.hash, "#dashboard", "an invalid deep link cannot crash route rendering");
  assert.equal(document.querySelector("#order-nav-count").textContent, "2");
  assert.equal(requests.length, 1);

  click(window, document.querySelector('#side-nav [data-route="orders"]'));
  assert.equal(document.querySelectorAll("#content tbody tr").length, 2, "untouched menu filter must not hide every order");
  assert.ok(document.querySelector("#content").textContent.includes("UND-NORTH"));
  assert.ok(document.querySelector("#content").textContent.includes("UND-DEFAULT"));
  assert.equal(document.querySelector("[data-order-branch-filter]").value, "all");
  assert.equal(document.querySelectorAll("#content [onload]").length, 0, "customer text never becomes an SVG element");
  assert.ok(document.querySelector("#content").textContent.includes("North <svg onload=window.pwned=true>"));

  click(window, document.querySelector('#side-nav [data-route="menu"]'));
  assert.ok(document.querySelector("#content").textContent.includes("Shared kacchi"));
  assert.equal(document.querySelector("#content").textContent.includes("North special"), false);
  const menuFilter = document.querySelector("[data-menu-branch-filter]");
  menuFilter.value = "north";
  change(window, menuFilter);
  assert.ok(document.querySelector("#content").textContent.includes("North special"));
  assert.equal(document.querySelector("#content").textContent.includes("Shared kacchi"), false);

  click(window, document.querySelector('#side-nav [data-route="orders"]'));
  assert.equal(document.querySelectorAll("#content tbody tr").length, 2, "menu branch selection must not become an order filter");
  assert.equal(document.querySelector("[data-order-branch-filter]").value, "all");

  click(window, document.querySelector('#side-nav [data-route="branches"]'));
  click(window, document.querySelector('[data-branch-orders="north"]'));
  assert.equal(document.querySelectorAll("#content tbody tr").length, 1);
  assert.ok(document.querySelector("#content").textContent.includes("UND-NORTH"));
  assert.equal(document.querySelector("#content").textContent.includes("UND-DEFAULT"), false);
  assert.equal(document.querySelector("[data-order-branch-filter] option[selected]").value, "north");

  click(window, document.querySelector("#logout-button"));
  assert.equal(window.sessionStorage.getItem("undal_admin_token"), null);
  assert.equal(window.sessionStorage.getItem("undal_admin_uid"), null);
  assert.equal(document.querySelector("#login-view").hidden, false);
  assert.equal(document.querySelector("#admin-view").hidden, true);
  await window.happyDOM.close();
});

test("admin keeps phone actions and editor controls at a 44px touch target", () => {
  const css = readFileSync(new URL("../../admin/public/app.css", import.meta.url), "utf8");
  const mobile = css.match(/@media \(max-width: 680px\) \{([\s\S]*?)\n\}\n@media \(prefers-reduced-motion/);
  assert.ok(mobile, "the phone layout remains explicit");
  assert.match(css, /\.button \{[^}]*min-height: 44px/);
  assert.match(css, /\.icon-button \{[^}]*width: 44px; height: 44px/);
  assert.match(css, /\.dialog-close \{[^}]*width: 44px; height: 44px/);
  assert.match(css, /\.undal-map-controls button \{[^}]*width: 44px; height: 44px/);
  assert.match(mobile[1], /\.icon-button,\.avatar \{ width: 44px; height: 44px/);
  assert.match(mobile[1], /\.hours-row input \{ min-height: 44px/);
  assert.match(mobile[1], /\.button--small \{ min-height: 44px/);
});
