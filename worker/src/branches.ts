/**
 * Undal's multi-branch delivery router.
 *
 * A branch is one kitchen of the same single vendor, Undal HomeKitchen. This
 * module contains no network or platform code: Android, the public API, web
 * checkout and the super-admin API all use the same deterministic rules.
 *
 * Selection is deliberately not "nearest pin wins". A customer must first be
 * inside a branch's published delivery area (polygon when present, otherwise
 * radius). Among covering branches the lowest routing priority wins, then the
 * shortest ride, then the stable id. The nearest non-covering branch is returned
 * separately so an outside-area customer can ask that branch to extend service.
 */

export interface GeoPoint {
  lat: number;
  lng: number;
}

export interface BranchRecord extends Record<string, unknown> {
  id: string;
  name?: string;
  storeName?: string;
  code?: string;
  slug?: string;
  active?: boolean;
  priority?: number;
  storeLat?: number;
  storeLng?: number;
  radiusKm?: number;
  deliveryPerKmAmountPaisa?: number;
  minOrderAmountPaisa?: number;
  coverage?: {
    radiusKm?: number;
    polygon?: GeoPoint[];
  };
  coveragePolygon?: GeoPoint[];
  serviceAreas?: string[];
}

export interface BranchCandidate {
  branch: BranchRecord;
  distanceKm: number;
  covering: boolean;
}

export interface BranchResolution {
  status: "deliverable" | "outside_coverage" | "no_branches" | "invalid_location";
  branch: BranchRecord | null;
  branchId: string | null;
  branchName: string | null;
  distanceKm: number | null;
  deliveryPaisa: number;
  nearest: BranchCandidate | null;
  candidates: BranchCandidate[];
  canRequestExtension: boolean;
}

const EARTH_RADIUS_KM = 6371.0088;
const MAX_BRANCH_DISTANCE_KM = 100;
const MAX_RADIUS_KM = 50;

export function validPoint(lat: unknown, lng: unknown): lat is number {
  return typeof lat === "number" && Number.isFinite(lat) && typeof lng === "number" &&
    Number.isFinite(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 &&
    !(lat === 0 && lng === 0);
}

export function haversineKm(a: GeoPoint, b: GeoPoint): number {
  const radians = (degrees: number) => degrees * Math.PI / 180;
  const dLat = radians(b.lat - a.lat);
  const dLng = radians(b.lng - a.lng);
  const lat1 = radians(a.lat);
  const lat2 = radians(b.lat);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
}

/** Ray casting, with an explicit boundary check so a pin on the edge is served. */
export function pointInPolygon(point: GeoPoint, polygon: GeoPoint[]): boolean {
  if (polygon.length < 3 || polygon.some((vertex) => !validPoint(vertex.lat, vertex.lng))) return false;
  const onSegment = (a: GeoPoint, b: GeoPoint): boolean => {
    const cross = (point.lng - a.lng) * (b.lat - a.lat) - (point.lat - a.lat) * (b.lng - a.lng);
    const scale = Math.max(1, Math.abs(b.lat - a.lat), Math.abs(b.lng - a.lng));
    if (Math.abs(cross) > 1e-9 * scale) return false;
    return point.lat >= Math.min(a.lat, b.lat) - 1e-10 && point.lat <= Math.max(a.lat, b.lat) + 1e-10 &&
      point.lng >= Math.min(a.lng, b.lng) - 1e-10 && point.lng <= Math.max(a.lng, b.lng) + 1e-10;
  };

  let inside = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i, i += 1) {
    const a = polygon[i];
    const b = polygon[j];
    if (onSegment(a, b)) return true;
    const crosses = (a.lat > point.lat) !== (b.lat > point.lat);
    if (!crosses) continue;
    const longitudeAtLatitude = (b.lng - a.lng) * (point.lat - a.lat) / (b.lat - a.lat) + a.lng;
    if (point.lng < longitudeAtLatitude) inside = !inside;
  }
  return inside;
}

