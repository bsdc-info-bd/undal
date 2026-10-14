/**
 * The web account, end to end against a fake Firebase.
 *
 * What is being pinned down here is not that the routes answer 200 — it is the
 * four promises the account makes:
 *
 *   1. a uid always comes from a verified ID token, never from a request body;
 *   2. a profile document is only ever written in the shape the Firestore rules
 *      accept, so the app's role gate never meets a half document;
 *   3. the order history needs no composite index — the cleanup's 502 was a
 *      two-field query on a project that had never built one, and this endpoint
 *      is the place that lesson could be forgotten;
 *   4. a refusal arrives as a sentence a customer can act on.
 *
 * The ID tokens in these tests are real RS256 signatures verified by the real
 * `verifyIdToken()`; see ./helpers/fake-auth.ts.
 */
import assert from "node:assert/strict";
import { test } from "node:test";
import { HttpError, errorJson } from "../src/util/http";
import { verifyIdToken } from "../src/authverify";
import {
  CLOSE_CONFIRMATION,
  MAX_SAVED_ADDRESSES,
  accountSentence,
  addressProblem,
  handleAccountAddressDelete,
  handleAccountAddressSave,
  handleAccountAddressesGet,
  handleAccountClose,
  handleAccountOrders,
  handleAccountPassword,
  handleAccountProfileGet,
  handleAccountProfileSave,
  handleAccountRefresh,
  handleAccountReset,
  handleAccountSignUp,
  passwordProblem,
} from "../src/routes/account";
import type { Env } from "../src/env";
import { FakeFirebase, orderFields } from "./helpers/fake-firebase";

type Handler = (env: Env, request: Request) => Promise<Response>;

/** Runs a handler the way the router does: an HttpError becomes its JSON body. */
async function call(handler: Handler, env: Env, request: Request): Promise<Response> {
  try {
    return await handler(env, request);
  } catch (error) {
    if (error instanceof HttpError) {
      return errorJson(error.status, error.code, error.message, { hint: error.hint, detail: error.detail });
    }
    throw error;
  }
}

function post(body: unknown, token?: string): Request {
  return new Request("https://undal.test/api/account/x", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });
}

const get = (token?: string): Request =>
  new Request("https://undal.test/api/account/x", {
    headers: token ? { authorization: `Bearer ${token}` } : {},
  });

const body = async (response: Response): Promise<Record<string, any>> => (await response.json()) as Record<string, any>;

/** A pin in Sylhet, inside any sane delivery radius. */
const pin = {
  lat: 24.8951,
  lng: 91.8682,
  title: "Zindabazar",
  desc: "House 12, Road 4, Flat 3B",
  note: "Blue gate beside the pharmacy",
};

async function signedUp(backend: FakeFirebase, email = "customer@example.com"): Promise<{ uid: string; token: string; refreshToken: string }> {
  const response = await call(
    handleAccountSignUp,
    backend.env,
    post({ email, password: "kitchen-99", name: "Ayesha Rahman", phone: "01712345678" }),
  );
  assert.equal(response.status, 201, "sign-up should create the account");
  const payload = await body(response);
  return { uid: payload.uid, token: payload.idToken, refreshToken: payload.refreshToken };
}

// ------------------------------------------------------------------- validation

test("sign-up is validated before a single request reaches Google", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const refusals = [
      [{ email: "not-an-email", password: "kitchen-99", name: "Ayesha", phone: "01712345678" }, "email-shape"],
      [{ email: "a@b.co", password: "12345", name: "Ayesha", phone: "01712345678" }, "password-shape"],
      [{ email: "a@b.co", password: "987654", name: "Ayesha", phone: "01712345678" }, "password-shape"],
      [{ email: "a@b.co", password: "kitchen-99", name: "12", phone: "01712345678" }, "name-shape"],
      [{ email: "a@b.co", password: "kitchen-99", name: "Ayesha", phone: "12345" }, "phone-shape"],
    ] as const;
    for (const [payload, code] of refusals) {
      const response = await call(handleAccountSignUp, backend.env, post(payload));
      assert.equal(response.status, 400, `${code} should be refused`);
      assert.equal((await body(response)).error, code);
    }
    assert.deepEqual(backend.authLog, [], "an invalid form must not spend a call on Firebase Authentication");
  } finally {
    restore();
  }
});

