/**
 * Firebase ID-token verification (RS256 against Google's rotating X.509 certs).
 *
 * Every /notify/* call from the Android apps carries the caller's ID token in
 * `Authorization: Bearer …`. The Worker verifies the signature, the audience,
 * the issuer and the expiry before it relays anything — an unauthenticated
 * caller can never make the kitchen ring.
 */
import type { Env } from "./env";
import { projectId } from "./env";
import { fetchSecureTokenCertificates } from "./gcp";
import { base64UrlToBytes, bytesToText, pemCertificateToDer, utf8, x509ToSpki } from "./util/codec";
import { HttpError, bearerToken } from "./util/http";

export interface FirebaseIdClaims {
  sub: string;
  aud: string;
  iss: string;
  iat: number;
  exp: number;
  email?: string;
  email_verified?: boolean;
  user_id?: string;
  vendor?: boolean;
  [key: string]: unknown;
}

interface JwtHeader {
  alg?: string;
  kid?: string;
  typ?: string;
}

/** Signature scheme used by every Firebase ID token. */
const RSA_ALGORITHM = { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" } as const;

export async function verifyIdToken(env: Env, idToken: string): Promise<FirebaseIdClaims> {
  const parts = idToken.split(".");
  const [headerPart, claimsPart, signaturePart] = parts;
  if (parts.length !== 3 || !headerPart || !claimsPart || !signaturePart) {
    throw new HttpError(401, "malformed-id-token");
  }

  let header: JwtHeader;
  try {
    header = JSON.parse(bytesToText(base64UrlToBytes(headerPart))) as JwtHeader;
  } catch {
    throw new HttpError(401, "malformed-id-token-header");
  }
  if (header.alg !== "RS256") throw new HttpError(401, "unsupported-id-token-algorithm");
  if (!header.kid) throw new HttpError(401, "id-token-missing-key-id");

  const certificates = await fetchSecureTokenCertificates(env);
  const certificatePem = certificates[header.kid];
  if (!certificatePem) throw new HttpError(401, "unknown-id-token-key");

  const publicKey = await crypto.subtle.importKey(
    "spki",
    x509ToSpki(pemCertificateToDer(certificatePem)),
    { ...RSA_ALGORITHM },
    false,
    ["verify"],
  );

  const signatureValid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    publicKey,
    base64UrlToBytes(signaturePart),
    utf8(`${headerPart}.${claimsPart}`),
  );
  if (!signatureValid) throw new HttpError(401, "id-token-signature-mismatch");

  let claims: FirebaseIdClaims;
  try {
    claims = JSON.parse(bytesToText(base64UrlToBytes(claimsPart))) as FirebaseIdClaims;
  } catch {
    throw new HttpError(401, "malformed-id-token-claims");
  }

  const expectedProject = projectId(env);
  const nowSeconds = Math.floor(Date.now() / 1000);
  if (claims.aud !== expectedProject) throw new HttpError(401, "id-token-wrong-audience");
  if (claims.iss !== `https://securetoken.google.com/${expectedProject}`) {
    throw new HttpError(401, "id-token-wrong-issuer");
  }
  if (typeof claims.exp !== "number" || claims.exp <= nowSeconds) {
    throw new HttpError(401, "id-token-expired");
  }
  if (typeof claims.iat !== "number" || claims.iat > nowSeconds + 60) {
    throw new HttpError(401, "id-token-issued-in-future");
  }
  if (!claims.sub) throw new HttpError(401, "id-token-missing-subject");

  return claims;
}

/** Verifies the caller and asserts the `vendor` custom claim set at bootstrap. */
export async function requireVendor(env: Env, request: Request): Promise<FirebaseIdClaims> {
  const claims = await verifyIdToken(env, bearerToken(request));
  if (claims.vendor !== true) throw new HttpError(403, "vendor-claim-required");
  return claims;
}

/** Verifies the caller and returns their uid (any signed-in customer or vendor). */
export async function requireCaller(env: Env, request: Request): Promise<FirebaseIdClaims> {
  return verifyIdToken(env, bearerToken(request));
}
