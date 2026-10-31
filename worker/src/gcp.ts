/**
 * Google Cloud authentication for the Worker.
 *
 * A single service-account JWT (RS256, signed with Web Crypto) is exchanged for
 * an OAuth2 access token that is cached in KV for ~50 minutes. Every Google API
 * call in this Worker — FCM HTTP v1, Firestore REST, Realtime Database REST —
 * uses that token. No legacy server key exists anywhere in this codebase.
 */
import type { Env } from "./env";
import { serviceAccount } from "./env";
import { base64UrlToBytes, bytesToBase64Url, pemPrivateKeyToPkcs8, utf8 } from "./util/codec";
import { memoDelete, memoGet, memoPut, singleFlight, type KvBinding } from "./util/kv";

const OAUTH_SCOPE = [
  "https://www.googleapis.com/auth/cloud-platform",
  "https://www.googleapis.com/auth/firebase.database",
  "https://www.googleapis.com/auth/firebase.messaging",
  "https://www.googleapis.com/auth/datastore",
].join(" ");

const TOKEN_CACHE_KEY = "oauth:token";
const TOKEN_SAFETY_SECONDS = 300;
/**
 * How long an isolate trusts the token it read from KV without asking again.
 *
 * This was one KV read per Google API call — a single storefront page view spent
 * eight of them, because the menu snapshot is seven Firestore reads plus a write.
 * The token itself lives ~55 minutes, and the only way it goes bad early is a
 * Google-side revocation, which answers 401; `invalidateAccessToken` drops the
 * isolate copy at the same moment it drops the KV one, so the next call re-mints
 * instead of serving a token that has already been refused.
 */
const TOKEN_MEMO_TTL_MS = 5 * 60_000;
/**
 * Google publishes these certificates with their own `max-age` (usually an hour)
 * and rotates them on that schedule. Inside an isolate they are trusted for five
 * minutes: long enough that verifying a hundred ID tokens costs one KV read,
 * short enough that a rotation is picked up while the isolate is still warm.
 */
const CERT_MEMO_TTL_MS = 5 * 60_000;

async function signJwt(env: Env, claims: Record<string, unknown>): Promise<string> {
  const account = serviceAccount(env);
  const header = { alg: "RS256", typ: "JWT", kid: account.client_email };
  const headerPart = bytesToBase64Url(utf8(JSON.stringify(header)));
  const claimsPart = bytesToBase64Url(utf8(JSON.stringify(claims)));
  const signingInput = `${headerPart}.${claimsPart}`;

  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemPrivateKeyToPkcs8(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, utf8(signingInput));
  return `${signingInput}.${bytesToBase64Url(new Uint8Array(signature))}`;
}

/** Signs the service-account JWT and exchanges it at Google's token endpoint. */
async function mintAccessToken(env: Env): Promise<{ token: string; ttlSeconds: number }> {
  const account = serviceAccount(env);
  const now = Math.floor(Date.now() / 1000);
  const tokenUri = account.token_uri ?? "https://oauth2.googleapis.com/token";
  const assertion = await signJwt(env, {
    iss: account.client_email,
    scope: OAUTH_SCOPE,
    aud: tokenUri,
    iat: now - 30,
    exp: now + 3540,
  });

  const response = await fetch(tokenUri, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });

  if (!response.ok) {
    const detail = await response.text();
    throw new Error(`oauth2 token exchange failed (${response.status}): ${detail.slice(0, 400)}`);
  }

  const payload = (await response.json()) as { access_token?: string; expires_in?: number };
  if (!payload.access_token) throw new Error("oauth2 response carried no access_token");
  return {
    token: payload.access_token,
    ttlSeconds: Math.max(60, (payload.expires_in ?? 3600) - TOKEN_SAFETY_SECONDS),
  };
}

/**
 * Mints (or returns the cached) OAuth2 access token.
 *
 * Isolate memory first, KV second, one mint in flight at a time. A page view that
 * reads the menu used to spend eight KV reads on this one key.
 */
export async function accessToken(env: Env): Promise<string> {
  const binding = env.UNDAL_KV as KvBinding;
  return singleFlight(binding, TOKEN_CACHE_KEY, async () => {
    const cached = await memoGet<string>(binding, TOKEN_CACHE_KEY, { ttlMs: TOKEN_MEMO_TTL_MS });
    if (cached && cached.length > 20) return cached;

    const minted = await mintAccessToken(env);
    // Required: an isolate that cannot read a token cannot call Google at all, so
    // this write is never one the brake is allowed to refuse.
    await memoPut(binding, TOKEN_CACHE_KEY, minted.token, {
      expirationTtl: minted.ttlSeconds,
      memoTtlMs: minted.ttlSeconds * 1000,
      required: true,
    });
    return minted.token;
  });
}

/** Drops the cached token so the next call re-mints it (used on 401 responses). */
export async function invalidateAccessToken(env: Env): Promise<void> {
  // Both tiers, or the isolate keeps using the token Google just refused.
  await memoDelete(env.UNDAL_KV as KvBinding, TOKEN_CACHE_KEY, { required: true });
}

/**
 * Google rotates the X.509 certificates that sign Firebase ID tokens. They are
 * cached in KV for the lifetime the endpoint's Cache-Control advertises.
 */
export async function fetchSecureTokenCertificates(env: Env): Promise<Record<string, string>> {
  const cacheKey = "certs:securetoken";
  const binding = env.UNDAL_KV as KvBinding;
  const cached = await memoGet<Record<string, string>>(binding, cacheKey, {
    ttlMs: CERT_MEMO_TTL_MS,
    json: true,
  });
  if (cached) return cached;

  const response = await fetch(
    "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com",
  );
  if (!response.ok) throw new Error(`securetoken certificates unavailable (${response.status})`);
  const certificates = (await response.json()) as Record<string, string>;

  const cacheControl = response.headers.get("cache-control") ?? "";
  const maxAgeMatch = /max-age=(\d+)/.exec(cacheControl);
  const ttl = maxAgeMatch?.[1] ? Math.max(300, Number(maxAgeMatch[1])) : 3600;

  await memoPut(binding, cacheKey, JSON.stringify(certificates), {
    expirationTtl: ttl,
    memoTtlMs: ttl * 1000,
    memoValue: certificates,
    required: true,
  });
  return certificates;
}

/** Exposed for tests and diagnostics: decodes a JWT payload without verifying. */
export function decodeJwtPayload<T>(token: string): T | null {
  const parts = token.split(".");
  if (parts.length !== 3 || !parts[1]) return null;
  try {
    return JSON.parse(new TextDecoder().decode(base64UrlToBytes(parts[1]))) as T;
  } catch {
    return null;
  }
}
