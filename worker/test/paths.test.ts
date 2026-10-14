/**
 * The router's path normalisation.
 *
 * A base URL with a trailing slash produced `//healthz` and the Worker answered
 * "No route for GET //healthz" — so a healthy deployment reported itself broken,
 * the release webhook stopped updating /latest.json, and the deploy workflow
 * went red after everything it had done had actually worked.
 *
 * The route table is exercised through `route()` itself where it can be reached
 * without Firebase, but the normalisation is what broke, so it is pinned here as
 * the one-liner it is: the same expression `index.ts` uses, asserted against the
 * shapes that arrive in practice.
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
