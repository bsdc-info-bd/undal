/**
 * Super-admin control plane. Every route requires a verified `superAdmin` claim;
 * writes use a field allowlist and the service account never appears in Pages.
 */
import type { Env } from "../env";
import { projectId } from "../env";
import { requireSuperAdmin } from "../authverify";
import { accessToken } from "../gcp";
import {
  fsGet,
  fsPatch,
  fsPatchWithServerTimestamps,
  fsQuery,
  type FsRecord,
} from "../firestore";
import { branchDisplayName, resolveBranch, validPoint, type BranchRecord, type GeoPoint } from "../branches";
import { HttpError, json, readJson } from "../util/http";
import { loadBranches } from "./branches";
import { publishOrderStatus } from "./notify";

const DAY_KEYS = new Set(["sun", "mon", "tue", "wed", "thu", "fri", "sat"]);
const BRANCH_ID = /^[a-z0-9][a-z0-9-]{1,48}$/;
const ORDER_STATUSES = new Set(["placed", "accepted", "ready", "in_delivery", "delivered", "cancelled"]);

function string(value: unknown, max = 160): string {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

function number(value: unknown, fallback = 0): number {
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function bool(value: unknown, fallback = false): boolean {
  return typeof value === "boolean" ? value : fallback;
}

function slug(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 40);
}

function cleanWeekly(value: unknown): Record<string, Array<{ open: string; close: string }>> {
  if (!value || typeof value !== "object") return {};
  const output: Record<string, Array<{ open: string; close: string }>> = {};
  for (const [day, rawSlots] of Object.entries(value as Record<string, unknown>)) {
    if (!DAY_KEYS.has(day) || !Array.isArray(rawSlots)) continue;
    output[day] = rawSlots.slice(0, 6).flatMap((raw) => {
      if (!raw || typeof raw !== "object") return [];
      const slot = raw as Record<string, unknown>;
      const open = string(slot.open, 5);
      const close = string(slot.close, 5);
      return /^([01]\d|2[0-3]):[0-5]\d$/.test(open) && /^([01]\d|2[0-3]):[0-5]\d$/.test(close)
        ? [{ open, close }]
        : [];
    });
  }
  return output;
}

function cleanPolygon(value: unknown): GeoPoint[] {
  if (!Array.isArray(value)) return [];
  return value.slice(0, 128).flatMap((entry) => {
    if (!entry || typeof entry !== "object") return [];
    const point = entry as Record<string, unknown>;
    const lat = number(point.lat, Number.NaN);
    const lng = number(point.lng, Number.NaN);
    return validPoint(lat, lng) ? [{ lat, lng }] : [];
  });
}

export function sanitiseBranchInput(body: Record<string, unknown>, existing?: FsRecord | null): Record<string, unknown> {
  const name = string(body.name ?? body.storeName ?? existing?.name, 80);
  if (name.length < 2) throw new HttpError(400, "branch-name-required", "Give this branch a customer-facing area name.");
  const storeLat = number(body.storeLat ?? existing?.storeLat, Number.NaN);
  const storeLng = number(body.storeLng ?? existing?.storeLng, Number.NaN);
  if (!validPoint(storeLat, storeLng)) throw new HttpError(400, "branch-pin-required", "Pin the branch kitchen on the map.");
  const radiusKm = number(body.radiusKm ?? body.coverageRadiusKm ?? existing?.radiusKm, 5);
  if (radiusKm < 0.25 || radiusKm > 50) throw new HttpError(400, "branch-radius-invalid", "Delivery radius must be between 0.25 and 50 km.");
  const perKm = Math.round(number(body.deliveryPerKmAmountPaisa ?? existing?.deliveryPerKmAmountPaisa, 2000));
  const minimum = Math.round(number(body.minOrderAmountPaisa ?? existing?.minOrderAmountPaisa, 0));
  if (perKm < 0 || perKm > 100_000 || minimum < 0 || minimum > 10_000_000) {
    throw new HttpError(400, "branch-pricing-invalid", "Check the delivery fee and minimum order amounts.");
  }
  const rawCoverage = body.coverage && typeof body.coverage === "object" ? body.coverage as Record<string, unknown> : {};
  const polygon = cleanPolygon(body.coveragePolygon ?? rawCoverage.polygon ?? existing?.coveragePolygon);
  const coverage = polygon.length >= 3 ? { radiusKm, polygon } : { radiusKm, polygon: [] };
  const output: Record<string, unknown> = {
    name,
    storeName: branchDisplayName({ id: string(existing?.id) || "new", name }),
    code: string(body.code ?? existing?.code, 20).toUpperCase(),
    slug: slug(string(body.slug ?? existing?.slug, 48) || name),
    active: bool(body.active, existing?.active !== false),
    acceptingOrders: bool(body.acceptingOrders, existing?.acceptingOrders === true),
    open: bool(body.open, existing?.open === true),
    openSynced: bool(body.open, existing?.open === true),
    autoClosed: bool(body.autoClosed, existing?.autoClosed === true),
    manualOverrideNote: string(body.manualOverrideNote ?? existing?.manualOverrideNote, 180),
    weekly: cleanWeekly(body.weekly ?? existing?.weekly),
    specialDates: Array.isArray(body.specialDates) ? body.specialDates.slice(0, 60) : (existing?.specialDates ?? []),
    deliveryPerKmAmountPaisa: perKm,
    radiusKm,
    minOrderAmountPaisa: minimum,
    storeLat,
    storeLng,
    storeAddressText: string(body.storeAddressText ?? existing?.storeAddressText, 200),
    serviceAreas: Array.isArray(body.serviceAreas)
      ? body.serviceAreas.map((area) => string(area, 60)).filter(Boolean).slice(0, 30)
      : (existing?.serviceAreas ?? []),
    coverage,
    coveragePolygon: polygon,
    priority: Math.max(0, Math.min(9999, Math.round(number(body.priority ?? existing?.priority, 100)))),
    bannerUrl: string(body.bannerUrl ?? existing?.bannerUrl, 600),
    logoUrl: string(body.logoUrl ?? existing?.logoUrl, 600),
    managerUids: Array.isArray(body.managerUids)
      ? [...new Set(body.managerUids.map((uid) => string(uid, 128)).filter(Boolean))].slice(0, 50)
      : (existing?.managerUids ?? []),
  };
  return output;
}

export async function handleAdminLogin(env: Env, request: Request): Promise<Response> {
  const body = await readJson<{ email?: string; password?: string }>(request);
  const email = string(body.email, 180).toLowerCase();
  const password = typeof body.password === "string" ? body.password : "";
  const key = (env.FIREBASE_WEB_API_KEY ?? "").trim();
  if (!key) throw new HttpError(503, "firebase-web-key-missing");
  if (!email.includes("@") || password.length < 8) throw new HttpError(400, "credentials-invalid");
  const response = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${encodeURIComponent(key)}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ email, password, returnSecureToken: true }),
  });
  const payload = await response.json().catch(() => ({})) as Record<string, unknown>;
  if (!response.ok) throw new HttpError(401, "admin-sign-in-refused", "Email or password was not accepted.");
  // Do not reveal whether an otherwise valid account lacks the admin claim here;
  // the authenticated bootstrap call below makes that decision.
  return json({
    ok: true,
    idToken: string(payload.idToken, 5000),
    refreshToken: string(payload.refreshToken, 5000),
    expiresIn: string(payload.expiresIn, 12),
    uid: string(payload.localId, 128),
  });
}

