/**
 * Minimal Google Cloud helper for the provisioning scripts that run inside
 * GitHub Actions. Node 22, no dependencies: JWT via node:crypto, REST via fetch.
 */
import { createSign } from "node:crypto";

const encoder = new TextEncoder();

function base64Url(input) {
  return Buffer.from(input)
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

export function serviceAccountFromEnv() {
  const raw = process.env.FIREBASE_SA_JSON;
  if (!raw || raw.trim().length === 0) {
    throw new Error("FIREBASE_SA_JSON is empty — add the service account JSON as a repository secret.");
  }
  const account = JSON.parse(raw);
  if (!account.project_id || !account.client_email || !account.private_key) {
    throw new Error("FIREBASE_SA_JSON is missing project_id / client_email / private_key.");
  }
  return account;
}

export async function accessToken(account) {
  const now = Math.floor(Date.now() / 1000);
  const tokenUri = account.token_uri ?? "https://oauth2.googleapis.com/token";
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64Url(
    JSON.stringify({
      iss: account.client_email,
      scope: "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/identitytoolkit",
      aud: tokenUri,
      iat: now - 30,
      exp: now + 3540,
    }),
  );
  const signingInput = `${header}.${claims}`;
  const signer = createSign("RSA-SHA256");
  signer.update(signingInput);
  signer.end();
  const signature = signer.sign(account.private_key, "base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const assertion = `${signingInput}.${signature}`;

  const response = await fetch(tokenUri, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) {
    throw new Error(`token exchange failed (${response.status}): ${await response.text()}`);
  }
  const payload = await response.json();
  if (!payload.access_token) throw new Error("token exchange returned no access_token");
  return payload.access_token;
}

function documentsUrl(projectId, path) {
  return `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/${path}`;
}

export function toFirestoreValue(value) {
  if (value === null || value === undefined) return { nullValue: null };
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "number") {
    return Number.isInteger(value) ? { integerValue: String(value) } : { doubleValue: value };
  }
  if (typeof value === "string") return { stringValue: value };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(toFirestoreValue) } };
  if (typeof value === "object") {
    return {
      mapValue: {
        fields: Object.fromEntries(
          Object.entries(value)
            .filter(([, nested]) => nested !== undefined)
            .map(([key, nested]) => [key, toFirestoreValue(nested)]),
        ),
      },
    };
  }
  return { nullValue: null };
}

export function fromFirestoreValue(value) {
  if (!value) return undefined;
  if ("nullValue" in value) return null;
  if ("booleanValue" in value) return value.booleanValue;
  if ("integerValue" in value) return Number(value.integerValue);
  if ("doubleValue" in value) return value.doubleValue;
  if ("timestampValue" in value) return value.timestampValue;
  if ("stringValue" in value) return value.stringValue;
  if ("arrayValue" in value) return (value.arrayValue.values ?? []).map(fromFirestoreValue);
  if ("mapValue" in value) {
    return Object.fromEntries(
      Object.entries(value.mapValue.fields ?? {}).map(([key, nested]) => [key, fromFirestoreValue(nested)]),
    );
  }
  return undefined;
}

export async function readDocument(account, token, path) {
  const response = await fetch(documentsUrl(account.project_id, path), {
    headers: { authorization: `Bearer ${token}` },
  });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`read ${path} failed (${response.status}): ${await response.text()}`);
  const document = await response.json();
  return Object.fromEntries(
    Object.entries(document.fields ?? {}).map(([key, value]) => [key, fromFirestoreValue(value)]),
  );
}

export async function writeDocument(account, token, path, fields, { serverTimestamps = [] } = {}) {
  const fieldPaths = Object.keys(fields).filter((key) => fields[key] !== undefined);
  const write = {
    update: {
      name: `projects/${account.project_id}/databases/(default)/documents/${path}`,
      fields: Object.fromEntries(fieldPaths.map((key) => [key, toFirestoreValue(fields[key])])),
    },
    updateMask: { fieldPaths },
  };
  if (serverTimestamps.length > 0) {
    write.updateTransforms = serverTimestamps.map((fieldPath) => ({ fieldPath, setToServerValue: "REQUEST_TIME" }));
  }
  const response = await fetch(
    `https://firestore.googleapis.com/v1/projects/${account.project_id}/databases/(default)/documents:commit`,
    {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ writes: [write] }),
    },
  );
  if (!response.ok) throw new Error(`write ${path} failed (${response.status}): ${await response.text()}`);
}

export { encoder };
