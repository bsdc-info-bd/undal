#!/usr/bin/env node
/**
 * Provisions the single Undal kitchen account (PART O3 / P-031).
 *
 *   1. creates the Firebase Auth user from VENDOR_EMAIL + VENDOR_PASSWORD
 *      (or signs in when it already exists),
 *   2. marks the address verified and grants the `vendor: true` custom claim
 *      that every security rule checks,
 *   3. writes the vendor/{uid} Firestore document.
 *
 * Idempotent: running the bootstrap workflow again is harmless.
 */
import { accessToken, serviceAccountFromEnv, writeDocument } from "./lib/gcp.mjs";

const account = serviceAccountFromEnv();
const apiKey = process.env.FIREBASE_WEB_API_KEY;
const email = process.env.VENDOR_EMAIL;
const password = process.env.VENDOR_PASSWORD;

if (!apiKey) throw new Error("FIREBASE_WEB_API_KEY is empty.");
if (!email || !password) throw new Error("VENDOR_EMAIL / VENDOR_PASSWORD are empty.");

const identityBase = "https://identitytoolkit.googleapis.com/v1";

async function createOrSignIn() {
  const signUp = await fetch(`${identityBase}/accounts:signUp?key=${apiKey}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ email, password, returnSecureToken: true }),
  });
  if (signUp.ok) return { payload: await signUp.json(), created: true };

  const failure = await signUp.json().catch(() => ({}));
  const code = failure?.error?.message ?? `HTTP_${signUp.status}`;
  if (!code.startsWith("EMAIL_EXISTS")) {
    throw new Error(`vendor sign-up failed: ${code}`);
  }

  const signIn = await fetch(`${identityBase}/accounts:signInWithPassword?key=${apiKey}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ email, password, returnSecureToken: true }),
  });
  if (!signIn.ok) {
    const detail = await signIn.json().catch(() => ({}));
    throw new Error(
      `the kitchen account already exists but VENDOR_PASSWORD does not match (${detail?.error?.message ?? signIn.status}). ` +
        "Reset the password in Firebase Console → Authentication → Users, then run bootstrap again.",
    );
  }
  return { payload: await signIn.json(), created: false };
}

const { payload, created } = await createOrSignIn();
const uid = payload.localId;
if (!uid) throw new Error("Identity Toolkit returned no localId");

const token = await accessToken(account);

const update = await fetch(`${identityBase}/projects/${account.project_id}/accounts:update`, {
  method: "POST",
  headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
  body: JSON.stringify({
    localId: uid,
    emailVerified: true,
    customAttributes: JSON.stringify({ vendor: true }),
  }),
});
if (!update.ok) {
  throw new Error(`setting the vendor claim failed (${update.status}): ${await update.text()}`);
}

await writeDocument(
  account,
  token,
  `vendor/${uid}`,
  {
    name: "Undal HomeKitchen",
    email,
    photoUrl: "",
    role: "vendor",
    platform: "android",
  },
  { serverTimestamps: ["createdAt"] },
);

console.log(`vendor account ${created ? "created" : "reused"}: ${email}`);
console.log(`vendor uid: ${uid}`);
console.log("vendor:true claim set, vendor/{uid} document written, email marked verified.");