async function overview(env: Env): Promise<Response> {
  const [branches, menuItems, categories, orders, requests, vendors] = await Promise.all([
    loadBranches(env, true),
    fsQuery(env, { collectionId: "menuItems", limit: 500 }),
    fsQuery(env, { collectionId: "categories", limit: 100 }),
    fsQuery(env, { collectionId: "orders", limit: 500 }),
    fsQuery(env, { collectionId: "deliveryRequests", limit: 200 }),
    fsQuery(env, { collectionId: "vendor", limit: 100 }),
  ]);
  const publicOrders = orders.map((order) => ({
    id: order.id, shortId: order.shortId, branchId: order.branchId ?? "default",
    branchName: order.branchName ?? "Undal HomeKitchen", status: order.status,
    customerName: order.customerName, phone: order.phone, items: order.items,
    totalAmountPaisa: order.totalAmountPaisa, createdAt: order.createdAt,
    updatedAt: order.updatedAt, address: order.address, cancelNote: order.cancelNote,
  }));
  return json({
    ok: true,
    serverTime: new Date().toISOString(),
    vendor: { id: "undal-home-kitchen", name: "Undal HomeKitchen", multiVendor: false },
    branches,
    menuItems,
    categories,
    orders: publicOrders,
    deliveryRequests: requests,
    staff: vendors.map((vendor) => ({
      uid: vendor.id, name: vendor.name, email: vendor.email, branchIds: vendor.branchIds ?? [],
      disabled: vendor.disabled === true, platform: vendor.platform,
    })),
  });
}

