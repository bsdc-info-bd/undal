/**
 * Just enough DOM to run a storefront page's inline script.
 *
 * The tracking page is the one screen a customer who ordered in a browser ever
 * sees, and its JavaScript is the only code in this Worker that nothing
 * executes: it lives inside a template literal, so the compiler never reads it
 * and the test suite never runs it. That is exactly where a page silently stops
 * updating — a typo in the poll, a property that does not exist, an innerHTML
 * that throws on the one order that has a coupon.
 *
 * This file is a deliberately small HTML reader: elements, ids and classes, the
 * attributes the pages actually use, and `innerHTML`, which is kept as the
 * string the script wrote so a test can read back what a customer would see.
 * It is not a browser. It is a place to run the script and look.
 */

export type MiniNode = MiniElement | { text: string };

export class MiniElement {
  tagName: string;
  attributes = new Map<string, string>();
  children: MiniNode[] = [];
  /** Whatever the script assigned, verbatim — what the customer is shown. */
  html = "";
  text = "";
  className = "";

  constructor(tagName: string) {
    this.tagName = tagName.toLowerCase();
  }

  get id(): string {
    return this.attributes.get("id") ?? "";
  }

  /** The visible text of this element and its children, whitespace collapsed. */
  get textContent(): string {
    if (this.html.length > 0) return stripTags(this.html);
    const parts = [this.text];
    for (const child of this.children) {
      parts.push("tagName" in child ? child.textContent : child.text);
    }
    return stripTags(parts.join(""));
  }

  set textContent(value: string) {
    this.html = "";
    this.children = [];
    this.text = String(value);
  }

  set innerHTML(value: string) {
    this.html = String(value);
    this.children = [];
    this.text = "";
  }

  get innerHTML(): string {
    return this.html;
  }

  getAttribute(name: string): string | null {
    return this.attributes.get(name) ?? null;
  }

  setAttribute(name: string, value: string): void {
    this.attributes.set(name, String(value));
  }

  removeAttribute(name: string): void {
    this.attributes.delete(name);
  }

  hasAttribute(name: string): boolean {
    return this.attributes.has(name);
  }

  /** Every descendant, depth first, so a selector can be answered. */
  descendants(): MiniElement[] {
    const found: MiniElement[] = [];
    for (const child of this.children) {
      if ("tagName" in child) {
        found.push(child, ...child.descendants());
      }
    }
    return found;
  }

  matchesSelector(selector: string): boolean {
    const trimmed = selector.trim();
    if (trimmed.startsWith("#")) return this.id === trimmed.slice(1);
    if (trimmed.startsWith(".")) return this.className.split(/\s+/).includes(trimmed.slice(1));
    return this.tagName === trimmed.toLowerCase();
  }
}

function stripTags(html: string): string {
  return html.replace(/<[^>]*>/g, "").replace(/\s+/g, " ").trim();
}

/** Reads a tag's attributes into a map; quotes are required, as the pages write them. */
function readAttributes(source: string): Map<string, string> {
  const attributes = new Map<string, string>();
  const pattern = /([A-Za-z-]+)\s*=\s*"([^"]*)"/g;
  let match = pattern.exec(source);
  while (match !== null) {
    attributes.set((match[1] ?? "").toLowerCase(), match[2] ?? "");
    match = pattern.exec(source);
  }
  return attributes;
}

const VOID_TAGS = new Set(["br", "hr", "img", "input", "meta", "link", "path", "source"]);

/**
 * Reads markup into a tree. Self-closing tags, void tags and unquoted text are
 * all handled; comments and doctypes are skipped. An unknown tag is treated as
 * text-only, which is all any of these pages need.
 */
