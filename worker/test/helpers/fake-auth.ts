/**
 * A Firebase Authentication the Worker's tests can sign in against.
 *
 * `authverify.ts` does the real thing: it reads Google's rotating certificates,
 * imports the one named by the token's `kid`, and verifies an RS256 signature
 * before it believes a single claim. Testing the account routes against a
 * verifier that has been stubbed out would prove nothing, so this file gives the
 * tests a key of their own instead.
 *
 * There is no private key in this repository and none is ever written to disk.
 * A 2048-bit RSA pair is generated in memory the first time a test asks for one,
 * and the *public* half is wrapped in a minimal but structurally real X.509
 * certificate — enough for `x509ToSpki()` to walk
 * `certificate → tbsCertificate → serial, algorithm, issuer, validity, subject`
 * and arrive at the SubjectPublicKeyInfo, which is exactly the path production
 * takes. That certificate is seeded into the fake KV under the same key
 * `gcp.ts` caches Google's own certificates under.
 *
 * The result: `verifyIdToken()` runs unmodified, with real WebCrypto
 * verification, against a token the test signed itself.
 */
import { generateKeyPairSync } from "node:crypto";

const RSA_ALGORITHM = { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" } as const;

/** The project id the fake service account carries, so `aud` matches. */
export const FAKE_AUTH_PROJECT = "undal-test";
/** The key id the test certificate is published under. */
export const FAKE_AUTH_KID = "undal-test-key-1";

// ------------------------------------------------------------------ DER basics

const derLength = (length: number): number[] => {
  if (length < 0x80) return [length];
  const bytes: number[] = [];
  let rest = length;
  while (rest > 0) {
    bytes.unshift(rest & 0xff);
    rest >>= 8;
  }
  return [0x80 | bytes.length, ...bytes];
};

const derElement = (tag: number, content: number[]): number[] => [tag, ...derLength(content.length), ...content];
const derSequence = (...elements: number[][]): number[] => derElement(0x30, elements.flat());
const derInteger = (value: number): number[] => {
  const bytes: number[] = [];
  let rest = value;
  do {
    bytes.unshift(rest & 0xff);
    rest >>= 8;
  } while (rest > 0);
  // A leading zero keeps a positive number whose high bit is set positive.
  if (bytes[0] !== undefined && bytes[0] >= 0x80) bytes.unshift(0x00);
  return derElement(0x02, bytes);
};
const derBitString = (content: number[]): number[] => derElement(0x03, [0x00, ...content]);
const derUtcTime = (date: Date): number[] => {
  const pad = (value: number): string => String(value).padStart(2, "0");
  const text =
    pad(date.getUTCFullYear() % 100) + pad(date.getUTCMonth() + 1) + pad(date.getUTCDate()) +
    pad(date.getUTCHours()) + pad(date.getUTCMinutes()) + pad(date.getUTCSeconds()) + "Z";
  return derElement(0x17, [...new TextEncoder().encode(text)]);
};

/** sha256WithRSAEncryption: OID 1.2.840.113549.1.1.11 followed by NULL. */
const SHA256_WITH_RSA = derSequence(
  derElement(0x06, [0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b]),
  derElement(0x05, []),
);

/** A printable-string RDN, for the two names a certificate cannot omit. */
const commonName = (value: string): number[] =>
  derSequence(
    derSequence(
      derElement(0x31, derSequence(
        derElement(0x06, [0x55, 0x04, 0x03]), // id-at-commonName
        derElement(0x13, [...new TextEncoder().encode(value)]),
      ).flat()),
    ),
  );

/** Wraps a DER SubjectPublicKeyInfo in the smallest certificate that parses. */
function wrapInCertificate(spkiDer: Uint8Array): string {
  const notBefore = new Date(Date.now() - 3_600_000);
  const notAfter = new Date(Date.now() + 86_400_000);
  const tbsCertificate = derSequence(
    derElement(0xa0, derInteger(2)), // version v3
    derInteger(1), // serialNumber
    SHA256_WITH_RSA, // signature
    commonName("Undal Test Token Issuer"), // issuer
    derSequence(derUtcTime(notBefore), derUtcTime(notAfter)), // validity
    commonName("Undal Test Token Issuer"), // subject
    [...spkiDer], // subjectPublicKeyInfo
  );
  const certificate = derSequence(
    tbsCertificate,
    SHA256_WITH_RSA,
    derBitString(tbsCertificate.slice(-32).concat(new Array(224).fill(0))),
  );
  const base64 = Buffer.from(certificate).toString("base64");
  const lines = base64.match(/.{1,64}/g) ?? [base64];
  return `-----BEGIN CERTIFICATE-----\n${lines.join("\n")}\n-----END CERTIFICATE-----\n`;
}

// --------------------------------------------------------------------- signing

const base64Url = (bytes: Uint8Array | ArrayBuffer): string =>
  Buffer.from(bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes)).toString("base64url");

export interface FakeAuthSigner {
  /** The key id the certificate is published under. */
  kid: string;
  /** PEM certificate, seeded into the fake KV for `fetchSecureTokenCertificates`. */
  certificatePem: string;
  /** The certificate map Google's own endpoint would have answered with. */
  certificates(): Record<string, string>;
  /**
   * Signs an ID token the Worker's verifier accepts. The five claims it always
   * checks are filled in unless the caller overrides them.
   */
  idToken(claims?: Record<string, unknown>): Promise<string>;
}

let signer: FakeAuthSigner | null = null;

/** One key pair for the whole test run: generating it is the slow part. */
export function fakeAuthSigner(): FakeAuthSigner {
  if (signer) return signer;
  const { publicKey, privateKey } = generateKeyPairSync("rsa", {
    modulusLength: 2048,
    publicKeyEncoding: { type: "spki", format: "der" },
    privateKeyEncoding: { type: "pkcs8", format: "der" },
  });
  const certificatePem = wrapInCertificate(new Uint8Array(publicKey));

  const sign = async (claims: Record<string, unknown>): Promise<string> => {
    const header = base64Url(new TextEncoder().encode(JSON.stringify({ alg: "RS256", typ: "JWT", kid: FAKE_AUTH_KID })));
    const payload = base64Url(new TextEncoder().encode(JSON.stringify(claims)));
    const key = await crypto.subtle.importKey("pkcs8", privateKey, { ...RSA_ALGORITHM }, false, ["sign"]);
    const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${payload}`));
    return `${header}.${payload}.${base64Url(signature)}`;
  };

  signer = {
    kid: FAKE_AUTH_KID,
    certificatePem,
    certificates: () => ({ [FAKE_AUTH_KID]: certificatePem }),
    idToken: (claims = {}) => {
      const now = Math.floor(Date.now() / 1000);
      return sign({
        aud: FAKE_AUTH_PROJECT,
        iss: `https://securetoken.google.com/${FAKE_AUTH_PROJECT}`,
        iat: now - 30,
        exp: now + 3600,
        auth_time: now - 30,
        sub: "customer-1",
        user_id: "customer-1",
        ...claims,
      });
    },
  };
  return signer;
}
