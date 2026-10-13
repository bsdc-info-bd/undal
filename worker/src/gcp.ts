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

const OAUTH_SCOPE = [
  "https://www.googleapis.com/auth/cloud-platform",
  "https://www.googleapis.com/auth/firebase.database",
  "https://www.googleapis.com/auth/firebase.messaging",
  "https://www.googleapis.com/auth/datastore",
].join(" ");

const TOKEN_CACHE_KEY = "oauth:token";
const TOKEN_SAFETY_SECONDS = 300;

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

/** Mints (or returns the cached) OAuth2 access token. */
export async function accessToken(env: Env): Promise<string> {
  const cached = await env.UNDAL_KV.get(TOKEN_CACHE_KEY);
  if (cached && cached.length > 20) return cached;

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

  const ttl = Math.max(60, (payload.expires_in ?? 3600) - TOKEN_SAFETY_SECONDS);
  await env.UNDAL_KV.put(TOKEN_CACHE_KEY, payload.access_token, { expirationTtl: ttl });
  return payload.access_token;
}

/** Drops the cached token so the next call re-mints it (used on 401 responses). */
export async function invalidateAccessToken(env: Env): Promise<void> {
  await env.UNDAL_KV.delete(TOKEN_CACHE_KEY);
}

/**
 * Google rotates the X.509 certificates that sign Firebase ID tokens. They are
 * cached in KV for the lifetime the endpoint's Cache-Control advertises.
 */
export async function fetchSecureTokenCertificates(env: Env): Promise<Record<string, string>> {
  const cacheKey = "certs:securetoken";
  const cached = await env.UNDAL_KV.get(cacheKey, "json");
  if (cached) return cached as Record<string, string>;

  const response = await fetch(
    "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com",
  );
  if (!response.ok) throw new Error(`securetoken certificates unavailable (${response.status})`);
  const certificates = (await response.json()) as Record<string, string>;

  const cacheControl = response.headers.get("cache-control") ?? "";
  const maxAgeMatch = /max-age=(\d+)/.exec(cacheControl);
  const ttl = maxAgeMatch?.[1] ? Math.max(300, Number(maxAgeMatch[1])) : 3600;

  await env.UNDAL_KV.put(cacheKey, JSON.stringify(certificates), { expirationTtl: ttl });
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