test("the password rule is the app's rule, not a stricter one invented here", () => {
  assert.equal(passwordProblem(""), "Please choose a password.");
  assert.match(passwordProblem("abc12") ?? "", /at least 6/);
  assert.match(passwordProblem("123456") ?? "", /Add a letter/);
  assert.match(passwordProblem("1234567") ?? "", /Add a letter/, "length alone never makes a password acceptable");
  assert.equal(passwordProblem("abcdef"), null);
  assert.equal(passwordProblem("kitchen-99"), null);
  assert.match(passwordProblem("a".repeat(201)) ?? "", /too long/);
});

test("an address without a pin is refused, and the sentence says what to do", () => {
  assert.match(addressProblem({ ...pin, lat: 0 }) ?? "", /Drop a pin/);
  assert.match(addressProblem({ ...pin, lat: 95 }) ?? "", /not on the Earth/);
  assert.match(addressProblem({ ...pin, title: "ab" }) ?? "", /area or neighbourhood/);
  assert.match(addressProblem({ ...pin, desc: "x".repeat(301) }) ?? "", /under 300 characters/);
  assert.match(addressProblem({ ...pin, label: "Home of my very favourite aunt in the whole of Sylhet division" }) ?? "", /under 40 characters/);
  assert.equal(addressProblem(pin), null);
});

test("Firebase's own refusal codes become sentences a customer can act on", () => {
  assert.match(accountSentence("EMAIL_EXISTS"), /already has an Undal account/);
  assert.match(accountSentence("WEAK_PASSWORD"), /at least six characters/);
  assert.equal(
    accountSentence("WEAK_PASSWORD", "Password should be at least 6 characters"),
    "Password should be at least 6 characters",
    "Firebase's own detail wins over the generic sentence",
  );
  assert.match(accountSentence("INVALID_LOGIN_CREDENTIALS"), /do not match/);
  assert.match(accountSentence("TOKEN_EXPIRED"), /sign in again/);
  assert.match(accountSentence("OPERATION_NOT_ALLOWED"), /switched off in this Firebase project/);
  assert.match(accountSentence("CREDENTIAL_TOO_OLD_LOGIN_AGAIN"), /sign in again/);
  assert.match(accountSentence("EMAIL_NOT_FOUND"), /no Undal account/);
  // An unknown code falls back to Firebase's own detail rather than a shrug.
  assert.equal(accountSentence("SOMETHING_NEW", "quota exceeded"), "quota exceeded");
});

// ---------------------------------------------------------------------- sign-up

test("sign-up returns a session that the Worker's own verifier accepts", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const response = await call(
      handleAccountSignUp,
      backend.env,
      post({ email: "Customer@Example.com", password: "kitchen-99", name: "Ayesha Rahman", phone: "+8801712345678" }),
    );
    assert.equal(response.status, 201);
    const payload = await body(response);
    assert.equal(payload.ok, true);
    assert.equal(payload.email, "customer@example.com", "the email is lower-cased once, here");
    assert.deepEqual(payload.profile, { name: "Ayesha Rahman", phone: "01712345678", address: null });
    assert.ok(payload.refreshToken.length > 0, "the tab needs a refresh token to outlive the hour");

    const claims = await verifyIdToken(backend.env, payload.idToken);
    assert.equal(claims.sub, payload.uid);
    assert.equal(claims.email, "customer@example.com");

    assert.deepEqual(backend.authLog, ["accounts:signUp", "accounts:update"], "the display name is set on the account itself");
  } finally {
    restore();
  }
});

test("sign-up writes no profile document: a document without a pin would lie to the app", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { uid } = await signedUp(backend);
    assert.equal(backend.docs.has(`users/${uid}`), false);
    assert.deepEqual(backend.writes, [], "no Firestore write at all until an address exists");
  } finally {
    restore();
  }
});

test("a second sign-up with the same email is refused in plain words", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    await signedUp(backend, "twice@example.com");
    const response = await call(
      handleAccountSignUp,
      backend.env,
      post({ email: "twice@example.com", password: "kitchen-99", name: "Someone Else", phone: "01812345678" }),
    );
    assert.equal(response.status, 400);
    const payload = await body(response);
    assert.match(payload.message, /already has an Undal account/);
    assert.equal(backend.accounts.size, 1, "the refused sign-up created nothing");
  } finally {
    restore();
  }
});

