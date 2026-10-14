/**
 * Realtime Database REST access.
 *
 * Used by the crons (vendor presence) and by the order-status relay (mirroring
 * sub-second flags). Chat itself is written by the apps directly through the
 * Firebase SDK; the Worker only reads the last message for push previews.
 *
 * The mirrors this file writes are a **cache of Firestore, never the record of
 * it**: the apps re-read Firestore when a mirror is missing. That is why every
 * write here can be treated as best-effort by its caller, and why a refusal is
 * reported with the reason attached instead of being turned into a failure of
 * whatever bigger job was running — a history cleanup that could not delete two
 * RTDB mirrors still deleted the orders, and saying so is the honest answer.
 */
import type { Env } from "./env";
import { rtdbOrigin } from "./env";
import { accessToken } from "./gcp";
import { DELETE_RETRY, READ_RETRY, WRITE_RETRY, fetchWithRetry, isRefusedBeforeApplying, type RetryPolicy } from "./util/retry";
import { classifyUpstreamFailure, upstreamSentence, type UpstreamFailure } from "./util/upstream";
import { HttpError } from "./util/http";

/**
 * A Realtime Database refusal with Google's own answer attached.
 *
 * The three shapes seen in production are a database that does not exist (404,
 * usually `FIREBASE_RTDB_URL` from another project), a credential without
 * access (401/403, usually a service account from before the database was
 * created) and a rule that says no — the last of which cannot happen with an
 * admin token, which is exactly why the message says so rather than blaming
 * the rules.
 */
export class RtdbError extends HttpError {
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
      },
    });
    this.failure = failure;
  }
}

function urlFor(env: Env, path: string, token: string): string {
  const clean = path.replace(/^\/+|\/+$/g, "");
  return `${rtdbOrigin(env)}/${clean}.json?access_token=${encodeURIComponent(token)}`;
}

async function authorized(
  env: Env,
  path: string,
  init: RequestInit,
  options: { policy?: RetryPolicy; label?: string; retryStatus?: (status: number) => boolean } = {},
): Promise<Response> {
  const token = await accessToken(env);
  return fetchWithRetry(urlFor(env, path, token), {
    headers: { "content-type": "application/json" },
    ...init,
  }, { policy: options.policy ?? READ_RETRY, label: options.label, retryStatus: options.retryStatus });
}

async function failureOf(response: Response): Promise<UpstreamFailure> {
  const body = await response.text().catch(() => "");
  return classifyUpstreamFailure(response.status, body);
}

function parsePayload<T>(text: string): T | null {
  if (text === "null" || text.length === 0) return null;
  return JSON.parse(text) as T;
}

export async function rtdbGet<T>(env: Env, path: string): Promise<T | null> {
  const response = await authorized(env, path, {}, { label: "rtdb-get" });
  if (!response.ok) {
    throw new RtdbError(`the live mirror at ${path || "the database root"}`, "rtdb-read-failed", await failureOf(response));
  }
  return parsePayload<T>(await response.text());
}

/** Shallow GET is enough for the heartbeat and for cheap existence checks. */
export async function rtdbGetShallow<T>(env: Env, path: string): Promise<T | null> {
  const token = await accessToken(env);
  const response = await fetchWithRetry(`${urlFor(env, path, token)}&shallow=true`, {}, {
    policy: READ_RETRY,
    label: "rtdb-get-shallow",
  });
  if (!response.ok) {
    throw new RtdbError(`the live mirror at ${path || "the database root"}`, "rtdb-read-failed", await failureOf(response));
  }
  return parsePayload<T>(await response.text());
}

export async function rtdbSet(env: Env, path: string, value: unknown): Promise<void> {
  const response = await authorized(
    env,
    path,
    { method: "PUT", body: JSON.stringify(value) },
    { policy: WRITE_RETRY, label: "rtdb-set", retryStatus: isRefusedBeforeApplying },
  );
  if (!response.ok) {
    throw new RtdbError(`the live mirror at ${path}`, "rtdb-write-failed", await failureOf(response));
  }
}

export async function rtdbUpdate(env: Env, path: string, value: Record<string, unknown>): Promise<void> {
  const response = await authorized(
    env,
    path,
    { method: "PATCH", body: JSON.stringify(value) },
    // A multi-path PATCH of nulls is a delete, and a delete repeated is a no-op.
    { policy: DELETE_RETRY, label: "rtdb-update" },
  );
  if (!response.ok) {
    throw new RtdbError(`the live mirrors under ${path || "the database root"}`, "rtdb-update-failed", await failureOf(response));
  }
}
