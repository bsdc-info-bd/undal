/**
 * Firestore REST access.
 *
 * The Android app is the system of record through the Firebase SDK; the Worker
 * only needs reads (store config, menu, orders, tokens) and a handful of
 * server-side writes (promo rotation, auto-offline, token purge), so the REST
 * surface here stays deliberately small.
 *
 * Two things this file refuses to do, both learned from a real failure:
 *
 *   · **swallow the reason.** Every refusal is read, classified by
 *     `util/upstream.ts` and rethrown as a [FirestoreError] carrying Google's own
 *     status, message and — for a missing composite index — the console link that
 *     creates it. A route can then tell a merchant what to do.
 *   · **retry a write that may have landed.** Reads, queries and deletes are
 *     retried on a transient status; creates and merges are retried only for a
 *     refusal that happens before the write is applied (429 and friends).
 */
import type { Env } from "./env";
import { projectId } from "./env";
import { accessToken, invalidateAccessToken } from "./gcp";
import { HttpError } from "./util/http";
import { DELETE_RETRY, READ_RETRY, WRITE_RETRY, fetchWithRetry, isRefusedBeforeApplying, type RetryPolicy } from "./util/retry";
import { classifyUpstreamFailure, upstreamSentence, type UpstreamFailure } from "./util/upstream";

export type FsRestValue =
  | { nullValue: null }
  | { booleanValue: boolean }
  | { integerValue: string }
  | { doubleValue: number }
  | { timestampValue: string }
  | { stringValue: string }
  | { arrayValue: { values?: FsRestValue[] } }
  | { mapValue: { fields?: Record<string, FsRestValue> } };

export interface FsRestDocument {
  name: string;
  fields?: Record<string, FsRestValue>;
  createTime?: string;
  updateTime?: string;
}

/**
 * A Firestore refusal with the upstream answer attached.
 *
 * Callers that have a fallback — the history cleanup does — inspect
 * `failure.needsIndex` and carry on. Everything else surfaces `hint`, which is
 * the sentence a human can act on.
 */
export class FirestoreError extends HttpError {
  readonly failure: UpstreamFailure;

  constructor(subject: string, code: string, failure: UpstreamFailure, status = 502) {
    const sentence = upstreamSentence(subject, failure);
    super(status, code, sentence.message, {
      hint: sentence.hint,
      detail: {
        subject,
        upstreamStatus: failure.httpStatus,
        googleStatus: failure.googleStatus,
        upstreamMessage: failure.message,
        needsIndex: failure.needsIndex,
        ...(failure.indexUrl ? { indexUrl: failure.indexUrl } : {}),
      },
    });
    this.failure = failure;
  }
}

/** Reads the body of a failed response and classifies it. Never throws. */
async function failureOf(response: Response): Promise<UpstreamFailure> {
  const body = await response.text().catch(() => "");
  return classifyUpstreamFailure(response.status, body);
}

export function toFsValue(value: unknown): FsRestValue {
  if (value === null || value === undefined) return { nullValue: null };
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "number") {
    return Number.isInteger(value) ? { integerValue: String(value) } : { doubleValue: value };
  }
  if (typeof value === "string") return { stringValue: value };
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(toFsValue) } };
  if (typeof value === "object") {
    const fields: Record<string, FsRestValue> = {};
    for (const [key, nested] of Object.entries(value as Record<string, unknown>)) {
      if (nested === undefined) continue;
      fields[key] = toFsValue(nested);
    }
    return { mapValue: { fields } };
  }
  return { nullValue: null };
}

export function fromFsValue(value: FsRestValue | undefined): unknown {
  if (!value) return undefined;
  if ("nullValue" in value) return null;
  if ("booleanValue" in value) return value.booleanValue;
  if ("integerValue" in value) return Number(value.integerValue);
  if ("doubleValue" in value) return value.doubleValue;
  if ("timestampValue" in value) return value.timestampValue;
  if ("stringValue" in value) return value.stringValue;
  if ("arrayValue" in value) return (value.arrayValue.values ?? []).map((entry) => fromFsValue(entry));
  if ("mapValue" in value) {
    const out: Record<string, unknown> = {};
    for (const [key, nested] of Object.entries(value.mapValue.fields ?? {})) {
      out[key] = fromFsValue(nested);
    }
    return out;
  }
  return undefined;
}

export type FsRecord = { id: string; [key: string]: unknown };

export function fromFsDocument(document: FsRestDocument): FsRecord {
  const record: FsRecord = { id: document.name.split("/").pop() ?? "" };
  for (const [key, value] of Object.entries(document.fields ?? {})) {
    record[key] = fromFsValue(value);
  }
  return record;
}

