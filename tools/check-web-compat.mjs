#!/usr/bin/env node
/**
 * Undal — browser-dialect gate (the "it works on my tablet" gate).
 *
 * The storefront ships its JavaScript inside the Worker, as template literals,
 * served inline on the page. That makes it easy to write — and easy to write in
 * today's dialect, which is exactly how a phone stops working while a tablet
 * does not:
 *
 *   one unsupported token → the browser refuses to parse the whole block →
 *   every control that block wired up does nothing.  Not "this feature is
 *   missing": sign-in, add to basket, continue as a guest, the lot, silently.
 *
 * So this gate reads the browser copy — the text *inside* the backticks, with
 * the Worker's own `${...}` interpolations skipped, because those are server
 * code — and fails on any construct that an older Android WebView, an in-app
 * browser or a locked-down phone browser cannot parse. Nothing here is about
 * taste: each entry is a construct whose absence is a blank page.
 *
 * Allowed, because the storefront cannot work without them and every browser
 * that can run the store at all has them: `fetch`, `Promise`, `JSON`, `var`,
 * `function`, `new RegExp` and `try/catch`.
 *
 * Run it by hand:  node tools/check-web-compat.mjs
 */
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const read = (relative) => readFileSync(join(ROOT, relative), "utf8");

/**
 * The files that carry browser-side code. Every one of them is served inline to
 * a phone; every one of them must parse there.
 */
const BROWSER_FILES = [
  "worker/src/web/cart-client.ts",
  "worker/src/web/map-client.ts",
  "worker/src/web/store-pages.ts",
  "worker/src/web/account-page.ts",
  "worker/src/web/download-page.ts",
  "worker/src/web/layout.ts",
  "worker/src/web/design.ts",
  "worker/src/web/motion.ts",
  "worker/src/web/motion-client.ts",
  "worker/src/web/pwa.ts",
];

/**
 * Constructs that break the parse (or the call) on a browser older than the
 * developer's. Each one names the engine floor it needs and what to write
 * instead, because "ES2020 syntax is banned" is not a repair instruction.
 */
