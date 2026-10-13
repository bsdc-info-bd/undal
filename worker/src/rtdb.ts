/**
 * Realtime Database REST access.
 *
 * Used by the crons (vendor presence) and by the order-status relay (mirroring
 * sub-second flags). Chat itself is written by the apps directly through the
 * Firebase SDK; the Worker only reads the last message for push previews.
 */
import type { Env } from "./env";
import { rtdbOrigin } from "./env";
import { accessToken } from "./gcp";
import { HttpError } from "./util/http";

function urlFor(env: Env, path: string, token: string): string {
  const clean = path.replace(/^\/+|\/+$/g, "");
  return `${rtdbOrigin(env)}/${clean}.json?access_token=${encodeURIComponent(token)}`;
}

export async function rtdbGet<T>(env: Env, path: string): Promise<T | null> {
  const token = await accessToken(env);
  const response = await fetch(urlFor(env, path, token));
  if (!response.ok) {
    throw new HttpError(502, "rtdb-read-failed", `${path} (${response.status})`);
  }
  const text = await response.text();
  if (text === "null" || text.length === 0) return null;
  return JSON.parse(text) as T;
}

/** Shallow GET is enough for the heartbeat and for cheap existence checks. */
export async function rtdbGetShallow<T>(env: Env, path: string): Promise<T | null> {
  const token = await accessToken(env);
  const response = await fetch(`${urlFor(env, path, token)}&shallow=true`);
  if (!response.ok) {
    throw new HttpError(502, "rtdb-read-failed", `${path} (${response.status})`);
  }
  const text = await response.text();
  if (text === "null" || text.length === 0) return null;
  return JSON.parse(text) as T;
}

export async function rtdbSet(env: Env, path: string, value: unknown): Promise<void> {
  const token = await accessToken(env);
  const response = await fetch(urlFor(env, path, token), {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(value),
  });
  if (!response.ok) {
    throw new HttpError(502, "rtdb-write-failed", `${path} (${response.status})`);
  }
}

export async function rtdbUpdate(env: Env, path: string, value: Record<string, unknown>): Promise<void> {
  const token = await accessToken(env);
  const response = await fetch(urlFor(env, path, token), {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(value),
  });
  if (!response.ok) {
    throw new HttpError(502, "rtdb-update-failed", `${path} (${response.status})`);
  }
}
