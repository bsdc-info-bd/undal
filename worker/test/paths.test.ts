/**
 * The router: its path normalisation and its route table.
 *
 * A base URL with a trailing slash produced `//healthz` and the Worker answered
 * "No route for GET //healthz" — so a healthy deployment reported itself broken,
 * the release webhook stopped updating /latest.json, and the deploy workflow
 * went red after everything it had done had actually worked.
 *
 * The normalisation is what broke, so it is pinned here as the one-liner it is:
 * the same expression `index.ts` uses, asserted against the shapes that arrive
 * in practice.
 *
 * The route table gets the other half of this file. A page or an endpoint that
 * is imported and never routed is invisible: the Worker builds, the tests pass,
 * and the URL answers 404 in production. Two checks make that impossible to
 * ship — every imported handler must be called somewhere in the router, and
 * every surface the header comment advertises must have a line that serves it.
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(join(here, "..", "src", "index.ts"), "utf8");

/** The expression the Worker applies to `url.pathname`, read out of the source. */
function normalise(pathname: string): string {
  return pathname.replace(/\/{2,}/g, "/").replace(/\/+$/, "") || "/";
}

describe("router path normalisation", () => {
  it("is the same normalisation the Worker uses", () => {
    assert.ok(
      source.includes('.replace(/\\/{2,}/g, "/").replace(/\\/+$/, "")'),
      "index.ts no longer collapses duplicate slashes before matching routes",
    );
  });

  it("survives the configuration shapes that actually occur", () => {
    assert.equal(normalise("/healthz"), "/healthz");
    assert.equal(normalise("//healthz"), "/healthz");
    assert.equal(normalise("/healthz/"), "/healthz");
    assert.equal(normalise("//healthz//"), "/healthz");
    assert.equal(normalise("//notify/test-alert"), "/notify/test-alert");
    assert.equal(normalise("//admin/release"), "/admin/release");
  });

  it("still understands the root and nested paths", () => {
    assert.equal(normalise("/"), "/");
    assert.equal(normalise("//"), "/");
    assert.equal(normalise(""), "/");
    assert.equal(normalise("/item/abc123"), "/item/abc123");
    assert.equal(normalise("/store"), "/store");
  });
});

// ------------------------------------------------------------------ route table

/** Every name imported from a local module, with the module it came from. */
function importedNames(text: string): Array<{ name: string; module: string }> {
  const found: Array<{ name: string; module: string }> = [];
  const pattern = /import\s+(?:type\s+)?\{([^}]*)\}\s+from\s+"(\.\.?\/[^"]+)"/g;
  for (const match of text.matchAll(pattern)) {
    const module = match[2] ?? "";
    for (const raw of (match[1] ?? "").split(",")) {
      const name = raw.trim().split(/\s+as\s+/).pop()?.trim() ?? "";
      if (name && !name.startsWith("type ")) found.push({ name, module });
    }
  }
  return found;
}

describe("the route table", () => {
  it("calls every handler it imports", () => {
    const orphans = importedNames(source).filter(({ name }) => {
      const uses = source.split(new RegExp(`\\b${name}\\b`, "g")).length - 1;
      return uses < 2;
    });
    assert.deepEqual(
      orphans.map((orphan) => `${orphan.name} from ${orphan.module}`),
      [],
      "an imported handler that is never routed answers 404 in production",
    );
  });

  it("serves every surface the header comment advertises", () => {
    const routes = [
      'path === "/"',
      'path === "/store"',
      'path === "/about"',
      'path === "/basket"',
      'path === "/checkout"',
      'path === "/login"',
      'path === "/account"',
      'path === "/robots.txt"',
      'path === "/sitemap.xml"',
      'path === "/track"',
      'path === "/download"',
      'path === "/download/apk"',
      'path === "/latest.json"',
      'path === "/manifest.webmanifest"',
      'path === "/sw.js"',
      'path === "/favicon.svg"',
      'path === "/healthz"',
      'path === "/.well-known/assetlinks.json"',
      'path.startsWith("/item/")',
      'path.startsWith("/dish/")',
      'path.startsWith("/combo/")',
      'path.startsWith("/order/")',
      'path.startsWith("/icons/")',
      'path === "/admin/cleanup"',
      'path === "/admin/release"',
      'path === "/notify/order-created"',
      'path === "/notify/order-status"',
      'path === "/notify/chat"',
      'path === "/notify/vendor-self"',
      'path === "/notify/test-alert"',
      'path === "/api/store/session"',
      'path === "/api/store/signin"',
      'path === "/api/store/order"',
      'path === "/api/store/menu"',
      'path.startsWith("/api/store/order/")',
      'path === "/api/account/signup"',
      'path === "/api/account/reset"',
      'path === "/api/account/refresh"',
      'path === "/api/account/profile"',
      'path === "/api/account/addresses"',
      'path === "/api/account/password"',
      'path === "/api/account/close"',
      'path === "/api/account/orders"',
      'path.startsWith("/api/account/addresses/")',
    ];
    for (const route of routes) {
      assert.ok(source.includes(route), `the router no longer matches ${route}`);
    }
  });

  it("answers the account API on the methods the pages actually send", () => {
    assert.match(source, /request\.method === "POST"/);
    assert.match(source, /request\.method === "DELETE"/, "removing a saved address is a DELETE");
    assert.match(
      source,
      /"access-control-allow-methods": "GET,POST,DELETE,OPTIONS"/,
      "a browser preflight must be told DELETE is allowed, or the address book cannot be edited from the web",
    );
  });

  it("counts the account endpoints against the rate limit that protects them", () => {
    assert.ok(
      source.includes('path.startsWith("/api/store/") || path.startsWith("/api/account/")'),
      "the account API shares the store's tighter bucket, not the loose web one",
    );
  });

  it("refuses an address id that is not one of ours before it reaches Firestore", () => {
    assert.match(source, /\/\^\[a-z0-9\]\{8,32\}\$\/\.test\(id\)/);
    assert.match(source, /errorJson\(400, "address-id-invalid"\)/);
  });
});