function documentsUrl(env: Env, path: string): string {
  return `https://firestore.googleapis.com/v1/projects/${projectId(env)}/databases/(default)/documents/${path}`;
}

interface AuthorizedFetchOptions {
  policy?: RetryPolicy;
  label?: string;
  retryStatus?: (status: number) => boolean;
}

async function authorizedFetch(
  env: Env,
  url: string,
  init: RequestInit = {},
  options: AuthorizedFetchOptions = {},
): Promise<Response> {
  const call = (token: string): Promise<Response> => {
    const headers = new Headers(init.headers);
    headers.set("authorization", `Bearer ${token}`);
    headers.set("content-type", "application/json");
    return fetchWithRetry(url, { ...init, headers }, {
      policy: options.policy ?? READ_RETRY,
      label: options.label,
      retryStatus: options.retryStatus,
    });
  };

  const first = await call(await accessToken(env));
  // A 401 is never retried as a transient failure: it means the cached token
  // expired between the KV read and the call, and only a fresh one helps.
  if (first.status !== 401) return first;
  await invalidateAccessToken(env);
  return call(await accessToken(env));
}

/** Reads a single document; returns null when it does not exist. */
export async function fsGet(env: Env, path: string): Promise<FsRecord | null> {
  const response = await authorizedFetch(env, documentsUrl(env, path), {}, { label: "firestore-get" });
  if (response.status === 404) return null;
  if (!response.ok) {
    throw new FirestoreError(`the document ${path}`, "firestore-read-failed", await failureOf(response));
  }
  return fromFsDocument((await response.json()) as FsRestDocument);
}

/** Creates or merges a document. */
export async function fsPatch(env: Env, path: string, fields: Record<string, unknown>): Promise<void> {
  const fieldPaths = Object.keys(fields).filter((key) => fields[key] !== undefined);
  const query = fieldPaths.map((field) => `updateMask.fieldPaths=${encodeURIComponent(field)}`).join("&");
  const body = {
    fields: Object.fromEntries(
      fieldPaths.map((field) => [field, toFsValue(fields[field])]),
    ),
  };
  const response = await authorizedFetch(
    env,
    `${documentsUrl(env, path)}${query ? `?${query}` : ""}`,
    { method: "PATCH", body: JSON.stringify(body) },
    {
      // A merge may already have landed when Google answers 500, so only the
      // refusals that happen before the write are repeated.
      policy: WRITE_RETRY,
      label: "firestore-patch",
      retryStatus: isRefusedBeforeApplying,
    },
  );
  if (!response.ok) {
    throw new FirestoreError(`the write to ${path}`, "firestore-write-failed", await failureOf(response));
  }
}

/** Deletes a document; a missing document is not an error. */
export async function fsDelete(env: Env, path: string): Promise<void> {
  const response = await authorizedFetch(
    env,
    documentsUrl(env, path),
    { method: "DELETE" },
    { policy: DELETE_RETRY, label: "firestore-delete" },
  );
  if (!response.ok && response.status !== 404) {
    throw new FirestoreError(`the deletion of ${path}`, "firestore-delete-failed", await failureOf(response));
  }
}

/**
 * Writes fields while letting Firestore stamp `request.time` into the listed
 * server-timestamp fields — the same semantics as FieldValue.serverTimestamp().
 */
export async function fsPatchWithServerTimestamps(
  env: Env,
  path: string,
  fields: Record<string, unknown>,
  serverTimestampFields: string[],
): Promise<void> {
  const fieldPaths = Object.keys(fields).filter((key) => fields[key] !== undefined);
  const documentName = `projects/${projectId(env)}/databases/(default)/documents/${path}`;
  const write: Record<string, unknown> = {
    update: {
      name: documentName,
      fields: Object.fromEntries(fieldPaths.map((field) => [field, toFsValue(fields[field])])),
    },
    updateMask: { fieldPaths },
    updateTransforms: serverTimestampFields.map((fieldPath) => ({
      fieldPath,
      setToServerValue: "REQUEST_TIME",
    })),
  };
  const response = await authorizedFetch(
    env,
    `https://firestore.googleapis.com/v1/projects/${projectId(env)}/databases/(default)/documents:commit`,
    { method: "POST", body: JSON.stringify({ writes: [write] }) },
    { policy: WRITE_RETRY, label: "firestore-commit", retryStatus: isRefusedBeforeApplying },
  );
  if (!response.ok) {
    throw new FirestoreError(`the write to ${path}`, "firestore-commit-failed", await failureOf(response));
  }
}