async function saveBranch(env: Env, request: Request, branchId?: string): Promise<Response> {
  const claims = await requireSuperAdmin(env, request);
  const body = await readJson<Record<string, unknown>>(request);
  const existing = branchId ? await fsGet(env, `branches/${branchId}`) : null;
  if (branchId && !existing) throw new HttpError(404, "branch-not-found");
  const id = branchId ?? slug(string(body.slug, 48) || string(body.name, 60));
  if (!BRANCH_ID.test(id)) throw new HttpError(400, "branch-id-invalid", "Use a short lowercase area name for the branch URL.");
  if (!branchId && await fsGet(env, `branches/${id}`)) throw new HttpError(409, "branch-already-exists");
  const fields = sanitiseBranchInput(body, existing);
  const timestamps = existing ? ["updatedAt"] : ["createdAt", "updatedAt"];
  await fsPatchWithServerTimestamps(env, `branches/${id}`, fields, timestamps);
  await fsPatchWithServerTimestamps(env, `auditLog/${crypto.randomUUID().replace(/-/g, "")}`, {
    actorId: claims.sub, action: existing ? "branch.updated" : "branch.created", branchId: id,
  }, ["createdAt"]);
  return json({ ok: true, branchId: id, branch: await fsGet(env, `branches/${id}`) }, existing ? 200 : 201);
}

async function saveMenuItem(env: Env, request: Request): Promise<Response> {
  await requireSuperAdmin(env, request);
  const body = await readJson<Record<string, unknown>>(request);
  const id = string(body.id, 80).replace(/[^A-Za-z0-9_-]/g, "") || crypto.randomUUID().replace(/-/g, "");
  const existing = await fsGet(env, `menuItems/${id}`);
  const name = string(body.name ?? existing?.name, 100);
  const amount = Math.round(number(body.amountPaisa ?? existing?.amountPaisa, Number.NaN));
  if (name.length < 2 || !Number.isFinite(amount) || amount <= 0) {
    throw new HttpError(400, "menu-item-invalid", "A dish needs a name and a positive price in paisa.");
  }
  const branchId = string(body.branchId ?? existing?.branchId, 64) || "*";
  if (branchId !== "*" && !await fsGet(env, `branches/${branchId}`)) throw new HttpError(400, "menu-branch-missing");
  const discount = Math.max(0, Math.round(number(body.discountAmountPaisa ?? existing?.discountAmountPaisa)));
  if (discount >= amount) throw new HttpError(400, "menu-discount-invalid", "Discount must be smaller than the full price.");
  await fsPatchWithServerTimestamps(env, `menuItems/${id}`, {
    name,
    description: string(body.description ?? existing?.description, 1000),
    amountPaisa: amount,
    discountAmountPaisa: discount,
    categoryId: string(body.categoryId ?? existing?.categoryId, 80),
    subcategoryId: string(body.subcategoryId ?? existing?.subcategoryId, 80),
    branchId,
    available: bool(body.available, existing?.available !== false),
    featured: bool(body.featured, existing?.featured === true),
    veg: bool(body.veg, existing?.veg === true),
    spiceLevel: Math.max(0, Math.min(3, Math.round(number(body.spiceLevel ?? existing?.spiceLevel)))),
    imageUrl: string(body.imageUrl ?? existing?.imageUrl, 600),
    tags: Array.isArray(body.tags) ? body.tags.map((tag) => string(tag, 30)).filter(Boolean).slice(0, 20) : (existing?.tags ?? []),
    orderCount: Math.max(0, Math.round(number(existing?.orderCount))),
  }, existing ? ["updatedAt"] : ["createdAt", "updatedAt"]);
  return json({ ok: true, id, item: await fsGet(env, `menuItems/${id}`) });
}

async function saveCategory(env: Env, request: Request): Promise<Response> {
  await requireSuperAdmin(env, request);
  const body = await readJson<Record<string, unknown>>(request);
  const id = string(body.id, 80).replace(/[^A-Za-z0-9_-]/g, "") || crypto.randomUUID().replace(/-/g, "");
  const existing = await fsGet(env, `categories/${id}`);
  const name = string(body.name ?? existing?.name, 80);
  if (name.length < 2) throw new HttpError(400, "category-name-required");
  await fsPatchWithServerTimestamps(env, `categories/${id}`, {
    name,
    order: Math.round(number(body.order ?? existing?.order, 100)),
    active: bool(body.active, existing?.active !== false),
    iconUrl: string(body.iconUrl ?? existing?.iconUrl, 600),
    branchId: string(body.branchId ?? existing?.branchId, 64) || "*",
  }, existing ? ["updatedAt"] : ["createdAt", "updatedAt"]);
  return json({ ok: true, id, category: await fsGet(env, `categories/${id}`) });
}