test("accounts are switched off with a named secret when the web API key is missing", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const env = { ...backend.env, FIREBASE_WEB_API_KEY: "" } as unknown as Env;
    const response = await call(
      handleAccountSignUp,
      env,
      post({ email: "a@b.co", password: "kitchen-99", name: "Ayesha", phone: "01712345678" }),
    );
    assert.equal(response.status, 503);
    const payload = await body(response);
    assert.equal(payload.error, "web-accounts-not-configured");
    assert.match(payload.hint, /FIREBASE_WEB_API_KEY/);
    assert.match(payload.hint, /guest order works without an account/);
  } finally {
    restore();
  }
});

// ---------------------------------------------------------------- profile

test("saving a name and number without a pin keeps them in the browser only", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { uid, token } = await signedUp(backend);
    const response = await call(handleAccountProfileSave, backend.env, post({ name: "Ayesha Rahman", phone: "01712345678" }, token));
    assert.equal(response.status, 200);
    const payload = await body(response);
    assert.equal(payload.needsAddress, true);
    assert.equal(payload.created, false);
    assert.equal(backend.docs.has(`users/${uid}`), false);
  } finally {
    restore();
  }
});

test("the first pin creates a profile the Firestore rules would accept", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { uid, token } = await signedUp(backend);
    const response = await call(
      handleAccountProfileSave,
      backend.env,
      post({ name: "Ayesha Rahman", phone: "01712345678", address: pin }, token),
    );
    assert.equal(response.status, 200);
    assert.equal((await body(response)).created, true);

    const written = backend.writes.at(-1);
    assert.ok(written, "the profile should have been written");
    assert.equal(written.path, `users/${uid}`);
    // The create rule demands every one of these, in this shape.
    assert.equal(written.fields.role, "customer");
    assert.equal(written.fields.fcmSub, false);
    assert.equal(written.fields.email, "customer@example.com");
    assert.equal(written.fields.phone, "01712345678");
    assert.deepEqual(Object.keys(written.fields.address as object).sort(), ["desc", "geohashLabel", "lat", "lng", "note", "title"]);
    assert.deepEqual(written.serverTimestamps.sort(), ["createdAt", "updatedAt"], "both stamps are Firestore's, not the Worker's clock");
  } finally {
    restore();
  }
});

test("updating a profile never touches role or createdAt", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { uid, token } = await signedUp(backend);
    backend.setDocument(`users/${uid}`, {
      name: "Old Name",
      email: "customer@example.com",
      phone: "01712345678",
      role: "customer",
      fcmSub: true,
      createdAt: "2026-01-01T00:00:00.000Z",
      address: { ...pin, geohashLabel: "tqm2" },
    });
    const response = await call(
      handleAccountProfileSave,
      backend.env,
      post({ name: "Ayesha Rahman", phone: "01899998888", address: { ...pin, title: "Amberkhana" } }, token),
    );
    assert.equal(response.status, 200);
    const written = backend.writes.at(-1);
    assert.ok(written);
    assert.deepEqual(
      Object.keys(written.fields).sort(),
      ["address", "name", "phone", "updatedAt"],
      "only the three the customer owns, plus the stamp — never role, never createdAt",
    );
    assert.deepEqual(written.serverTimestamps, ["updatedAt"]);
    assert.equal((written.fields.address as Record<string, unknown>).geohashLabel, "tqm2", "the existing geohash survives an address edit");
    assert.equal((backend.docs.get(`users/${uid}`) as Record<string, unknown>).role, "customer");
    assert.equal((backend.docs.get(`users/${uid}`) as Record<string, unknown>).createdAt, "2026-01-01T00:00:00.000Z");
    assert.equal((backend.docs.get(`users/${uid}`) as Record<string, unknown>).fcmSub, true, "a field the customer did not send is left alone");
  } finally {
    restore();
  }
});