export interface FsQuery {
  /** Document path whose subcollection is queried; omit for root collections. */
  parentPath?: string;
  collectionId: string;
  where?: Array<{ field: string; op: "EQUAL" | "NOT_EQUAL" | "LESS_THAN" | "LESS_THAN_OR_EQUAL" | "GREATER_THAN" | "GREATER_THAN_OR_EQUAL"; value: unknown }>;
  orderBy?: Array<{ field: string; direction?: "ASCENDING" | "DESCENDING" }>;
  limit?: number;
}

/**
 * Runs a structured query and returns plain records.
 *
 * A query Firestore cannot serve from an index is refused with 400
 * FAILED_PRECONDITION and a console link. That refusal arrives here as a
 * [FirestoreError] with `failure.needsIndex === true` so a caller with a
 * fallback — the history cleanup — can take it instead of failing a merchant's
 * request over a deployment step nobody remembered to run.
 */
export async function fsQuery(env: Env, query: FsQuery, subject?: string): Promise<FsRecord[]> {
  const filters = (query.where ?? []).map((clause) => ({
    fieldFilter: {
      field: { fieldPath: clause.field },
      op: clause.op,
      value: toFsValue(clause.value),
    },
  }));

  const structuredQuery: Record<string, unknown> = {
    from: [{ collectionId: query.collectionId }],
  };
  if (filters.length === 1) structuredQuery.where = filters[0];
  if (filters.length > 1) structuredQuery.where = { compositeFilter: { op: "AND", filters } };
  if (query.orderBy?.length) {
    structuredQuery.orderBy = query.orderBy.map((clause) => ({
      field: { fieldPath: clause.field },
      direction: clause.direction ?? "ASCENDING",
    }));
  }
  if (query.limit) structuredQuery.limit = query.limit;

  const response = await authorizedFetch(
    env,
    `https://firestore.googleapis.com/v1/projects/${projectId(env)}/databases/(default)/documents${query.parentPath ? `/${query.parentPath}` : ""}:runQuery`,
    { method: "POST", body: JSON.stringify({ structuredQuery }) },
    { label: "firestore-query" },
  );
  if (!response.ok) {
    const described = subject
      ?? `the ${query.collectionId} query${query.where?.length ? ` (${query.where.map((clause) => `${clause.field} ${clause.op}`).join(", ")})` : ""}`;
    throw new FirestoreError(described, "firestore-query-failed", await failureOf(response));
  }
  const payload = (await response.json()) as Array<{ document?: FsRestDocument }>;
  return payload
    .filter((entry) => entry.document !== undefined)
    .map((entry) => fromFsDocument(entry.document as FsRestDocument));
}

/** Idempotent server-authored inbox entry; retries never reset read state. */
export async function fsCreateIfAbsent(env: Env, path: string, fields: Record<string, unknown>): Promise<void> {
  const response = await authorizedFetch(
    env,
    `${documentsUrl(env, path)}?currentDocument.exists=false`,
    {
      method: "PATCH",
      body: JSON.stringify({ fields: Object.fromEntries(Object.entries(fields).map(([key, value]) => [key, toFsValue(value)])) }),
    },
    // `currentDocument.exists=false` makes the write itself the guard: a repeat
    // is refused with 409 rather than applied twice, so a retry is safe here.
    { policy: DELETE_RETRY, label: "firestore-create-if-absent" },
  );
  if (!response.ok && response.status !== 409) {
    throw new FirestoreError(`the creation of ${path}`, "firestore-create-failed", await failureOf(response));
  }
}

/**
 * Atomic bounded deletion; child collections must be explicitly supplied.
 *
 * Deleting a document that is already gone is a no-op in Firestore, so the whole
 * batch is safe to repeat — which is why it takes the delete retry budget.
 */
export async function fsDeleteBatch(env: Env, paths: string[]): Promise<void> {
  if (!paths.length) return;
  if (paths.length > 400) throw new HttpError(400, "delete-batch-too-large");
  const unique = [...new Set(paths)];
  const prefix = `projects/${projectId(env)}/databases/(default)/documents/`;
  const response = await authorizedFetch(
    env,
    `${documentsUrl(env, "").replace(/\/$/, "")}:commit`,
    { method: "POST", body: JSON.stringify({ writes: unique.map((path) => ({ delete: prefix + path })) }) },
    { policy: DELETE_RETRY, label: "firestore-delete-batch" },
  );
  if (!response.ok) {
    throw new FirestoreError(
      `the deletion of ${unique.length} document(s)`,
      "firestore-delete-batch-failed",
      await failureOf(response),
    );
  }
}
