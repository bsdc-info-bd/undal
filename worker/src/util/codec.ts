/**
 * Byte-level helpers used by the JWT (JWS) and X.509 code paths.
 * Everything here is pure Web Crypto plus hand-rolled DER walking — no
 * third-party library, because the Worker must stay dependency-free.
 */

const textDecoder = new TextDecoder();
const textEncoder = new TextEncoder();

export function utf8(value: string): Uint8Array {
  return textEncoder.encode(value);
}

export function bytesToText(bytes: Uint8Array): string {
  return textDecoder.decode(bytes);
}

/** Standard base64 (used by PEM bodies) → bytes. */
export function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i);
  return out;
}

/** RFC 7515 base64url → bytes. */
export function base64UrlToBytes(value: string): Uint8Array {
  const normalised = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalised + "=".repeat((4 - (normalised.length % 4)) % 4);
  return base64ToBytes(padded);
}

/** bytes → RFC 7515 base64url (no padding). */
export function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i] as number);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/**
 * A PEM private key (PKCS#8, "BEGIN PRIVATE KEY") → DER bytes ready for
 * `crypto.subtle.importKey("pkcs8", …)`.
 */
export function pemPrivateKeyToPkcs8(pem: string): Uint8Array {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  if (body.length === 0) throw new Error("empty private key");
  return base64ToBytes(body);
}

/** A PEM certificate (X.509) → DER bytes. */
export function pemCertificateToDer(pem: string): Uint8Array {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  if (body.length === 0) throw new Error("empty certificate");
  return base64ToBytes(body);
}

interface DerElement {
  tag: number;
  start: number;
  contentStart: number;
  end: number;
}

/** Minimal DER TLV reader: enough for the RSA certificates Google serves. */
function readDerElement(buffer: Uint8Array, offset: number): DerElement {
  const tag = buffer[offset];
  if (tag === undefined) throw new Error("truncated DER element");
  let cursor = offset + 1;
  let length = buffer[cursor];
  if (length === undefined) throw new Error("truncated DER length");
  cursor += 1;
  if ((length & 0x80) !== 0) {
    const count = length & 0x7f;
    length = 0;
    for (let i = 0; i < count; i += 1) {
      const byte = buffer[cursor + i];
      if (byte === undefined) throw new Error("truncated DER long length");
      length = (length << 8) | byte;
    }
    cursor += count;
  }
  const end = cursor + length;
  if (end > buffer.length) throw new Error("DER element exceeds buffer");
  return { tag, start: offset, contentStart: cursor, end };
}

/**
 * Extracts the SubjectPublicKeyInfo (SPKI) element from an X.509 certificate so
 * it can be imported with `crypto.subtle.importKey("spki", …)`.
 *
 * Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
 * TBSCertificate ::= SEQUENCE {
 *   [0] version, serialNumber, signature, issuer, validity, subject,
 *   subjectPublicKeyInfo, …
 * }
 */
export function x509ToSpki(certificateDer: Uint8Array): Uint8Array {
  const certificate = readDerElement(certificateDer, 0);
  if (certificate.tag !== 0x30) throw new Error("certificate is not a SEQUENCE");

  let cursor = certificate.contentStart;
  const tbs = readDerElement(certificateDer, cursor);
  if (tbs.tag !== 0x30) throw new Error("tbsCertificate is not a SEQUENCE");

  cursor = tbs.contentStart;
  const maybeVersion = readDerElement(certificateDer, cursor);
  if (maybeVersion.tag === 0xa0) cursor = maybeVersion.end;

  const skipSequence = (): void => {
    const element = readDerElement(certificateDer, cursor);
    cursor = element.end;
  };

  skipSequence(); // serialNumber (INTEGER)
  skipSequence(); // signature (AlgorithmIdentifier)
  skipSequence(); // issuer (Name)
  skipSequence(); // validity
  skipSequence(); // subject (Name)

  const spki = readDerElement(certificateDer, cursor);
  if (spki.tag !== 0x30) throw new Error("subjectPublicKeyInfo is not a SEQUENCE");
  return certificateDer.slice(spki.start, spki.end);
}
