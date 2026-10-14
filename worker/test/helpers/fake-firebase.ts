/**
 * A fake Firebase for the Worker's tests.
 *
 * The point of this file is that a route which talks to Firestore, the Realtime
 * Database and KV can be run end to end — including its failure paths — without
 * a network, a project or a credential. The history cleanup has four ways to go
 * wrong in production (a missing composite index, an unreachable RTDB, a
 * transient Google 503, a batch that is empty), and each one of them is a test
 * here rather than a guess.
 *
 * It implements exactly the REST surface `src/firestore.ts` and `src/rtdb.ts`
 * use, with the real refusal shapes Google answers with:
 *
 *   · a two-field filter with no matching composite index → 400
 *     FAILED_PRECONDITION and the console link that creates it;
 *   · a single-field `orderBy` → always served, because Firestore builds a
 *     single-field index for every field automatically;
 *   · `rtdbStatus = 401` → `{ "error": "Permission denied" }`, the shape the
 *     Realtime Database really uses.
 *
 * Firebase Authentication is faked here too, and it is not a short-cut: the
 * accounts it creates hand back ID tokens signed by a real RSA key whose
 * certificate is published under the KV key `gcp.ts` caches Google's own
 * certificates under (see ./fake-auth.ts). A route that signs a customer up and
 * then uses the token it was given exercises `verifyIdToken()` for real.
 */
import type { Env } from "../../src/env";
import { decodeJwtPayload } from "../../src/gcp";
import { fakeAuthSigner, type FakeAuthSigner } from "./fake-auth";

export const FAKE_PROJECT = "undal-test";
export const FAKE_RTDB_URL = "https://undal-test-default-rtdb.asia-southeast1.firebasedatabase.app";
const DOC_PREFIX = `projects/${FAKE_PROJECT}/databases/(default)/documents/`;
const FIRESTORE_BASE = `https://firestore.googleapis.com/v1/projects/${FAKE_PROJECT}/databases/(default)/documents`;

/** The refusal Firestore sends when a query needs an index that is not built. */
export const INDEX_REFUSAL = (indexLink: string): string =>
  JSON.stringify({
    error: {
      code: 400,
      message: `The query requires an index. You can create it here: ${indexLink}`,
      status: "FAILED_PRECONDITION",
    },
  });

export const INDEX_CONSOLE_LINK =
  "https://console.firebase.google.com/v1/r/project/undal-test/firestore/indexes?create_composite=CicScHJvamVjdHMvdW5kYWwtdGVzdC9kYXRhYmFzZXMvKGRlZmF1bHQpL2NvbGxlY3Rpb25Hcm91cHMvb3JkZXJzL2luZGV4ZXMvXxABGg0KCXN0YXR1cxMQARoNCgl1cGRhdGVkQXQzEAEMAg";

// --------------------------------------------------------------- REST values

export function toRestValue(value: unknown): Record<string, unknown> {
  if (value === null || value === undefined) return { nullValue: null };
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "number") {
    return Number.isInteger(value) ? { integerValue: String(value) } : { doubleValue: value };
  }
  if (typeof value === "string") return { stringValue: value };
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(toRestValue) } };
  return { mapValue: { fields: Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([k, v]) => [k, toRestValue(v)])) } };
}

export function fromRestValue(value: Record<string, unknown> | undefined): unknown {
  if (!value) return undefined;
  if ("nullValue" in value) return null;
  if ("booleanValue" in value) return value.booleanValue;
  if ("integerValue" in value) return Number(value.integerValue);
  if ("doubleValue" in value) return value.doubleValue;
  if ("timestampValue" in value) return value.timestampValue;
  if ("stringValue" in value) return value.stringValue;
  if ("arrayValue" in value) return ((value.arrayValue as { values?: Record<string, unknown>[] }).values ?? []).map(fromRestValue);
  if ("mapValue" in value) {
    const fields = (value.mapValue as { fields?: Record<string, Record<string, unknown>> }).fields ?? {};
    return Object.fromEntries(Object.entries(fields).map(([key, nested]) => [key, fromRestValue(nested)]));
  }
  return undefined;
}

// -------------------------------------------------------------------- filters

interface FieldFilter {
  fieldFilter?: { field?: { fieldPath?: string }; op?: string; value?: Record<string, unknown> };
  compositeFilter?: { op?: string; filters?: FieldFilter[] };
}