const BANNED = [
  { pattern: /\?\.\s*[A-Za-z_$(]/, name: "optional chaining ?.", needs: "Chrome 80 / WebView 80", instead: "value && value.field" },
  { pattern: /\?\?/, name: "nullish coalescing ??", needs: "Chrome 80 / WebView 80", instead: "value === undefined ? fallback : value" },
  { pattern: /catch\s*\{/, name: "catch without a binding", needs: "Chrome 66 / WebView 66", instead: "catch (error) { }" },
  { pattern: /\bconst\s+[A-Za-z_$]/, name: "const", needs: "Chrome 49 / WebView 49", instead: "var" },
  { pattern: /\blet\s+[A-Za-z_$]/, name: "let", needs: "Chrome 49 / WebView 49", instead: "var" },
  { pattern: /=>/, name: "arrow function", needs: "Chrome 45 / WebView 45", instead: "function () { }" },
  { pattern: /\.\.\.\s*[A-Za-z_$[(]/, name: "spread or rest", needs: "Chrome 46 / WebView 46", instead: "a loop, or slice()" },
  { pattern: /\.padStart\(/, name: "String.prototype.padStart", needs: "Chrome 57 / WebView 57", instead: 'a two-digit helper: ("0" + value).slice(-2)' },
  { pattern: /\.padEnd\(/, name: "String.prototype.padEnd", needs: "Chrome 57 / WebView 57", instead: "a small pad helper" },
  { pattern: /\.replaceChildren\(/, name: "Element.replaceChildren", needs: "Chrome 86 / Safari 14", instead: 'node.innerHTML = "" then appendChild' },
  { pattern: /Object\.fromEntries\(/, name: "Object.fromEntries", needs: "Chrome 73 / WebView 73", instead: "a loop that fills an object" },
  { pattern: /\.flatMap\(/, name: "Array.prototype.flatMap", needs: "Chrome 69 / WebView 69", instead: "map + a loop" },
  { pattern: /\.flat\(\)/, name: "Array.prototype.flat", needs: "Chrome 69 / WebView 69", instead: "concat.apply([], list)" },
  { pattern: /Promise\.allSettled\(/, name: "Promise.allSettled", needs: "Chrome 76 / WebView 76", instead: "Promise.all with a catch per promise" },
  { pattern: /document\.querySelectorAll\([^)]*\)\.forEach\(/, name: "NodeList.forEach", needs: "Chrome 51 / WebView 51", instead: "a for loop" },
  { pattern: /crypto\.randomUUID\(/, name: "crypto.randomUUID", needs: "Chrome 92 / Safari 15.4", instead: "a token drawn from Math.random" },
  { pattern: /structuredClone\(/, name: "structuredClone", needs: "Chrome 98 / Safari 15.4", instead: "JSON.parse(JSON.stringify(value))" },
  { pattern: /globalThis\b/, name: "globalThis", needs: "Chrome 71 / WebView 71", instead: "window" },
  { pattern: /\/\[?\s*\\p\{[A-Za-z]+\}/, name: "Unicode property escape inside a regex literal", needs: "Chrome 64 / WebView 64", instead: 'new RegExp("[\\\\p{L}]", "u") inside try/catch' },
];

/**
 * The browser code in one Worker file: the text inside its template literals.
 *
 * A real single-pass scan, not a search for backticks: a template literal is
 * only a template literal when the scanner is in code, not inside a comment or
 * a quoted string. That distinction matters here — this file's own sibling
 * files *document* the banned tokens in comments and in help text, and a gate
 * that flags its own documentation is a gate everybody switches off.
 *
 * `${ ... }` interpolations are skipped: those are the Worker's own TypeScript,
 * which runs on the edge, not on the phone.
 */
function templateBodies(source) {
  const bodies = [];
  let index = 0;
  while (index < source.length) {
    const character = source[index];
    if (character === "/" && source[index + 1] === "/") {
      const end = source.indexOf("\n", index);
      index = end < 0 ? source.length : end + 1;
      continue;
    }
    if (character === "/" && source[index + 1] === "*") {
      const end = source.indexOf("*/", index + 2);
      index = end < 0 ? source.length : end + 2;
      continue;
    }
    if (character === "'" || character === '"') {
      index += 1;
      while (index < source.length && source[index] !== character) {
        index += source[index] === "\\" ? 2 : 1;
      }
      index += 1;
      continue;
    }
    if (character === "`") {
      let cursor = index + 1;
      let start = cursor;
      let body = "";
      for (; cursor < source.length; cursor++) {
        const inner = source[cursor];
        if (inner === "\\") {
          // Keep the escape: `\\u09F3` and `\\B` are real characters in the output.
          body += source.slice(start, cursor + 2);
          cursor += 1;
          start = cursor + 1;
          continue;
        }
        if (inner === "$" && source[cursor + 1] === "{") {
          body += source.slice(start, cursor);
          // Skip the interpolation, and everything nested inside it.
          let depth = 1;
          cursor += 2;
          while (cursor < source.length && depth > 0) {
            const deep = source[cursor];
            if (deep === "\\") cursor += 1;
            else if (deep === "{") depth += 1;
            else if (deep === "}") depth -= 1;
            else if (deep === "'" || deep === '"' || deep === "`") {
              const quote = deep;
              cursor += 1;
              while (cursor < source.length && source[cursor] !== quote) {
                cursor += source[cursor] === "\\" ? 2 : 1;
              }
            }
            cursor += 1;
          }
          start = cursor;
          cursor -= 1;
          continue;
        }
        if (inner === "`") break;
      }
      body += source.slice(start, cursor);
      bodies.push({ body, at: index });
      index = cursor + 1;
      continue;
    }
    index += 1;
  }
  return bodies;
}

/** The line number in the original file, for a message a human can act on. */
function lineOf(source, text) {
  const at = source.indexOf(text);
  if (at < 0) return 0;
  return source.slice(0, at).split("\n").length;
}

const problems = [];
for (const file of BROWSER_FILES) {
  const source = read(file);
  for (const { body, at } of templateBodies(source)) {
    const firstLine = source.slice(0, at).split("\n").length;
    for (const rule of BANNED) {
      const match = rule.pattern.exec(body);
      if (!match) continue;
      const snippet = match[0].length > 48 ? `${match[0].slice(0, 45)}…` : match[0];
      const innerLine = body.slice(0, Math.max(0, match.index)).split("\n").length;
      problems.push({
        file,
        line: firstLine + innerLine,
        message: `${rule.name} — needs ${rule.needs}; write ${rule.instead} (found “${snippet.replace(/\n/g, " ")}”)`,
      });
    }
  }
}

if (problems.length > 0) {
  console.error("check-web-compat: the storefront would not parse on an older phone browser.\n");
  for (const problem of problems) {
    console.error(`  ${problem.file}:${problem.line} — ${problem.message}`);
  }
  console.error(
    "\nOne unsupported token is one dead page: every control the script wires up stops working,\n" +
      "and only on the devices that cannot parse it. Rewrite it in the older dialect, or move it\n" +
      "behind a feature test.\n",
  );
  process.exit(1);
}

console.log("check-web-compat: the storefront's browser code parses on every Android WebView the store supports.");