async function transitionOrder(env: Env, request: Request, orderId: string): Promise<Response> {
  const claims = await requireSuperAdmin(env, request);
  const body = await readJson<Record<string, unknown>>(request);
  const order = await fsGet(env, `orders/${orderId}`);
  if (!order) throw new HttpError(404, "order-not-found");
  const from = string(order.status, 30);
  const to = string(body.status, 30);
  if (!ORDER_STATUSES.has(to)) throw new HttpError(400, "order-status-invalid");
  const allowed = (from === "placed" && ["accepted", "cancelled"].includes(to)) ||
    (from === "accepted" && ["ready", "cancelled"].includes(to)) ||
    (from === "ready" && to === "in_delivery") || (from === "in_delivery" && to === "delivered");
  if (!allowed) throw new HttpError(409, "order-transition-invalid", `${from} cannot move directly to ${to}.`);
  const timeline = Array.isArray(order.timeline) ? [...order.timeline, { status: to }] : [{ status: to }];
  const fields: Record<string, unknown> = { status: to, timeline };
  const stamps = ["updatedAt"];
  if (to === "accepted") {
    const prepMinutes = Math.max(1, Math.min(180, Math.round(number(body.prepMinutes, 25))));
    fields.prepMinutes = prepMinutes;
    fields.prepEndsAt = new Date(Date.now() + prepMinutes * 60_000);
    stamps.push("acceptedAt");
  }
  if (to === "ready") stamps.push("readyAt");
  if (to === "in_delivery") stamps.push("inDeliveryAt");
  if (to === "delivered") {
    fields.collectedAmountPaisa = Math.round(number(order.totalAmountPaisa));
    stamps.push("deliveredAt");
  }
  if (to === "cancelled") {
    const note = string(body.cancelNote, 300);
    if (note.length < 3) throw new HttpError(400, "cancel-note-required");
    fields.cancelNote = note;
  }
  await fsPatchWithServerTimestamps(env, `orders/${orderId}`, fields, stamps);
  await fsPatchWithServerTimestamps(env, `auditLog/${crypto.randomUUID().replace(/-/g, "")}`, {
    actorId: claims.sub, action: "order.status", orderId, from, to,
  }, ["createdAt"]);
  const notification = await (await publishOrderStatus(env, orderId)).json().catch(() => ({ ok: false }));
  return json({ ok: true, order: await fsGet(env, `orders/${orderId}`), notification });
}

async function decideExtension(env: Env, request: Request, requestId: string): Promise<Response> {
  const claims = await requireSuperAdmin(env, request);
  const body = await readJson<Record<string, unknown>>(request);
  const extension = await fsGet(env, `deliveryRequests/${requestId}`);
  if (!extension) throw new HttpError(404, "delivery-request-not-found");
  if (extension.status !== "pending") throw new HttpError(409, "delivery-request-already-decided");
  const decision = string(body.decision, 20);
  if (!["approved", "rejected"].includes(decision)) throw new HttpError(400, "delivery-decision-invalid");
  const branchId = string(extension.branchId, 64);
  const branch = await fsGet(env, `branches/${branchId}`);
  if (!branch) throw new HttpError(409, "delivery-request-branch-missing");
  if (decision === "approved") {
    const radiusKm = Math.max(number(branch.radiusKm), Math.min(50, number(body.radiusKm, number(extension.suggestedRadiusKm))));
    await fsPatchWithServerTimestamps(env, `branches/${branchId}`, {
      radiusKm,
      coverage: { ...(branch.coverage as Record<string, unknown> ?? {}), radiusKm, polygon: [] },
      coveragePolygon: [],
      updatedBy: claims.sub,
    }, ["updatedAt"]);
  }
  await fsPatchWithServerTimestamps(env, `deliveryRequests/${requestId}`, {
    status: decision,
    decisionNote: string(body.note, 300),
    decidedBy: claims.sub,
  }, ["updatedAt", "decidedAt"]);
  return json({ ok: true, request: await fsGet(env, `deliveryRequests/${requestId}`), branch: await fsGet(env, `branches/${branchId}`) });
}

