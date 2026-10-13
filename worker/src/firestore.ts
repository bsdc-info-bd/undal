/**
 * Firestore REST access.
 *
 * The Android app is the system of record through the Firebase SDK; the Worker
 * only needs reads (store config, menu, orders, tokens) and a handful of
 * server-side writes (promo rotation, auto-offline, token purge), so the REST
 * surface here stays deliberately small.
 */
import type { Env } from "./env";
import { projectId } from "./env";
import { accessToken, invalidateAccessToken } from "./gcp";
import { HttpError } from "./util/http";

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

async function authorizedFetch(env: Env, url: string, init: RequestInit = {}): Promise<Response> {
  const token = await accessToken(env);
  const headers = new Headers(init.headers);
  headers.set("authorization", `Bearer ${token}`);
  headers.set("content-type", "application/json");
  const first = await fetch(url, { ...init, headers });
  if (first.status !== 401) return first;
  await invalidateAccessToken(env);
  const retryHeaders = new Headers(init.headers);
  retryHeaders.set("authorization", `Bearer ${await accessToken(env)}`);
  retryHeaders.set("content-type", "application/json");
  return fetch(url, { ...init, headers: retryHeaders });
}

/** Reads a single document; returns null when it does not exist. */
export async function fsGet(env: Env, path: string): Promise<FsRecord | null> {
  const response = await authorizedFetch(env, documentsUrl(env, path));
  if (response.status === 404) return null;
  if (!response.ok) {
    throw new HttpError(502, "firestore-read-failed", `${path} (${response.status})`);
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
  const response = await authorizedFetch(env, `${documentsUrl(env, path)}${query ? `?${query}` : ""}`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new HttpError(502, "firestore-write-failed", `${path} (${response.status})`);
  }
}

/** Deletes a document; a missing document is not an error. */
export async function fsDelete(env: Env, path: string): Promise<void> {
  const response = await authorizedFetch(env, documentsUrl(env, path), { method: "DELETE" });
  if (!response.ok && response.status !== 404) {
    throw new HttpError(502, "firestore-delete-failed", `${path} (${response.status})`);
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
  );
  if (!response.ok) {
    throw new HttpError(502, "firestore-commit-failed", `${path} (${response.status})`);
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

/** Runs a structured query and returns plain records. */
export async function fsQuery(env: Env, query: FsQuery): Promise<FsRecord[]> {
  const filters = (query.where ?? []).map((clause) => ({
    fieldFilter: {
      field: { fieldPath: clause.field },
      op: clause.op,
      value: toFsValue(clause.value),
    },
  }));

  const structuredQuery: Record<string, unknown> = {
    from: [{ collectionId: query.collectionId }],
    parent: `projects/${projectId(env)}/databases/(default)/documents${query.parentPath ? `/${query.parentPath}` : ""}`,
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
    `https://firestore.googleapis.com/v1/projects/${projectId(env)}/databases/(default)/documents:runQuery`,
    { method: "POST", body: JSON.stringify({ structuredQuery }) },
  );
  if (!response.ok) {
    throw new HttpError(502, "firestore-query-failed", `${query.collectionId} (${response.status})`);
  }
  const payload = (await response.json()) as Array<{ document?: FsRestDocument }>;
  return payload
    .filter((entry) => entry.document !== undefined)
    .map((entry) => fromFsDocument(entry.document as FsRestDocument));
}
