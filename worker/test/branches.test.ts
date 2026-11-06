import assert from "node:assert/strict";
import { test } from "node:test";
import {
  coversPoint,
  haversineKm,
  pointInPolygon,
  resolveBranch,
  type BranchRecord,
} from "../src/branches";

const branch = (id: string, lat: number, lng: number, radiusKm: number, extra: Record<string, unknown> = {}): BranchRecord => ({
  id, name: id, active: true, storeLat: lat, storeLng: lng, radiusKm,
  deliveryPerKmAmountPaisa: 2000, priority: 100, ...extra,
});

test("haversine is finite, symmetric and zero at the same pin", () => {
  const a = { lat: 24.8949, lng: 91.8687 };
  const b = { lat: 24.9049, lng: 91.8787 };
  assert.equal(haversineKm(a, a), 0);
  assert.ok(Math.abs(haversineKm(a, b) - haversineKm(b, a)) < 1e-12);
  assert.ok(haversineKm(a, b) > 1 && haversineKm(a, b) < 2);
});

test("a polygon is authoritative and includes its boundary", () => {
  const polygon = [
    { lat: 24, lng: 91 }, { lat: 24, lng: 92 }, { lat: 25, lng: 92 }, { lat: 25, lng: 91 },
  ];
  assert.equal(pointInPolygon({ lat: 24.5, lng: 91.5 }, polygon), true);
  assert.equal(pointInPolygon({ lat: 24, lng: 91.5 }, polygon), true);
  assert.equal(pointInPolygon({ lat: 25.5, lng: 91.5 }, polygon), false);
  const kitchen = branch("polygon", 24.5, 91.5, 50, { coveragePolygon: polygon });
  assert.equal(coversPoint(kitchen, { lat: 25.2, lng: 91.5 }), false, "radius must not enlarge a polygon");
});

test("covering branch wins over a nearer branch that cannot deliver", () => {
  const customer = { lat: 24.9, lng: 91.87 };
  const nearestButClosedArea = branch("near", 24.901, 91.871, 0.01);
  const covering = branch("covering", 24.93, 91.89, 5);
  const result = resolveBranch([nearestButClosedArea, covering], customer.lat, customer.lng);
  assert.equal(result.status, "deliverable");
  assert.equal(result.branchId, "covering");
  assert.equal(result.nearest?.branch.id, "near");
});

test("routing priority breaks overlap, distance breaks equal priority, id is deterministic", () => {
  const at = { lat: 24.9, lng: 91.87 };
  const preferred = branch("preferred", 24.95, 91.9, 10, { priority: 10 });
  const normal = branch("normal", 24.901, 91.871, 10, { priority: 100 });
  assert.equal(resolveBranch([normal, preferred], at.lat, at.lng).branchId, "preferred");
  assert.equal(resolveBranch([normal, branch("other", 24.95, 91.9, 10)], at.lat, at.lng).branchId, "normal");
});

test("outside customers are routed to the nearest branch for an extension request", () => {
  const result = resolveBranch([
    branch("far", 25.5, 92.5, 1),
    branch("nearest", 24.96, 91.93, 1),
  ], 24.9, 91.87);
  assert.equal(result.status, "outside_coverage");
  assert.equal(result.branch, null);
  assert.equal(result.nearest?.branch.id, "nearest");
  assert.equal(result.canRequestExtension, true);
});

test("a requested branch cannot bypass coverage and an inactive branch is invisible", () => {
  const at = { lat: 24.9, lng: 91.87 };
  const valid = branch("valid", 24.91, 91.88, 5);
  const invalid = branch("invalid", 24.91, 91.88, 0.01);
  const result = resolveBranch([valid, invalid], at.lat, at.lng, "invalid");
  assert.equal(result.branchId, "valid");
  assert.equal(resolveBranch([branch("off", 24.9, 91.87, 5, { active: false })], at.lat, at.lng).status, "no_branches");
});

test("delivery fee uses exact paisa and whole billed kilometres", () => {
  const result = resolveBranch([branch("fee", 24.91, 91.88, 5, { deliveryPerKmAmountPaisa: 2050 })], 24.9, 91.87);
  assert.equal(result.status, "deliverable");
  assert.ok(result.distanceKm! > 1);
  assert.equal(result.deliveryPaisa, Math.ceil(result.distanceKm!) * 2050);
});