function flattenFilters(where: FieldFilter | undefined): Array<{ field: string; op: string; value: unknown }> {
  if (!where) return [];
  if (where.compositeFilter) return (where.compositeFilter.filters ?? []).flatMap((child) => flattenFilters(child));
  const filter = where.fieldFilter;
  if (!filter) return [];
  return [{ field: filter.field?.fieldPath ?? "", op: filter.op ?? "EQUAL", value: fromRestValue(filter.value) }];
}

/** Firestore's ordering across types, reduced to the comparisons we rely on. */
function compareValues(left: unknown, right: unknown): number {
  const key = (value: unknown): number | string => {
    if (typeof value === "number") return value;
    if (typeof value === "string") {
      const parsed = Date.parse(value);
      return Number.isFinite(parsed) && /^\d{4}-\d{2}-\d{2}T/.test(value) ? parsed : value;
    }
    return String(value ?? "");
  };
  const a = key(left);
  const b = key(right);
  if (typeof a === "number" && typeof b === "number") return a - b;
  return String(a).localeCompare(String(b));
}

function matches(fields: Record<string, unknown>, filter: { field: string; op: string; value: unknown }): boolean {
  const actual = fields[filter.field];
  if (actual === undefined) return false;
  switch (filter.op) {
    case "EQUAL": return compareValues(actual, filter.value) === 0;
    case "LESS_THAN": return compareValues(actual, filter.value) < 0;
    case "LESS_THAN_OR_EQUAL": return compareValues(actual, filter.value) <= 0;
    case "GREATER_THAN": return compareValues(actual, filter.value) > 0;
    case "GREATER_THAN_OR_EQUAL": return compareValues(actual, filter.value) >= 0;
    default: return false;
  }
}

// -------------------------------------------------------------------- backend

/**
 * The request body as text, whatever shape it arrived in.
 *
 * `fetch()` accepts a string, a `URLSearchParams` (which the OAuth and refresh
 * token exchanges use) or bytes. A fake that only understood strings would
 * silently answer every form post with "no refresh token", which is the kind of
 * test lie that lets a real bug through.
 */
function bodyTextOf(body: unknown): string {
  if (typeof body === "string") return body;
  if (body instanceof URLSearchParams) return body.toString();
  if (body instanceof Uint8Array) return new TextDecoder().decode(body);
  if (body instanceof ArrayBuffer) return new TextDecoder().decode(body);
  if (body && typeof body === "object") return JSON.stringify(body);
  return "";
}

