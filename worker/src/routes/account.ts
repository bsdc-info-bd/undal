/**
 * The customer's own account, on the web (P-048 … P-055).
 *
 * The app has had an account since the first build: register, verify the email,
 * save a profile with a pinned address, sign in, reset a password. The web had a
 * sign-in box and nothing behind it — no way to create an account, no way to see
 * the orders you have placed, no way to change a password or close the account.
 * A customer who ordered from a browser was a `sessionStorage` entry that
 * evaporated when the tab closed.
 *
 * These endpoints give the web the same account, against the same Firebase
 * Authentication project and the same `users/{uid}` document the app writes, so
 * the two are one account rather than two:
 *
 *   POST /api/account/signup    create it
 *   POST /api/account/reset     email a password-reset link
 *   POST /api/account/refresh   keep the tab signed in past the token's hour
 *   GET  /api/account/profile   read it back
 *   POST /api/account/profile   save the name, the number and the pinned address
 *   GET  /api/account/addresses the address book
 *   POST /api/account/addresses add or update one entry
 *   DELETE /api/account/addresses/{id}  remove one entry
 *   GET  /api/account/orders    the caller's own order history
 *   POST /api/account/password  change the password
 *   POST /api/account/close     delete the Firebase account and the profile
 *
 * Two rules hold across all of them. The browser never sees the project's API key
 * — the Worker holds it and forwards one request. And the uid always comes from a
 * verified ID token, never from the request body: a customer can only ever read
 * or write their own account.
 */
import type { Env } from "../env";
import { requireCaller } from "../authverify";
import { fsDeleteBatch, fsGet, fsPatchWithServerTimestamps, fsQuery, type FsRecord } from "../firestore";
import { HttpError, bearerToken, json, readJson } from "../util/http";
import { MAX_NAME_LENGTH, normalizeBdMobile, usableName, usableText } from "../web/cart";

const IDENTITY_TOOLKIT = "https://identitytoolkit.googleapis.com/v1";
const SECURE_TOKEN = "https://securetoken.googleapis.com/v1/token";
const EMAIL_SHAPE = /^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/;
const MIN_PASSWORD_LENGTH = 6;
/** The most addresses one customer may keep. A bounded list is a list that loads. */
export const MAX_SAVED_ADDRESSES = 12;
/** What the account page sends back to close the account. No accidental taps. */
export const CLOSE_CONFIRMATION = "CLOSE_MY_UNDAL_ACCOUNT";
const ORDER_HISTORY_LIMIT = 20;
const ORDER_HISTORY_SCAN = 60;

const str = (value: unknown, fallback = ""): string => (typeof value === "string" ? value : fallback);
const num = (value: unknown, fallback = Number.NaN): number => {
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
};

/** The web API key, or an honest refusal that says which secret is missing. */
function webApiKey(env: Env): string {
  const key = (env.FIREBASE_WEB_API_KEY ?? "").trim();
  if (!key) {
    throw new HttpError(
      503,
      "web-accounts-not-configured",
      "Accounts on the web are switched off right now.",
      {
        hint:
          "Add FIREBASE_WEB_API_KEY to the repository secrets and re-run the deploy-worker workflow. "
          + "The Undal app signs in as usual in the meantime, and a guest order works without an account.",
      },
    );
  }
  return key;
}