export function parseHtml(html: string): MiniElement {
  // Scripts are taken out first: their JavaScript contains the markup of
  // notices this harness builds as strings, and those tags would otherwise be
  // read as elements of the page.
  const markup = html.replace(/<script[\s\S]*?<\/script>/gi, "");
  const root = new MiniElement("html");
  const stack: MiniElement[] = [root];
  const pattern = /<!--[\s\S]*?-->|<!doctype[^>]*>|<\/([A-Za-z][A-Za-z0-9]*)\s*>|<([A-Za-z][A-Za-z0-9]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(\/?)>|([^<]+)/gi;
  let match = pattern.exec(markup);
  while (match !== null) {
    const [whole, closing, opening, attributes, selfClosing, text] = match;
    const top = stack[stack.length - 1]!;
    if (opening) {
      const element = new MiniElement(opening);
      element.attributes = readAttributes(attributes ?? "");
      top.children.push(element);
      const closed = (selfClosing ?? "").length > 0 || VOID_TAGS.has(element.tagName);
      if (!closed) stack.push(element);
    } else if (closing) {
      const wanted = closing.toLowerCase();
      for (let index = stack.length - 1; index > 0; index -= 1) {
        if (stack[index]!.tagName === wanted) {
          stack.length = index;
          break;
        }
      }
    } else if (text !== undefined && text.length > 0 && whole.startsWith(text)) {
      top.children.push({ text });
    }
    match = pattern.exec(markup);
  }
  return root;
}

/** A `window` with the timers a page script uses, recorded instead of firing. */
export type MiniWindow = {
  _undalPoll?: number;
  setInterval: (handler: () => void, ms: number) => number;
  clearInterval: (handle: number) => void;
  intervals: Array<{ handle: number; ms: number; handler: () => void }>;
  cleared: number[];
};

/**
 * Runs one page's inline script against its own markup.
 *
 * `fetch` is whatever the caller passes, so a test can answer the poll with a
 * real order document. The script runs inside `new Function`, which is the only
 * way to execute code the TypeScript compiler has never seen — the point of the
 * exercise.
 */
export type MiniFetchAnswer = { status?: number; json?: unknown };

export function runInlineScript(
  html: string,
  options: { fetch?: (url: string) => MiniFetchAnswer | Promise<MiniFetchAnswer> } = {},
): { document: MiniElement; window: MiniWindow; ids: (id: string) => MiniElement } {
  const document = parseHtml(html);
  const intervals: MiniWindow["intervals"] = [];
  const cleared: number[] = [];
  let nextHandle = 1;
  const window: MiniWindow = {
    intervals,
    cleared,
    setInterval: (handler, ms) => {
      const handle = nextHandle++;
      intervals.push({ handle, ms, handler });
      return handle;
    },
    clearInterval: (handle) => {
      cleared.push(handle);
    },
  };

  const scripts = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map((match) => match[1] ?? "");
  if (scripts.length === 0) throw new Error("the page has no inline script to run");
  const code = scripts[scripts.length - 1]!;

  const byId = (id: string): MiniElement => {
    for (const element of document.descendants()) {
      if (element.id === id) return element;
    }
    // The script is allowed to address an element the markup lost; creating it
    // here means a test can still read what was written and see it went nowhere.
    const missing = new MiniElement("div");
    missing.setAttribute("id", id);
    document.children.push(missing);
    return missing;
  };

  const scoped = {
    getElementById: byId,
    querySelectorAll: (selector: string): MiniElement[] => {
      const parts = selector.trim().split(/\s+/);
      const head = parts[0] ?? "";
      const scopes = document.descendants().filter((element) => element.matchesSelector(head));
      const found: MiniElement[] = [];
      for (const scope of scopes) {
        const candidates = parts.length === 1 ? [scope] : scope.descendants();
        for (const candidate of candidates) {
          if (parts.length === 1 || candidate.matchesSelector(parts[parts.length - 1]!)) found.push(candidate);
        }
      }
      return found;
    },
  };

  const fetcher = options.fetch ?? (() => ({}));
  const run = new Function("window", "document", "fetch", `"use strict";\n${code}`);
  run(window, scoped, (url: string) =>
    Promise.resolve(fetcher(String(url))).then((answer) => ({
      // The status a page branches on is the status the test asked for; a page
      // that reads a status it was not given sees 200, like a live response.
      status: answer.status ?? 200,
      json: () => Promise.resolve(answer.json),
    })));
  return { document, window, ids: byId };
}

/** Lets the microtask queue drain, so a polled `fetch().then()` has landed. */
export async function settle(): Promise<void> {
  for (let round = 0; round < 8; round += 1) await Promise.resolve();
}