function finite(value: unknown, fallback = 0): number {
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function pointFrom(value: unknown): GeoPoint | null {
  if (!value || typeof value !== "object") return null;
  const record = value as Record<string, unknown>;
  const lat = finite(record.lat, Number.NaN);
  const lng = finite(record.lng, Number.NaN);
  return validPoint(lat, lng) ? { lat, lng } : null;
}

export function branchPoint(branch: BranchRecord): GeoPoint | null {
  const location = branch.location as Record<string, unknown> | undefined;
  const lat = finite(branch.storeLat ?? location?.lat, Number.NaN);
  const lng = finite(branch.storeLng ?? location?.lng, Number.NaN);
  return validPoint(lat, lng) ? { lat, lng } : null;
}

export function branchPolygon(branch: BranchRecord): GeoPoint[] {
  const raw = branch.coverage?.polygon ?? branch.coveragePolygon ?? [];
  if (!Array.isArray(raw)) return [];
  return raw.map(pointFrom).filter((point): point is GeoPoint => point !== null);
}

export function branchRadiusKm(branch: BranchRecord): number {
  return Math.min(MAX_RADIUS_KM, Math.max(0, finite(branch.coverage?.radiusKm ?? branch.radiusKm)));
}

export function branchDisplayName(branch: BranchRecord): string {
  const name = typeof branch.name === "string" && branch.name.trim()
    ? branch.name.trim()
    : typeof branch.storeName === "string" && branch.storeName.trim()
      ? branch.storeName.trim()
      : "Undal HomeKitchen";
  return name.startsWith("Undal HomeKitchen") ? name : `Undal HomeKitchen · ${name}`;
}

export function coversPoint(branch: BranchRecord, point: GeoPoint, distanceKm?: number): boolean {
  if (branch.active === false) return false;
  const polygon = branchPolygon(branch);
  // A valid polygon is authoritative; a radius must not accidentally enlarge it.
  if (polygon.length >= 3) return pointInPolygon(point, polygon);
  const kitchen = branchPoint(branch);
  const radius = branchRadiusKm(branch);
  if (!kitchen || radius <= 0) return false;
  return (distanceKm ?? haversineKm(kitchen, point)) <= radius + 1e-9;
}

/** Public, non-sensitive branch data safe for an anonymous customer. */
export function publicBranch(branch: BranchRecord): Record<string, unknown> {
  const allowed = [
    "id", "name", "storeName", "code", "slug", "active", "acceptingOrders", "open", "openSynced",
    "autoClosed", "manualOverrideNote", "weekly", "specialDates", "deliveryPerKmAmountPaisa",
    "radiusKm", "minOrderAmountPaisa", "storeLat", "storeLng", "storeAddressText", "bannerUrl",
    "logoUrl", "coverage", "coveragePolygon", "serviceAreas", "priority", "prepMinutes", "legacy",
  ];
  const output: Record<string, unknown> = {};
  for (const key of allowed) if (branch[key] !== undefined) output[key] = branch[key];
  output.id = branch.id;
  output.branchId = branch.id;
  output.name = branchDisplayName(branch);
  output.storeName = branchDisplayName(branch);
  return output;
}

export function resolveBranch(
  branches: BranchRecord[],
  lat: number,
  lng: number,
  requestedBranchId?: string | null,
): BranchResolution {
  const empty = (status: BranchResolution["status"]): BranchResolution => ({
    status, branch: null, branchId: null, branchName: null, distanceKm: null,
    deliveryPaisa: 0, nearest: null, candidates: [], canRequestExtension: false,
  });
  if (!validPoint(lat, lng)) return empty("invalid_location");
  const point = { lat, lng };
  const candidates = branches
    .filter((branch) => branch && typeof branch.id === "string" && branch.active !== false)
    .map((branch): BranchCandidate | null => {
      const kitchen = branchPoint(branch);
      if (!kitchen) return null;
      const distanceKm = haversineKm(kitchen, point);
      if (!Number.isFinite(distanceKm) || distanceKm > MAX_BRANCH_DISTANCE_KM) return null;
      return { branch, distanceKm, covering: coversPoint(branch, point, distanceKm) };
    })
    .filter((candidate): candidate is BranchCandidate => candidate !== null)
    .sort((a, b) => a.distanceKm - b.distanceKm || a.branch.id.localeCompare(b.branch.id));

  const nearest = candidates[0] ?? null;
  const covering = candidates.filter((candidate) => candidate.covering);
  const requested = requestedBranchId
    ? covering.find((candidate) => candidate.branch.id === requestedBranchId)
    : undefined;
  const selected = requested ?? covering.sort((a, b) => {
    const priority = finite(a.branch.priority, 100) - finite(b.branch.priority, 100);
    return priority || a.distanceKm - b.distanceKm || a.branch.id.localeCompare(b.branch.id);
  })[0] ?? null;

  if (!selected) {
    return {
      ...empty(candidates.length === 0 ? "no_branches" : "outside_coverage"),
      nearest,
      candidates,
      canRequestExtension: nearest !== null,
    };
  }

  const perKm = Math.max(0, Math.round(finite(selected.branch.deliveryPerKmAmountPaisa)));
  return {
    status: "deliverable",
    branch: selected.branch,
    branchId: selected.branch.id,
    branchName: branchDisplayName(selected.branch),
    distanceKm: selected.distanceKm,
    deliveryPaisa: selected.distanceKm <= 0 ? 0 : Math.ceil(selected.distanceKm) * perKm,
    nearest,
    candidates,
    canRequestExtension: false,
  };
}

/** Firestore's legacy config/store becomes the default branch during migration. */
export function legacyStoreAsBranch(store: Record<string, unknown> | null): BranchRecord | null {
  if (!store) return null;
  return {
    ...store,
    id: "default",
    name: typeof store.storeName === "string" ? store.storeName : "Zindabazar",
    code: "UND-ZIN",
    legacy: true,
    active: store.active !== false,
    priority: 100,
  };
}