test("the profile read returns the uid's own document and nobody else's", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { uid, token } = await signedUp(backend);
    backend.setDocument(`users/${uid}`, { name: "Ayesha Rahman", phone: "01712345678", address: pin });
    backend.setDocument("users/somebody-else", { name: "Other Person", phone: "01811112222", address: { ...pin, title: "Elsewhere" } });
    backend.setDocument(`users/${uid}/addresses/home`, { id: "home", label: "Home", ...pin });

    const response = await call(handleAccountProfileGet, backend.env, get(token));
    assert.equal(response.status, 200);
    const payload = await body(response);
    assert.equal(payload.uid, uid);
    assert.equal(payload.hasProfile, true);
    assert.equal(payload.profile.name, "Ayesha Rahman");
    assert.equal(payload.profile.address.title, "Zindabazar");
    assert.equal(payload.addresses.length, 1);
    assert.equal(payload.addresses[0].label, "Home");
  } finally {
    restore();
  }
});

test("a call with no bearer token is refused before any data is read", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const response = await call(handleAccountProfileGet, backend.env, get());
    assert.equal(response.status, 401);
    assert.equal((await body(response)).error, "missing-bearer-token");
    assert.equal(backend.log.length, 0, "an anonymous caller must not cause a Firestore read");
  } finally {
    restore();
  }
});

test("a forged token is refused by the real signature check", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { token } = await signedUp(backend);
    const [header, , signature] = token.split(".");
    const claims = Buffer.from(JSON.stringify({ sub: "victim", aud: "undal-test", iss: "https://securetoken.google.com/undal-test", iat: 1, exp: 9999999999 })).toString("base64url");
    const response = await call(handleAccountProfileGet, backend.env, get(`${header}.${claims}.${signature}`));
    assert.equal(response.status, 401);
    assert.equal((await body(response)).error, "id-token-signature-mismatch");
  } finally {
    restore();
  }
});

// ------------------------------------------------------------- address book

test("an address can be added, listed, edited and deleted", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { uid, token } = await signedUp(backend);

    const created = await call(handleAccountAddressSave, backend.env, post({ address: { ...pin, label: "Home" } }, token));
    assert.equal(created.status, 201);
    const { id } = await body(created);
    assert.match(id, /^[a-z0-9]{20}$/, "the id is a dashless uuid, safe in a URL path");
    assert.equal(backend.docs.has(`users/${uid}/addresses/${id}`), true);

    const listed = await body(await call(handleAccountAddressesGet, backend.env, get(token)));
    assert.equal(listed.addresses.length, 1);
    assert.equal(listed.addresses[0].label, "Home");

    const edited = await call(handleAccountAddressSave, backend.env, post({ id, address: { ...pin, label: "Office" } }, token));
    assert.equal(edited.status, 200, "the same id is an edit, not a second entry");
    assert.equal((await body(edited)).id, id);
    assert.equal((backend.docs.get(`users/${uid}/addresses/${id}`) as Record<string, unknown>).label, "Office");

    const deleted = await call(
      (env, request) => handleAccountAddressDelete(env, request, id),
      backend.env,
      new Request(`https://undal.test/api/account/addresses/${id}`, {
        method: "DELETE",
        headers: { authorization: `Bearer ${token}` },
      }),
    );
    assert.equal(deleted.status, 200);
    assert.equal(backend.docs.has(`users/${uid}/addresses/${id}`), false);
  } finally {
    restore();
  }
});

test("the address book stops growing at its cap, and says why", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { uid, token } = await signedUp(backend);
    for (let index = 0; index < MAX_SAVED_ADDRESSES; index += 1) {
      backend.setDocument(`users/${uid}/addresses/entry${index}`, {
        id: `entry${index}`, label: `Entry ${index}`, ...pin, createdAt: new Date(Date.now() - index * 1000).toISOString(),
      });
    }
    const response = await call(handleAccountAddressSave, backend.env, post({ address: pin }, token));
    assert.equal(response.status, 409);
    const payload = await body(response);
    assert.equal(payload.error, "address-limit");
    assert.match(payload.message, new RegExp(String(MAX_SAVED_ADDRESSES)));

    // Editing one of the existing entries is still allowed at the cap.
    const edit = await call(handleAccountAddressSave, backend.env, post({ id: "entry0", address: { ...pin, label: "Renamed" } }, token));
    assert.equal(edit.status, 200);
  } finally {
    restore();
  }
});