async function identityToolkit<T>(env: Env, path: string, body: Record<string, unknown>): Promise<T> {
  const key = webApiKey(env);
  let response: Response;
  try {
    response = await fetch(`${IDENTITY_TOOLKIT}/${path}?key=${encodeURIComponent(key)}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    });
  } catch {
    throw new HttpError(503, "accounts-unreachable", "Firebase Authentication could not be reached from this network.", {
      hint: "This is Google's service, not your data. Try again in a minute.",
    });
  }
  const payload = (await response.json().catch(() => ({}))) as T & { error?: { message?: string } };
  if (!response.ok) {
    const [code, detail] = (payload.error?.message ?? "").split(" : ");
    throw new HttpError(response.status === 400 ? 400 : 502, "account-refused", accountSentence(code, detail));
  }
  return payload;
}

/** Firebase's own refusal codes, in the sentence a customer can act on. */
export function accountSentence(code: string, detail?: string): string {
  switch ((code ?? "").trim()) {
    case "EMAIL_EXISTS":
      return "That email address already has an Undal account. Sign in with it, or reset the password if you have forgotten it.";
    case "WEAK_PASSWORD":
    case "WEAK_PASSWORD : Password should be at least 6 characters":
      return detail?.trim() || "That password is too short. Use at least six characters, and not only numbers.";
    case "INVALID_EMAIL":
      return "That email address is missing something — it should look like name@example.com.";
    case "EMAIL_NOT_FOUND":
    case "USER_NOT_FOUND":
      return "There is no Undal account with that email address.";
    case "INVALID_PASSWORD":
    case "INVALID_LOGIN_CREDENTIALS":
      return "That email and password do not match.";
    case "USER_DISABLED":
      return "That account has been switched off. Please call the kitchen.";
    case "TOO_MANY_ATTEMPTS_TRY_LATER":
      return "Too many attempts. Please wait a few minutes and try again.";
    case "INVALID_ID_TOKEN":
    case "TOKEN_EXPIRED":
    case "ID_TOKEN_EXPIRED":
    case "ID_TOKEN_REVOKED":
      return "Your session on this phone has ended. Please sign in again.";
    case "INVALID_REFRESH_TOKEN":
      return "This browser's saved session is no longer valid. Please sign in again.";
    case "CREDENTIAL_TOO_OLD_LOGIN_AGAIN":
      return "Changing a password needs a fresh sign-in. Please sign in again on this page, then change it.";
    case "OPERATION_NOT_ALLOWED":
      return "Email-and-password accounts are switched off in this Firebase project.";
    case "RESET_PASSWORD_NOT_ALLOWED":
      return "Password reset emails are switched off in this Firebase project.";
    case "QUOTA_EXCEEDED":
      return "The kitchen's Firebase project has run out of quota for that. Please tell them.";
    default:
      return detail?.trim() || "That did not go through. Please check what you typed and try again.";
  }
}

/** The password rule the app enforces, so the two cannot disagree. */
export function passwordProblem(raw: string): string | null {
  if (raw.length === 0) return "Please choose a password.";
  if (raw.length < MIN_PASSWORD_LENGTH) return `Use at least ${MIN_PASSWORD_LENGTH} characters.`;
  if (/^[0-9]+$/.test(raw)) return "Add a letter as well as numbers.";
  if (raw.length > 200) return "That password is too long.";
  return null;
}

interface TokenResponse {
  idToken?: string;
  refreshToken?: string;
  expiresIn?: string;
  localId?: string;
  email?: string;
}

interface SessionPayload {
  idToken: string;
  refreshToken: string;
  expiresIn: number;
  uid: string;
  email: string;
}

function sessionOf(payload: TokenResponse): SessionPayload {
  if (!payload.idToken || !payload.localId) {
    throw new HttpError(502, "account-no-token", "Firebase Authentication answered without a session.");
  }
  return {
    idToken: payload.idToken,
    refreshToken: payload.refreshToken ?? "",
    expiresIn: Number(payload.expiresIn) || 3600,
    uid: payload.localId,
    email: str(payload.email),
  };
}

// -------------------------------------------------------------------- signup

export async function handleAccountSignUp(env: Env, request: Request): Promise<Response> {
  const body = await readJson<{ email?: string; password?: string; name?: string; phone?: string }>(request);
  const email = str(body.email).trim().toLowerCase();
  const password = str(body.password);
  const name = str(body.name).trim().slice(0, MAX_NAME_LENGTH);
  const phone = normalizeBdMobile(str(body.phone));

  if (!EMAIL_SHAPE.test(email)) {
    throw new HttpError(400, "email-shape", "That email address is missing something — it should look like name@example.com.");
  }
  const passwordProblemText = passwordProblem(password);
  if (passwordProblemText) throw new HttpError(400, "password-shape", passwordProblemText);
  if (!usableName(name)) {
    throw new HttpError(400, "name-shape", "Please write the name the kitchen should call out — letters, at least three of them.");
  }
  if (!phone) {
    throw new HttpError(400, "phone-shape", "Write a Bangladeshi mobile number like 01712345678.");
  }

  const payload = await identityToolkit<TokenResponse>(env, "accounts:signUp", {
    email,
    password,
    returnSecureToken: true,
  });
  const session = sessionOf(payload);

  // The profile document is written only once an address exists, exactly as the
  // app does it: `users/{uid}` without a complete address means "onboarding is not
  // finished", and writing a half document here would send the app's role gate
  // down a path it was never written for. The name and number are kept in the
  // browser's own profile cache until then.
  await identityToolkit<TokenResponse>(env, "accounts:update", {
    idToken: session.idToken,
    displayName: name,
  }).catch((error) => {
    // A display name is a convenience; losing it must not lose the account.
    console.warn("undal-account displayName skipped", error instanceof Error ? error.message : String(error));
    return {} as TokenResponse;
  });

  console.info("undal-account signup", JSON.stringify({ uid: session.uid, source: "web" }));
  return json({
    ok: true,
    ...session,
    email,
    profile: { name, phone, address: null },
  }, 201);
}

// --------------------------------------------------------------------- reset

export async function handleAccountReset(env: Env, request: Request): Promise<Response> {
  const body = await readJson<{ email?: string }>(request);
  const email = str(body.email).trim().toLowerCase();
  if (!EMAIL_SHAPE.test(email)) {
    throw new HttpError(400, "email-shape", "That email address is missing something — it should look like name@example.com.");
  }
  await identityToolkit<Record<string, unknown>>(env, "accounts:sendOobCode", {
    requestType: "PASSWORD_RESET",
    email,
  });
  return json({
    ok: true,
    message: `A password-reset link is on its way to ${email}. It arrives from Firebase, and it expires in about an hour.`,
  });
}

// ------------------------------------------------------------------- refresh

export async function handleAccountRefresh(env: Env, request: Request): Promise<Response> {
  const key = webApiKey(env);
  const body = await readJson<{ refreshToken?: string }>(request);
  const refreshToken = str(body.refreshToken);
  if (!refreshToken) throw new HttpError(400, "refresh-token-required", "This browser has no saved session to renew. Please sign in again.");

  let response: Response;
  try {
    response = await fetch(`${SECURE_TOKEN}?key=${encodeURIComponent(key)}`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: refreshToken }),
    });
  } catch {
    throw new HttpError(503, "accounts-unreachable", "Firebase Authentication could not be reached from this network.", {
      hint: "Try again in a minute; your basket is saved on this phone either way.",
    });
  }
  const payload = (await response.json().catch(() => ({}))) as {
    id_token?: string;
    refresh_token?: string;
    expires_in?: string;
    user_id?: string;
    error?: { message?: string };
  };
  if (!response.ok || !payload.id_token) {
    throw new HttpError(401, "refresh-refused", accountSentence(payload.error?.message ?? "", payload.error?.message));
  }
  return json({
    ok: true,
    idToken: payload.id_token,
    refreshToken: payload.refresh_token ?? refreshToken,
    expiresIn: Number(payload.expires_in) || 3600,
    uid: payload.user_id ?? "",
  });
}

// ------------------------------------------------------------------- profile

export interface WebAddress {
  id: string;
  label: string;
  title: string;
  desc: string;
  note: string;
  lat: number;
  lng: number;
  createdAt: string;
}

function addressFrom(record: FsRecord): WebAddress {
  return {
    id: str(record.id),
    label: str(record.label).slice(0, 40),
    title: str(record.title).slice(0, 160),
    desc: str(record.desc).slice(0, 300),
    note: str(record.note).slice(0, 300),
    lat: num(record.lat, 0),
    lng: num(record.lng, 0),
    createdAt: str(record.createdAt),
  };
}

interface AddressInput {
  label?: string;
  title?: string;
  desc?: string;
  note?: string;
  lat?: number;
  lng?: number;
}

/**
 * Validates one address against the same limits the Firestore rules enforce, so a
 * write from the web can never leave a document the app's own update would be
 * refused for.
 */
export function addressProblem(input: AddressInput): string | null {
  const lat = num(input.lat);
  const lng = num(input.lng);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat === 0 || lng === 0) {
    return "Drop a pin on the map first — the rider is sent to that exact point.";
  }
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return "That pin is not on the Earth. Please drop it again.";
  if (!usableText(str(input.title))) return "Write the area or neighbourhood — three characters at least.";
  if (!usableText(str(input.desc))) return "Write the house, road and floor — three characters at least.";
  if (!usableText(str(input.note))) return "Write one landmark the rider can ask for — three characters at least.";
  if (str(input.title).length > 160) return "Keep the area under 160 characters.";
  if (str(input.desc).length > 300 || str(input.note).length > 300) return "Keep the house and the landmark under 300 characters each.";
  if (str(input.label).length > 40) return "Keep the label under 40 characters.";
  return null;
}

export async function handleAccountProfileGet(env: Env, request: Request): Promise<Response> {
  const claims = await requireCaller(env, request);
  const record = await fsGet(env, `users/${claims.sub}`).catch(() => null);
  const address = (record?.address ?? null) as Record<string, unknown> | null;
  const addresses = await listAddresses(env, claims.sub);
  return json({
    ok: true,
    uid: claims.sub,
    email: str(claims.email),
    emailVerified: claims.email_verified === true,
    vendor: claims.vendor === true,
    hasProfile: record !== null,
    profile: {
      name: str(record?.name).slice(0, MAX_NAME_LENGTH),
      phone: str(record?.phone).slice(0, 20),
      address: address && num(address.lat, 0) !== 0
        ? {
            lat: num(address.lat, 0),
            lng: num(address.lng, 0),
            title: str(address.title),
            desc: str(address.desc),
            note: str(address.note),
          }
        : null,
    },
    addresses,
  });
}

export async function handleAccountProfileSave(env: Env, request: Request): Promise<Response> {
  const claims = await requireCaller(env, request);
  const body = await readJson<{ name?: string; phone?: string; address?: AddressInput }>(request);
  const name = str(body.name).trim().slice(0, MAX_NAME_LENGTH);
  if (!usableName(name)) {
    throw new HttpError(400, "name-shape", "Please write the name the kitchen should call out — letters, at least three of them.");
  }
  const phone = normalizeBdMobile(str(body.phone));
  if (!phone) throw new HttpError(400, "phone-shape", "Write a Bangladeshi mobile number like 01712345678.");

  const existing = await fsGet(env, `users/${claims.sub}`).catch(() => null);
  const input = (body.address ?? {}) as AddressInput;
  const hasAddress = num(input.lat, 0) !== 0 && usableText(str(input.title));
  const problem = hasAddress ? addressProblem(input) : null;
  if (problem) throw new HttpError(400, "address-shape", problem);

  if (!existing) {
    if (!hasAddress) {
      // No profile document and no address yet: the name and the number are kept
      // in the browser, and the document is written the moment a pin exists. The
      // app treats a missing `users/{uid}` as "onboarding not finished", which is
      // the truth here, so writing a half document would only lie to it.
      return json({
        ok: true,
        created: false,
        needsAddress: true,
        message: "Your name and number are saved on this phone. Drop a pin on the map and save again to create your Undal profile.",
        profile: { name, phone, address: null },
      });
    }
    await fsPatchWithServerTimestamps(
      env,
      `users/${claims.sub}`,
      {
        name,
        email: str(claims.email),
        phone,
        role: "customer",
        fcmSub: false,
        source: "web",
        address: {
          lat: num(input.lat),
          lng: num(input.lng),
          title: str(input.title).trim().slice(0, 160),
          desc: str(input.desc).trim().slice(0, 300),
          note: str(input.note).trim().slice(0, 300),
          geohashLabel: "",
        },
      },
      ["createdAt", "updatedAt"],
    );
    return json({ ok: true, created: true, needsAddress: false, profile: { name, phone, address: input } });
  }

  const fields: Record<string, unknown> = { name, phone };
  if (hasAddress) {
    fields.address = {
      lat: num(input.lat),
      lng: num(input.lng),
      title: str(input.title).trim().slice(0, 160),
      desc: str(input.desc).trim().slice(0, 300),
      note: str(input.note).trim().slice(0, 300),
      geohashLabel: str((existing.address as Record<string, unknown> | null)?.geohashLabel ?? ""),
    };
  }
  await fsPatchWithServerTimestamps(env, `users/${claims.sub}`, fields, ["updatedAt"]);
  return json({
    ok: true,
    created: false,
    needsAddress: false,
    message: hasAddress ? "Your Undal profile is saved." : "Your name and number are saved.",
  });
}

// ----------------------------------------------------------------- addresses

async function listAddresses(env: Env, uid: string): Promise<WebAddress[]> {
  const records = await fsQuery(
    env,
    { parentPath: `users/${uid}`, collectionId: "addresses", limit: MAX_SAVED_ADDRESSES },
    "your saved addresses",
  ).catch(() => [] as FsRecord[]);
  return records
    .map(addressFrom)
    .sort((left, right) => (left.createdAt < right.createdAt ? 1 : -1))
    .slice(0, MAX_SAVED_ADDRESSES);
}

export async function handleAccountAddressesGet(env: Env, request: Request): Promise<Response> {
  const claims = await requireCaller(env, request);
  return json({ ok: true, addresses: await listAddresses(env, claims.sub) });
}

export async function handleAccountAddressSave(env: Env, request: Request): Promise<Response> {
  const claims = await requireCaller(env, request);
  const body = await readJson<{ id?: string; address?: AddressInput }>(request);
  const input = (body.address ?? {}) as AddressInput;
  const problem = addressProblem(input);
  if (problem) throw new HttpError(400, "address-shape", problem);

  const requestedId = str(body.id).replace(/[^A-Za-z0-9_-]/g, "").slice(0, 40);
  const existing = await listAddresses(env, claims.sub);
  if (!requestedId && existing.length >= MAX_SAVED_ADDRESSES) {
    throw new HttpError(409, "address-limit", `You can keep ${MAX_SAVED_ADDRESSES} addresses. Remove one to add another.`);
  }
  const id = requestedId || crypto.randomUUID().replace(/-/g, "").slice(0, 20);
  const fields = {
    label: str(input.label).trim().slice(0, 40) || str(input.title).trim().slice(0, 40),
    title: str(input.title).trim().slice(0, 160),
    desc: str(input.desc).trim().slice(0, 300),
    note: str(input.note).trim().slice(0, 300),
    lat: num(input.lat),
    lng: num(input.lng),
  };
  const isNew = !requestedId || !existing.some((entry) => entry.id === id);
  await fsPatchWithServerTimestamps(
    env,
    `users/${claims.sub}/addresses/${id}`,
    fields,
    isNew ? ["createdAt", "updatedAt"] : ["updatedAt"],
  );
  return json({ ok: true, id, address: { id, ...fields, createdAt: new Date().toISOString() } }, isNew ? 201 : 200);
}

export async function handleAccountAddressDelete(env: Env, request: Request, id: string): Promise<Response> {
  const claims = await requireCaller(env, request);
  const safeId = id.replace(/[^A-Za-z0-9_-]/g, "").slice(0, 40);
  if (!safeId) throw new HttpError(400, "address-id-invalid", "That address id is not one of yours.");
  await fsDeleteBatch(env, [`users/${claims.sub}/addresses/${safeId}`]);
  return json({ ok: true, deleted: safeId });
}

// -------------------------------------------------------------------- orders

export interface WebOrderRow {
  id: string;
  shortId: string;
  status: string;
  totalPaisa: number;
  itemCount: number;
  createdAt: string;
}

/**
 * The caller's own orders, newest first.
 *
 * Queried on `customerId` alone and sorted here, not on `customerId + createdAt`:
 * a single-field index exists for every field automatically, so this endpoint
 * works on a project that has never deployed a composite index. The history
 * cleanup is the cautionary tale, and it is not being repeated here.
 */
export async function handleAccountOrders(env: Env, request: Request): Promise<Response> {
  const claims = await requireCaller(env, request);
  const records = await fsQuery(
    env,
    { collectionId: "orders", where: [{ field: "customerId", op: "EQUAL", value: claims.sub }], limit: ORDER_HISTORY_SCAN },
    "your order history",
  );
  const rows: WebOrderRow[] = records
    .map((record) => ({
      id: str(record.id),
      shortId: str(record.shortId),
      status: str(record.status, "placed"),
      totalPaisa: num(record.totalAmountPaisa, 0),
      itemCount: Array.isArray(record.items) ? record.items.length : 0,
      createdAt: str(record.createdAt),
    }))
    .sort((left, right) => (left.createdAt < right.createdAt ? 1 : left.createdAt > right.createdAt ? -1 : 0))
    .slice(0, ORDER_HISTORY_LIMIT);
  return json({ ok: true, orders: rows, more: records.length > rows.length });
}

// ------------------------------------------------------------------ password

export async function handleAccountPassword(env: Env, request: Request): Promise<Response> {
  const claims = await requireCaller(env, request);
  const body = await readJson<{ password?: string }>(request);
  const password = str(body.password);
  const problem = passwordProblem(password);
  if (problem) throw new HttpError(400, "password-shape", problem);
  const payload = await identityToolkit<TokenResponse>(env, "accounts:update", {
    idToken: bearerToken(request),
    password,
    returnSecureToken: true,
  });
  const session = sessionOf(payload);
  console.info("undal-account password changed", JSON.stringify({ uid: claims.sub }));
  return json({ ok: true, message: "Your password is changed. Every other device stays signed in until its session ends.", ...session });
}

// --------------------------------------------------------------------- close

export async function handleAccountClose(env: Env, request: Request): Promise<Response> {
  const claims = await requireCaller(env, request);
  if (claims.vendor === true) {
    throw new HttpError(403, "vendor-account-cannot-close", "This is the kitchen's own account. It cannot be closed from the web.", {
      hint: "A vendor account holds live orders. If it really has to go, that is a job for the developer with the Firebase console.",
    });
  }
  const body = await readJson<{ confirmation?: string }>(request);
  if (body.confirmation !== CLOSE_CONFIRMATION) {
    throw new HttpError(400, "close-confirmation-required", "Closing an account needs the confirmation word.", {
      hint: `Send {"confirmation": "${CLOSE_CONFIRMATION}"} — the account page does this from its own confirmation step.`,
    });
  }

  const addresses = await listAddresses(env, claims.sub);
  await identityToolkit<Record<string, unknown>>(env, "accounts:delete", { idToken: bearerToken(request) });
  // The Firebase account is gone, so the profile that pointed at it goes too.
  // Orders stay: they are the kitchen's financial record of food it cooked and
  // delivered, and a customer closing an account is not a reason to erase one.
  await fsDeleteBatch(env, [
    `users/${claims.sub}`,
    ...addresses.map((address) => `users/${claims.sub}/addresses/${address.id}`),
    `fcmTokens/${claims.sub}`,
  ]).catch((error) => {
    console.error("undal-account close: profile deletion failed", error);
    return undefined;
  });

  console.info("undal-account closed", JSON.stringify({ uid: claims.sub }));
  return json({
    ok: true,
    message: "Your Undal account is closed. Orders you already placed stay with the kitchen as their record of the sale.",
  });
}
