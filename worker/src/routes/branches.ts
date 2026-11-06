/** Public branch discovery, automatic area routing and delivery-extension requests. */
import type { Env } from "../env";
import { fsCreateIfAbsent, fsGet, fsQuery } from "../firestore";
import { requireCaller } from "../authverify";
import {
  branchDisplayName,
  legacyStoreAsBranch,
  publicBranch,
  resolveBranch,
  type BranchRecord,
} from "../branches";
import { HttpError, json, readJson } from "../util/http";
import { raiseKitchenAlert } from "../alerting";

export async function loadBranches(env: Env, includeInactive = false): Promise<BranchRecord[]> {
  const records = await fsQuery(env, { collectionId: "branches", limit: 200 });
  const branches = records as BranchRecord[];
  if (branches.length > 0) {
    return includeInactive ? branches : branches.filter((branch) => branch.active !== false);
  }
  // Zero-downtime migration: until seed-config has copied config/store, old and
  // new clients still see the original kitchen as the `default` branch.
  const legacy = legacyStoreAsBranch(await fsGet(env, "config/store").catch(() => null));
  return legacy ? [legacy] : [];
}

function coordinate(body: Record<string, unknown>, key: string): number {
  const value = Number(body[key]);
  if (!Number.isFinite(value)) return Number.NaN;
  return value;
}

export async function handleBranchList(env: Env): Promise<Response> {
  const branches = await loadBranches(env);
  return json({
    ok: true,
    vendor: { id: "undal-home-kitchen", name: "Undal HomeKitchen", multiVendor: false },
    branches: branches.map(publicBranch),
  }, 200, { "cache-control": "public, max-age=20, stale-while-revalidate=60" });
}

export async function handleBranchResolve(env: Env, request: Request): Promise<Response> {
  const body = await readJson<Record<string, unknown>>(request);
  const lat = coordinate(body, "lat");
  const lng = coordinate(body, "lng");
  const requested = typeof body.branchId === "string" ? body.branchId.trim().slice(0, 64) : null;
  const branches = await loadBranches(env);
  const resolution = resolveBranch(branches, lat, lng, requested);
  return json({
    ok: true,
    ...resolution,
    branch: resolution.branch ? publicBranch(resolution.branch) : null,
    nearest: resolution.nearest ? {
      branch: publicBranch(resolution.nearest.branch),
      distanceKm: resolution.nearest.distanceKm,
      covering: resolution.nearest.covering,
    } : null,
    // Candidate documents are not needed by the client and repeating them would
    // make every resolve response unnecessarily large.
    candidates: resolution.candidates.map((candidate) => ({
      branchId: candidate.branch.id,
      branchName: branchDisplayName(candidate.branch),
      distanceKm: candidate.distanceKm,
      covering: candidate.covering,
    })),
  });
}

function text(value: unknown, max: number): string {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

/**
 * A request is routed to the nearest branch, never to a branch chosen by an
 * untrusted client. It carries no basket and creates no order; the super admin
 * must approve a real coverage change before checkout can succeed.
 */
export async function handleDeliveryExtensionRequest(env: Env, request: Request): Promise<Response> {
  const claims = await requireCaller(env, request);
  const body = await readJson<Record<string, unknown>>(request);
  const lat = coordinate(body, "lat");
  const lng = coordinate(body, "lng");
  const branches = await loadBranches(env);
  const resolution = resolveBranch(branches, lat, lng);
  if (resolution.status === "invalid_location") {
    throw new HttpError(400, "location-invalid", "Drop a valid delivery pin before requesting extended delivery.");
  }
  if (resolution.status === "deliverable") {
    return json({ ok: true, created: false, status: "already_covered", branchId: resolution.branchId });
  }
  const nearest = resolution.nearest;
  if (!nearest) throw new HttpError(409, "no-branch-available", "No Undal branch can receive this request yet.");

  const addressTitle = text(body.addressTitle, 100);
  const addressDesc = text(body.addressDesc, 240);
  const addressNote = text(body.addressNote, 240);
  if (addressTitle.length < 2 || addressDesc.length < 3 || addressNote.length < 2) {
    throw new HttpError(400, "address-incomplete", "Save the area, road and a rider landmark with your request.");
  }
  const profile = await fsGet(env, `users/${claims.sub}`).catch(() => null);
  const customerName = text(profile?.name ?? body.customerName, 80) || "Undal customer";
  const phone = text(profile?.phone ?? body.phone, 20);
  const id = crypto.randomUUID().replace(/-/g, "");
  const requestedBranchId = nearest.branch.id;
  const suggestedRadiusKm = Math.min(50, Math.ceil((nearest.distanceKm + 0.25) * 10) / 10);
  await fsCreateIfAbsent(env, `deliveryRequests/${id}`, {
    customerId: claims.sub,
    customerName,
    phone,
    address: { lat, lng, title: addressTitle, desc: addressDesc, note: addressNote },
    branchId: requestedBranchId,
    branchName: branchDisplayName(nearest.branch),
    distanceKm: nearest.distanceKm,
    suggestedRadiusKm,
    status: "pending",
    source: text(body.source, 24) || "android",
    createdAt: new Date(),
    updatedAt: new Date(),
  });

  await raiseKitchenAlert(env, {
    orderId: `delivery-request-${id}`,
    shortId: "DELIVERY AREA",
    branchId: requestedBranchId,
    title: `Delivery extension · ${branchDisplayName(nearest.branch)}`,
    body: `${customerName} is ${nearest.distanceKm.toFixed(1)} km away and requested delivery. Review in the admin panel.`,
    type: "vendor_self",
    kind: "delivery_extension",
    ttl: "86400s",
    extra: { requestId: id, branchId: requestedBranchId },
  }).catch(() => undefined);

  return json({
    ok: true,
    created: true,
    request: { id, branchId: requestedBranchId, branchName: branchDisplayName(nearest.branch), status: "pending", suggestedRadiusKm },
  }, 201);
}