test("an address with no pin is refused before it is written", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { token } = await signedUp(backend);
    const response = await call(handleAccountAddressSave, backend.env, post({ address: { ...pin, lat: 0, lng: 0 } }, token));
    assert.equal(response.status, 400);
    assert.equal((await body(response)).error, "address-shape");
    assert.deepEqual(backend.writes, []);
  } finally {
    restore();
  }
});

// ------------------------------------------------------------------- history

test("the order history reads the caller's own orders, newest first, with no composite index", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { uid, token } = await signedUp(backend);
    // Deliberately no backend.withIndex("orders", ...): this endpoint must work
    // on a project that has never deployed a composite index.
    backend.setDocument("orders/first", orderFields({ customerId: uid, shortId: "UND-1001", status: "delivered", createdAt: "2026-02-01T10:00:00.000Z", items: [{ id: "rice" }] }));
    backend.setDocument("orders/third", orderFields({ customerId: uid, shortId: "UND-1003", status: "placed", createdAt: "2026-03-01T10:00:00.000Z", items: [{ id: "rice" }, { id: "dal" }] }));
    backend.setDocument("orders/second", orderFields({ customerId: uid, shortId: "UND-1002", status: "in_delivery", createdAt: "2026-02-15T10:00:00.000Z", items: [] }));
    backend.setDocument("orders/somebody-else", orderFields({ customerId: "other-customer", shortId: "UND-2001", createdAt: "2026-03-02T10:00:00.000Z" }));

    const response = await call(handleAccountOrders, backend.env, get(token));
    assert.equal(response.status, 200, "a single-field filter needs no index, so there is no 502 here");
    const payload = await body(response);
    assert.deepEqual(payload.orders.map((row: { shortId: string }) => row.shortId), ["UND-1003", "UND-1002", "UND-1001"]);
    assert.equal(payload.orders[0].itemCount, 2);
    assert.equal(payload.more, false);

    const query = backend.log.find((entry) => entry.includes(":runQuery"));
    assert.ok(query, "the history should be one query");
  } finally {
    restore();
  }
});

test("the history is capped, and the cap is reported rather than hidden", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { uid, token } = await signedUp(backend);
    for (let index = 0; index < 25; index += 1) {
      backend.setDocument(`orders/o${index}`, orderFields({
        customerId: uid,
        shortId: `UND-${1000 + index}`,
        createdAt: new Date(Date.UTC(2026, 0, index + 1)).toISOString(),
        items: [],
      }));
    }
    const payload = await body(await call(handleAccountOrders, backend.env, get(token)));
    assert.equal(payload.orders.length, 20);
    assert.equal(payload.more, true, "the customer is told there is more than the page shows");
    assert.equal(payload.orders[0].shortId, "UND-1024", "newest first");
  } finally {
    restore();
  }
});

// --------------------------------------------------------- password and close

test("changing the password hands back a fresh session", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { uid, token } = await signedUp(backend);
    const weak = await call(handleAccountPassword, backend.env, post({ password: "123456" }, token));
    assert.equal(weak.status, 400);
    assert.match((await body(weak)).message, /Add a letter/);

    const response = await call(handleAccountPassword, backend.env, post({ password: "new-kitchen-1" }, token));
    assert.equal(response.status, 200);
    const payload = await body(response);
    assert.ok(payload.idToken, "the tab stays signed in with the new session");
    const claims = await verifyIdToken(backend.env, payload.idToken);
    assert.equal(claims.sub, uid);
    assert.equal(backend.accounts.get(uid)?.password, "new-kitchen-1");
  } finally {
    restore();
  }
});

test("closing an account needs the confirmation word", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { uid, token } = await signedUp(backend);
    const response = await call(handleAccountClose, backend.env, post({ confirmation: "yes please" }, token));
    assert.equal(response.status, 400);
    const payload = await body(response);
    assert.equal(payload.error, "close-confirmation-required");
    assert.match(payload.hint, new RegExp(CLOSE_CONFIRMATION));
    assert.equal(backend.accounts.has(uid), true, "a mistyped confirmation closes nothing");
  } finally {
    restore();
  }
});