/** `/v1/projects/p/databases/(default)/documents/notifInbox/u/items:runQuery` → `notifInbox/u/items`. */
function documentParent(path: string): string {
  return path
    .replace(/^\/v1\/projects\/[^/]+\/databases\/\(default\)\/documents/, "")
    .replace(/:runQuery$/, "")
    .replace(/^\//, "");
}

export class FakeFirebase {
  /** Firestore documents, keyed by their relative path (`orders/abc`). */
  readonly docs = new Map<string, Record<string, unknown>>();
  /** KV, in memory. */
  readonly kv = new Map<string, string>();
  /** Realtime Database values, keyed by path (`orderLive/abc`). */
  readonly rtdb = new Map<string, unknown>();
  /** Composite indexes that exist, as `collection:fieldA,fieldB`. */
  readonly compositeIndexes = new Set<string>();
  /** Every request the Worker made, as `METHOD host/path`, for assertions. */
  readonly log: string[] = [];
  /** Bodies of the commit calls, so a test can see exactly what was deleted. */
  readonly commits: Array<{ writes: Array<{ delete?: string }> }> = [];
  /** Bodies of the RTDB PATCH calls. */
  readonly rtdbPatches: Array<Record<string, unknown>> = [];
  /** Set to a status to make the Realtime Database refuse everything. */
  rtdbStatus = 200;
  /** Set to make one query answer with a transient failure before succeeding. */
  transientQueryFailures = 0;
  /** Firestore merge writes, in order, with the server-timestamp fields asked for. */
  readonly writes: Array<{ path: string; fields: Record<string, unknown>; serverTimestamps: string[] }> = [];
  /** Firebase Authentication accounts, keyed by uid. */
  readonly accounts = new Map<string, { email: string; password: string; displayName: string }>();
  /** Refresh token → uid, the way the securetoken endpoint resolves a session. */
  readonly refreshTokens = new Map<string, string>();
  /** Every Identity Toolkit call, as its path, for assertions. */
  readonly authLog: string[] = [];
  /** Emails a password-reset link was sent to. */
  readonly resetEmails: string[] = [];
  /** Set to a status to make Firebase Authentication refuse everything. */
  authStatus = 200;
  /** Every FCM message the Worker sent, parsed: `{ token?, topic?, message }`. */
  readonly fcmSends: Array<Record<string, any>> = [];
  /** Refuse every FCM send, with `fcmStatus` and `fcmErrorCode` as the reason. */
  fcmRefuseAll = false;
  /** The HTTP status and error code a refused send answers with. */
  fcmStatus = 404;
  fcmErrorCode = "UNREGISTERED";
  /**
   * Refuse only this many of the next sends, then behave normally. A dead token
   * is dead; the topic that follows it is not, and a fake that could not tell the
   * two apart would make the ladder's fallbacks untestable.
   */
  fcmFailFirst = 0;
  /** The key this backend signs ID tokens with. */
  readonly signer: FakeAuthSigner = fakeAuthSigner();

  private accountSequence = 0;

  constructor() {
    // gcp.ts reads the cached OAuth token from KV; seeding it means no token
    // exchange is needed and the fake service account is never signed with.
    this.kv.set("oauth:token", "fake-access-token-0123456789abcdef");
    // ...and the certificate the ID-token verifier looks up by `kid`.
    this.kv.set("certs:securetoken", JSON.stringify(this.signer.certificates()));
  }

  /** Adds a Firebase Authentication account and returns a valid ID token for it. */
  async withAccount(
    uid: string,
    fields: { email: string; password?: string; displayName?: string; vendor?: boolean } = { email: "customer@example.com" },
  ): Promise<string> {
    this.accounts.set(uid, {
      email: fields.email,
      password: fields.password ?? "password-1",
      displayName: fields.displayName ?? "",
    });
    const refreshToken = `refresh-${uid}`;
    this.refreshTokens.set(refreshToken, uid);
    return this.signer.idToken({
      sub: uid,
      user_id: uid,
      email: fields.email,
      email_verified: false,
      ...(fields.vendor ? { vendor: true } : {}),
    });
  }

  /** `orders/abc` with plain JavaScript field values. */
  setDocument(path: string, fields: Record<string, unknown>): this {
    this.docs.set(path, { ...fields });
    return this;
  }

  setRtdb(path: string, value: unknown): this {
    this.rtdb.set(path, value);
    return this;
  }

  /** Declares that the project has the composite index a query needs. */
  withIndex(collectionId: string, ...fields: string[]): this {
    this.compositeIndexes.add(`${collectionId}:${fields.join(",")}`);
    return this;
  }

  get env(): Env {
    const kv = this.kv;
    return {
      UNDAL_KV: {
        get: async (key: string, type?: string) => {
          const value = kv.get(key);
          if (value === undefined) return null;
          return type === "json" ? JSON.parse(value) : value;
        },
        put: async (key: string, value: string) => {
          kv.set(key, value);
        },
        delete: async (key: string) => {
          kv.delete(key);
        },
      },
      FIREBASE_SA_JSON: JSON.stringify({
        project_id: FAKE_PROJECT,
        client_email: `fake@${FAKE_PROJECT}.iam.gserviceaccount.com`,
        private_key: "-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----\n",
      }),
      FIREBASE_RTDB_URL: FAKE_RTDB_URL,
      APP_SIGNING_SHA256: "",
      RELEASE_WEBHOOK_SECRET: "",
      CLOUDINARY_CLOUD_NAME: "fake",
      APP_PACKAGE_NAME: "com.undal.syl.app",
      GITHUB_REPO_SLUG: "bsdc-info-bd/undal",
      PROMO_TOPIC: "promos_all",
      FIREBASE_WEB_API_KEY: "fake-web-api-key",
    } as unknown as Env;
  }

  /** Installs this backend as `globalThis.fetch`; returns the restore function. */
  install(): () => void {
    const original = globalThis.fetch;
    globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) =>
      Promise.resolve(this.respond(String(typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url), init ?? {}))) as typeof fetch;
    return () => {
      globalThis.fetch = original;
    };
  }

  private async respond(url: string, init: RequestInit): Promise<Response> {
    const parsed = new URL(url);
    const method = (init.method ?? "GET").toUpperCase();
    const path = parsed.pathname;
    this.log.push(`${method} ${parsed.host}${path}`);
    const bodyText = bodyTextOf(init.body);

    if (parsed.host === "firestore.googleapis.com") {
      if (path.endsWith(":runQuery")) return this.runQuery(bodyText, documentParent(path));
      if (path.endsWith(":commit")) return this.commit(bodyText);
      if (method === "GET") return this.getDocument(path);
      if (method === "PATCH") return this.patchDocument(path, parsed, bodyText);
      if (method === "DELETE") return this.deleteDocument(path);
      return new Response(JSON.stringify({ error: { code: 400, message: `Unhandled fake route ${path}`, status: "INVALID_ARGUMENT" } }), { status: 400 });
    }

    if (parsed.host.endsWith("firebasedatabase.app")) return this.rtdbRequest(method, parsed, bodyText);

    if (parsed.host === "identitytoolkit.googleapis.com") {
      return this.identityToolkit(path.replace(/^\/v1\//, ""), bodyText);
    }
    if (parsed.host === "securetoken.googleapis.com") return this.secureToken(bodyText);
    if (parsed.host === "fcm.googleapis.com") return this.fcmSend(path, bodyText);

    if (parsed.host === "www.googleapis.com") {
      // The certificate endpoint, for a test that did not seed the KV cache.
      return Response.json(this.signer.certificates(), {
        headers: { "cache-control": "public, max-age=3600" },
      });
    }

    return new Response(`unexpected host ${parsed.host}`, { status: 500 });
  }

  private getDocument(path: string): Response {
    const relative = path.replace(/^\/v1\/projects\/[^/]+\/databases\/\(default\)\/documents\//, "");
    const fields = this.docs.get(relative);
    if (!fields) return new Response(JSON.stringify({ error: { code: 404, message: "No document to update.", status: "NOT_FOUND" } }), { status: 404 });
    return Response.json({
      name: DOC_PREFIX + relative,
      fields: Object.fromEntries(Object.entries(fields).map(([key, value]) => [key, toRestValue(value)])),
    });
  }

  /** `PATCH /documents/{path}?updateMask.fieldPaths=name` — create or merge. */
  private patchDocument(path: string, parsed: URL, bodyText: string): Response {
    const relative = path.replace(/^\/v1\/projects\/[^/]+\/databases\/\(default\)\/documents\//, "");
    const exists = this.docs.has(relative);
    if (parsed.searchParams.get("currentDocument.exists") === "false" && exists) {
      return new Response(JSON.stringify({ error: { code: 409, message: "Document already exists.", status: "ALREADY_EXISTS" } }), { status: 409 });
    }
    const body = JSON.parse(bodyText || "{}") as { fields?: Record<string, Record<string, unknown>> };
    const fields = Object.fromEntries(
      Object.entries(body.fields ?? {}).map(([key, value]) => [key, fromRestValue(value)]),
    );
    this.writes.push({ path: relative, fields, serverTimestamps: [] });
    this.docs.set(relative, { ...(this.docs.get(relative) ?? {}), ...fields });
    return Response.json({
      name: DOC_PREFIX + relative,
      fields: Object.fromEntries(Object.entries(this.docs.get(relative) ?? {}).map(([key, value]) => [key, toRestValue(value)])),
      updateTime: new Date().toISOString(),
    });
  }

  private deleteDocument(path: string): Response {
    const relative = path.replace(/^\/v1\/projects\/[^/]+\/databases\/\(default\)\/documents\//, "");
    this.docs.delete(relative);
    return Response.json({});
  }

  /** Writes `value` at `a.b.c`, creating the maps on the way down. */
  private static setField(document: Record<string, unknown>, fieldPath: string, value: unknown): void {
    const parts = fieldPath.split(".");
    let cursor = document;
    for (const part of parts.slice(0, -1)) {
      const nested = cursor[part];
      cursor[part] = nested && typeof nested === "object" && !Array.isArray(nested)
        ? (nested as Record<string, unknown>)
        : {};
      cursor = cursor[part] as Record<string, unknown>;
    }
    cursor[parts[parts.length - 1] ?? fieldPath] = value;
  }

  // ------------------------------------------------------- Firebase Auth

  /** Resolves an ID token to its uid without verifying: the fake issued it. */
  private uidOfToken(idToken: string): string | null {
    const claims = decodeJwtPayload<{ sub?: string; user_id?: string }>(idToken ?? "");
    const uid = claims?.sub || claims?.user_id || "";
    return uid && this.accounts.has(uid) ? uid : null;
  }

  private authRefusal(status: number, message: string): Response {
    return new Response(JSON.stringify({ error: { code: status, message, status: "INVALID_ARGUMENT" } }), { status });
  }

  private async issueSession(uid: string): Promise<Record<string, unknown>> {
    const account = this.accounts.get(uid);
    const refreshToken = `refresh-${uid}`;
    this.refreshTokens.set(refreshToken, uid);
    return {
      localId: uid,
      email: account?.email ?? "",
      displayName: account?.displayName ?? "",
      idToken: await this.signer.idToken({
        sub: uid,
        user_id: uid,
        email: account?.email ?? "",
        email_verified: false,
      }),
      refreshToken,
      expiresIn: "3600",
    };
  }

  private async identityToolkit(route: string, bodyText: string): Promise<Response> {
    this.authLog.push(route);
    if (this.authStatus !== 200) return this.authRefusal(this.authStatus, "SERVICE_UNAVAILABLE : Firebase Authentication is refusing.");
    const body = JSON.parse(bodyText || "{}") as Record<string, unknown>;

    if (route === "accounts:signUp") {
      const email = String(body.email ?? "").toLowerCase();
      const password = String(body.password ?? "");
      if (password.length < 6) return this.authRefusal(400, "WEAK_PASSWORD : Password should be at least 6 characters");
      if ([...this.accounts.values()].some((account) => account.email === email)) {
        return this.authRefusal(400, "EMAIL_EXISTS : The email address is already in use by another account.");
      }
      this.accountSequence += 1;
      const uid = `uid-${this.accountSequence}`;
      this.accounts.set(uid, { email, password, displayName: "" });
      return Response.json(await this.issueSession(uid));
    }

    if (route === "accounts:update") {
      const uid = this.uidOfToken(String(body.idToken ?? ""));
      if (!uid) return this.authRefusal(400, "INVALID_ID_TOKEN : The ID token is not valid.");
      const account = this.accounts.get(uid);
      if (account) {
        if (typeof body.displayName === "string") account.displayName = body.displayName;
        if (typeof body.password === "string") {
          if (body.password.length < 6) return this.authRefusal(400, "WEAK_PASSWORD : Password should be at least 6 characters");
          account.password = body.password;
        }
      }
      const session = await this.issueSession(uid);
      return Response.json(body.returnSecureToken === true ? session : { localId: uid, email: session.email });
    }

    if (route === "accounts:sendOobCode") {
      const email = String(body.email ?? "").toLowerCase();
      if (![...this.accounts.values()].some((account) => account.email === email)) {
        return this.authRefusal(400, "EMAIL_NOT_FOUND : There is no user record corresponding to this identifier.");
      }
      this.resetEmails.push(email);
      return Response.json({ email });
    }

    if (route === "accounts:lookup") {
      const uid = this.uidOfToken(String(body.idToken ?? ""));
      if (!uid) return this.authRefusal(400, "INVALID_ID_TOKEN : The ID token is not valid.");
      const account = this.accounts.get(uid);
      return Response.json({
        users: [{ localId: uid, email: account?.email ?? "", displayName: account?.displayName ?? "", emailVerified: false }],
      });
    }

    if (route === "accounts:delete") {
      const uid = this.uidOfToken(String(body.idToken ?? ""));
      if (!uid) return this.authRefusal(400, "INVALID_ID_TOKEN : The ID token is not valid.");
      this.accounts.delete(uid);
      this.refreshTokens.delete(`refresh-${uid}`);
      return Response.json({});
    }

    return this.authRefusal(400, `OPERATION_NOT_ALLOWED : The fake has no route ${route}.`);
  }

  private async secureToken(bodyText: string): Promise<Response> {
    this.authLog.push("securetoken:token");
    if (this.authStatus !== 200) return this.authRefusal(this.authStatus, "SERVICE_UNAVAILABLE");
    const form = new URLSearchParams(bodyText);
    const refreshToken = form.get("refresh_token") ?? "";
    const uid = this.refreshTokens.get(refreshToken);
    if (form.get("grant_type") !== "refresh_token" || !uid || !this.accounts.has(uid)) {
      return this.authRefusal(400, "TOKEN_EXPIRED : The refresh token has expired.");
    }
    const session = await this.issueSession(uid);
    return Response.json({
      access_token: `access-${uid}`,
      expires_in: "3600",
      token_type: "Bearer",
      refresh_token: refreshToken,
      id_token: session.idToken,
      user_id: uid,
      project_id: FAKE_PROJECT,
    });
  }

  /** `POST /v1/projects/{p}/messages:send` — recorded, then accepted or refused. */
  private fcmSend(path: string, bodyText: string): Response {
    const message = JSON.parse(bodyText || "{}") as Record<string, any>;
    this.fcmSends.push(message);

    let refuse: boolean;
    if (this.fcmFailFirst > 0) {
      this.fcmFailFirst -= 1;
      refuse = true;
    } else {
      refuse = this.fcmRefuseAll;
    }
    if (!refuse) {
      return Response.json({ name: `${path.replace(":send", "")}/${this.fcmSends.length}` });
    }

    const status = this.fcmStatus;
    return new Response(JSON.stringify({
      error: {
        status: status === 404 ? "NOT_FOUND" : "INVALID_ARGUMENT",
        message: `${this.fcmErrorCode}: the fake refused this send`,
        details: [{ errorCode: this.fcmErrorCode }],
      },
    }), { status });
  }

  private runQuery(bodyText: string, parent: string): Response {
    if (this.transientQueryFailures > 0) {
      this.transientQueryFailures -= 1;
      return new Response(JSON.stringify({ error: { code: 503, message: "Backend Error.", status: "UNAVAILABLE" } }), { status: 503 });
    }
    const body = JSON.parse(bodyText || "{}") as {
      structuredQuery?: {
        from?: Array<{ collectionId?: string }>;
        where?: FieldFilter;
        orderBy?: Array<{ field?: { fieldPath?: string }; direction?: string }>;
        limit?: number;
      };
    };
    const query = body.structuredQuery ?? {};
    const collectionId = query.from?.[0]?.collectionId ?? "";
    const filters = flattenFilters(query.where);
    const orderBy = query.orderBy ?? [];

    // The rule Firestore really enforces: a query that touches more than one
    // field across its filter and its ordering needs a composite index. One
    // field — including a lone `orderBy` — is served by the single-field index
    // Firestore builds for every field automatically, which is exactly the hole
    // the cleanup's scan fallback climbs through.
    const fields = [...new Set([...filters.map((filter) => filter.field), ...orderBy.map((clause) => clause.field?.fieldPath ?? "")])].filter(Boolean);
    if (fields.length > 1 && !this.hasCompositeIndex(collectionId, fields)) {
      return new Response(INDEX_REFUSAL(INDEX_CONSOLE_LINK), { status: 400 });
    }

    // The URL carries the *parent document* and the body carries the collection:
    // `/documents:runQuery` with `from: orders` for a root collection,
    // `/documents/notifInbox/{uid}:runQuery` with `from: items` for a
    // subcollection. Both resolve to the same document-key prefix here.
    const prefix = `${parent ? `${parent}/` : ""}${collectionId}/`;
    let rows = [...this.docs.entries()]
      .filter(([path]) => path.startsWith(prefix) && path.slice(prefix.length).indexOf("/") === -1)
      .map(([path, fieldsOfRow]) => ({ path, fields: fieldsOfRow }));

    for (const filter of filters) rows = rows.filter((row) => matches(row.fields, filter));
    if (orderBy.length > 0) {
      const clause = orderBy[0];
      const field = clause.field?.fieldPath ?? "";
      const direction = clause.direction === "DESCENDING" ? -1 : 1;
      rows = rows.filter((row) => row.fields[field] !== undefined)
        .sort((left, right) => compareValues(left.fields[field], right.fields[field]) * direction);
    }
    if (typeof query.limit === "number") rows = rows.slice(0, query.limit);

    return Response.json(rows.map((row) => ({
      document: {
        name: DOC_PREFIX + row.path,
        fields: Object.fromEntries(Object.entries(row.fields).map(([key, value]) => [key, toRestValue(value)])),
      },
    })));
  }

  /** Field order does not matter to this fake, only the set of fields. */
  private hasCompositeIndex(collectionId: string, fields: string[]): boolean {
    const wanted = [...fields].sort().join(",");
    for (const key of this.compositeIndexes) {
      const [collection, list] = key.split(":");
      if (collection === collectionId && (list ?? "").split(",").sort().join(",") === wanted) return true;
    }
    return false;
  }

  private commit(bodyText: string): Response {
    const body = JSON.parse(bodyText || "{}") as {
      writes?: Array<{
        delete?: string;
        update?: { name?: string; fields?: Record<string, Record<string, unknown>> };
        updateMask?: { fieldPaths?: string[] };
        updateTransforms?: Array<{ fieldPath?: string; setToServerValue?: string }>;
      }>;
    };
    const commitTime = new Date().toISOString();
    this.commits.push(body);
    for (const write of body.writes ?? []) {
      if (write.delete) {
        this.docs.delete(write.delete.replace(DOC_PREFIX, ""));
        continue;
      }
      const update = write.update;
      if (!update?.name) continue;
      const relative = update.name.replace(DOC_PREFIX, "");
      const document = { ...(this.docs.get(relative) ?? {}) };
      // What the Worker asked to write, as opposed to what the document holds
      // afterwards: a test asserting "role was never touched" needs the first.
      const sent: Record<string, unknown> = {};
      const mask = write.updateMask?.fieldPaths ?? Object.keys(update.fields ?? {});
      for (const fieldPath of mask) {
        const value = update.fields?.[fieldPath];
        if (value === undefined) continue;
        const plain = fromRestValue(value);
        FakeFirebase.setField(document, fieldPath, plain);
        FakeFirebase.setField(sent, fieldPath, plain);
      }
      // A server-timestamp transform is applied after the merge, exactly as
      // Firestore applies it, and lands as the commit's own time.
      const transforms = (write.updateTransforms ?? []).map((transform) => transform.fieldPath ?? "");
      for (const fieldPath of transforms) {
        if (!fieldPath) continue;
        FakeFirebase.setField(document, fieldPath, commitTime);
        FakeFirebase.setField(sent, fieldPath, commitTime);
      }
      this.writes.push({ path: relative, fields: sent, serverTimestamps: transforms.filter(Boolean) });
      this.docs.set(relative, document);
    }
    return Response.json({ commitTime, writeResults: (body.writes ?? []).map(() => ({})) });
  }

  private rtdbRequest(method: string, parsed: URL, bodyText: string): Response {
    const relative = parsed.pathname.replace(/\.json$/, "").replace(/^\//, "");
    const shallow = parsed.searchParams.get("shallow") === "true";
    if (this.rtdbStatus !== 200) {
      return new Response(JSON.stringify({ error: this.rtdbStatus === 404 ? "Not Found" : "Permission denied" }), { status: this.rtdbStatus });
    }
    if (method === "PATCH") {
      const patch = JSON.parse(bodyText || "{}") as Record<string, unknown>;
      this.rtdbPatches.push(patch);
      for (const [key, value] of Object.entries(patch)) {
        if (value === null) this.rtdb.delete(key);
        else this.rtdb.set(key, value);
      }
      return Response.json(patch);
    }
    if (method === "PUT") {
      this.rtdb.set(relative, JSON.parse(bodyText || "null"));
      return new Response(bodyText, { status: 200 });
    }
    // GET, including the shallow reads: top-level keys only, which is all the
    // diagnosis asks for.
    const value = relative === ""
      ? Object.fromEntries([...this.rtdb.keys()].map((key) => [key.split("/")[0], true]))
      : shallow
        ? Object.fromEntries([...this.rtdb.keys()].filter((key) => key.startsWith(`${relative}/`)).map((key) => [key.slice(relative.length + 1).split("/")[0], true]))
        : Object.fromEntries([...this.rtdb.entries()].filter(([key]) => key.startsWith(`${relative}/`)).map(([key, item]) => [key.slice(relative.length + 1), item]));
    return new Response(Object.keys(value).length === 0 ? "null" : JSON.stringify(value), { status: 200 });
  }
}

/** An order document shaped the way the app writes it. */
export function orderFields(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    shortId: "UND-1001",
    status: "delivered",
    customerId: "customer-1",
    totalAmountPaisa: 45000,
    updatedAt: new Date(Date.now() - 86_400_000).toISOString(),
    createdAt: new Date(Date.now() - 86_400_000).toISOString(),
    ...overrides,
  };
}