async function assignStaff(env: Env, request: Request): Promise<Response> {
  const claims = await requireSuperAdmin(env, request);
  const body = await readJson<Record<string, unknown>>(request);
  const uid = string(body.uid, 128);
  const branchIds = Array.isArray(body.branchIds)
    ? [...new Set(body.branchIds.map((id) => string(id, 64)).filter((id) => BRANCH_ID.test(id)))].slice(0, 50)
    : [];
  if (!uid || branchIds.length === 0) throw new HttpError(400, "staff-assignment-invalid", "Choose a staff uid and at least one branch.");
  for (const branchId of branchIds) if (!await fsGet(env, `branches/${branchId}`)) throw new HttpError(400, "staff-branch-missing", branchId);
  const token = await accessToken(env);
  const base = `https://identitytoolkit.googleapis.com/v1/projects/${projectId(env)}`;
  const lookup = await fetch(`${base}/accounts:lookup`, {
    method: "POST", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ localId: [uid] }),
  });
  const lookupPayload = await lookup.json().catch(() => ({})) as { users?: Array<Record<string, unknown>> };
  const account = lookupPayload.users?.[0];
  if (!lookup.ok || !account) throw new HttpError(404, "staff-auth-account-not-found");
  let existingClaims: Record<string, unknown> = {};
  try { existingClaims = JSON.parse(string(account.customAttributes, 5000) || "{}") as Record<string, unknown>; } catch { /* replace malformed claims */ }
  // RTDB rules cannot safely search a Firestore-style list claim. A map gives
  // them O(1) checks such as branchAccess[data.child('branchId').val()].
  const branchAccess = Object.fromEntries(branchIds.map((id) => [id, true]));
  const nextClaims = { ...existingClaims, vendor: true, branchIds, branchAccess, superAdmin: false };
  const update = await fetch(`${base}/accounts:update`, {
    method: "POST", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ localId: uid, customAttributes: JSON.stringify(nextClaims) }),
  });
  if (!update.ok) throw new HttpError(502, "staff-claim-update-failed", await update.text());
  await fsPatchWithServerTimestamps(env, `vendor/${uid}`, {
    name: string(body.name ?? account.displayName, 80) || "Undal branch staff",
    email: string(account.email, 180), role: "vendor", branchIds, platform: "undal-vendor-android",
    assignedBy: claims.sub,
  }, ["updatedAt"]);
  return json({ ok: true, uid, branchIds, message: "Staff must sign out and back in to refresh their Firebase claims." });
}

/** Dispatches /api/admin/* after the main Worker router has rate-limited it. */
export async function handleAdminApi(env: Env, request: Request, path: string): Promise<Response> {
  if (path === "/api/admin/login" && request.method === "POST") return handleAdminLogin(env, request);
  if (path === "/api/admin/overview" && request.method === "GET") {
    await requireSuperAdmin(env, request);
    return overview(env);
  }
  if (path === "/api/admin/branches" && request.method === "POST") return saveBranch(env, request);
  const branchMatch = /^\/api\/admin\/branches\/([a-z0-9-]{2,49})$/.exec(path);
  if (branchMatch && ["PUT", "PATCH", "POST"].includes(request.method)) return saveBranch(env, request, branchMatch[1]);
  if (path === "/api/admin/menu-items" && ["POST", "PUT", "PATCH"].includes(request.method)) return saveMenuItem(env, request);
  if (path === "/api/admin/categories" && ["POST", "PUT", "PATCH"].includes(request.method)) return saveCategory(env, request);
  const orderMatch = /^\/api\/admin\/orders\/([A-Za-z0-9]{8,64})\/status$/.exec(path);
  if (orderMatch && request.method === "POST") return transitionOrder(env, request, orderMatch[1]);
  const extensionMatch = /^\/api\/admin\/delivery-requests\/([A-Za-z0-9]{8,64})$/.exec(path);
  if (extensionMatch && request.method === "POST") return decideExtension(env, request, extensionMatch[1]);
  if (path === "/api/admin/staff" && request.method === "POST") return assignStaff(env, request);
  if (path === "/api/admin/session" && request.method === "GET") {
    const claims = await requireSuperAdmin(env, request);
    return json({ ok: true, uid: claims.sub, email: claims.email ?? null, role: "super-admin" });
  }
  throw new HttpError(404, "admin-route-not-found");
}

/** Used by tests and migration tooling to prove a new branch really resolves. */
export async function resolveAdminBranch(env: Env, lat: number, lng: number): Promise<BranchRecord | null> {
  return resolveBranch(await loadBranches(env), lat, lng).branch;
}

/** Small utility for branch screens; not exported to browsers as a mutation. */
export async function setBranchActive(env: Env, branchId: string, active: boolean): Promise<void> {
  await fsPatch(env, `branches/${branchId}`, { active, updatedAt: new Date() });
}