test("the kitchen's own account cannot be closed from the web", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const token = await backend.withAccount("vendor-1", { email: "kitchen@example.com", vendor: true });
    const response = await call(handleAccountClose, backend.env, post({ confirmation: CLOSE_CONFIRMATION }, token));
    assert.equal(response.status, 403);
    assert.equal((await body(response)).error, "vendor-account-cannot-close");
    assert.equal(backend.accounts.has("vendor-1"), true);
  } finally {
    restore();
  }
});

test("closing an account deletes the profile and the addresses but keeps the orders", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { uid, token } = await signedUp(backend);
    await call(handleAccountProfileSave, backend.env, post({ name: "Ayesha Rahman", phone: "01712345678", address: pin }, token));
    await call(handleAccountAddressSave, backend.env, post({ address: { ...pin, label: "Home" } }, token));
    backend.setDocument("orders/kept", orderFields({ customerId: uid, shortId: "UND-1001", status: "delivered" }));
    backend.setDocument(`fcmTokens/${uid}/tokens/web-1`, { platform: "android" });

    const response = await call(handleAccountClose, backend.env, post({ confirmation: CLOSE_CONFIRMATION }, token));
    assert.equal(response.status, 200);
    assert.match((await body(response)).message, /Orders you already placed stay/);

    assert.equal(backend.accounts.has(uid), false, "the Firebase account is gone");
    assert.equal(backend.docs.has(`users/${uid}`), false, "and so is the profile that pointed at it");
    assert.equal(
      [...backend.docs.keys()].some((path) => path.startsWith(`users/${uid}/addresses/`)),
      false,
      "every saved address goes with the account",
    );
    assert.equal(backend.docs.has(`fcmTokens/${uid}`), false);
    assert.equal(backend.docs.has("orders/kept"), true, "an order is the kitchen's record of a sale, not the customer's to erase");
  } finally {
    restore();
  }
});

// ------------------------------------------------- session renewal and reset

test("a saved refresh token renews the session past the token's hour", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const { uid, refreshToken } = await signedUp(backend);
    const response = await call(handleAccountRefresh, backend.env, post({ refreshToken }));
    assert.equal(response.status, 200);
    const payload = await body(response);
    assert.equal(payload.uid, uid);
    assert.ok(payload.idToken);
    const claims = await verifyIdToken(backend.env, payload.idToken);
    assert.equal(claims.sub, uid);
  } finally {
    restore();
  }
});

test("a refresh token that is not this project's is refused, not believed", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    const missing = await call(handleAccountRefresh, backend.env, post({}));
    assert.equal(missing.status, 400);

    const stale = await call(handleAccountRefresh, backend.env, post({ refreshToken: "refresh-somebody-deleted" }));
    assert.equal(stale.status, 401);
    assert.equal((await body(stale)).error, "refresh-refused");
  } finally {
    restore();
  }
});

test("a password reset is sent only to an address that has an account", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    await signedUp(backend, "reset-me@example.com");
    const sent = await call(handleAccountReset, backend.env, post({ email: "Reset-Me@Example.com" }));
    assert.equal(sent.status, 200);
    assert.match((await body(sent)).message, /password-reset link is on its way/);
    assert.deepEqual(backend.resetEmails, ["reset-me@example.com"]);

    const unknown = await call(handleAccountReset, backend.env, post({ email: "nobody@example.com" }));
    assert.equal(unknown.status, 400);
    assert.match((await body(unknown)).message, /no Undal account/);

    const shape = await call(handleAccountReset, backend.env, post({ email: "nope" }));
    assert.equal(shape.status, 400);
    assert.equal((await body(shape)).error, "email-shape");
  } finally {
    restore();
  }
});

test("a Firebase Authentication outage is reported as Google's, not the customer's", async () => {
  const backend = new FakeFirebase();
  const restore = backend.install();
  try {
    backend.authStatus = 503;
    const response = await call(
      handleAccountSignUp,
      backend.env,
      post({ email: "a@b.co", password: "kitchen-99", name: "Ayesha", phone: "01712345678" }),
    );
    assert.equal(response.status, 502);
    const payload = await body(response);
    assert.equal(payload.error, "account-refused");
    assert.match(payload.message, /Firebase Authentication is refusing/, "Google's own detail is passed through");
  } finally {
    restore();
  }
});
