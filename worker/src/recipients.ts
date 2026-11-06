/**
 * Who gets a notification: the single vendor account and the customer who owns
 * an order. Device tokens live at fcmTokens/{uid}/tokens/{token}.
 */
import type { Env } from "./env";
import { fsDelete, fsQuery } from "./firestore";
import { memoGet, memoPut, type KvBinding } from "./util/kv";

const VENDOR_UID_KEY = "vendor:uid";
const VENDOR_UID_TTL_SECONDS = 600;
/** The kitchen's uid changes once, when the owner re-provisions. Ten minutes is plenty. */
const VENDOR_UID_MEMO_TTL_MS = 10 * 60_000;

export async function vendorUid(env: Env): Promise<string | null> {
  const binding = env.UNDAL_KV as KvBinding;
  const cached = await memoGet<string>(binding, VENDOR_UID_KEY, { ttlMs: VENDOR_UID_MEMO_TTL_MS });
  if (cached) return cached;
  const vendors = await fsQuery(env, { collectionId: "vendor", limit: 1 });
  const uid = vendors[0]?.id ?? null;
  if (uid) {
    await memoPut(binding, VENDOR_UID_KEY, uid, {
      expirationTtl: VENDOR_UID_TTL_SECONDS,
      memoTtlMs: VENDOR_UID_MEMO_TTL_MS,
    });
  }
  return uid;
}

export async function tokensForUser(env: Env, uid: string | null | undefined): Promise<string[]> {
  if (!uid) return [];
  const documents = await fsQuery(env, {
    parentPath: `fcmTokens/${uid}`,
    collectionId: "tokens",
    limit: 20,
  });
  return documents
    .map((document) => document.id)
    .filter((token) => typeof token === "string" && token.length > 20);
}

export async function vendorTokens(env: Env): Promise<string[]> {
  const registrations = await tokensForBranch(env, "default");
  return registrations.map((entry) => entry.token);
}

/** Stable FCM topic for one branch. Ids are slugged by the admin API. */
export function branchTopic(branchId: string): string {
  const safe = branchId.toLowerCase().replace(/[^a-z0-9-]/g, "-").slice(0, 48) || "default";
  return `undal_branch_${safe}`;
}

/**
 * Staff assigned to a branch. During migration a legacy vendor document with no
 * branchIds receives only default/legacy orders, never another branch's alerts.
 */
export async function vendorUidsForBranch(env: Env, branchId?: string | null): Promise<string[]> {
  const branch = branchId?.trim() || "default";
  const vendors = await fsQuery(env, { collectionId: "vendor", limit: 100 });
  return vendors.filter((vendor) => {
    if (vendor.disabled === true) return false;
    const assigned = Array.isArray(vendor.branchIds)
      ? vendor.branchIds.filter((id): id is string => typeof id === "string")
      : [];
    return assigned.includes(branch) || assigned.includes("*") ||
      (branch === "default" && assigned.length === 0);
  }).map((vendor) => vendor.id);
}

export async function tokensForBranch(env: Env, branchId?: string | null): Promise<Array<{ uid: string; token: string }>> {
  const uids = await vendorUidsForBranch(env, branchId);
  const lists = await Promise.all(uids.map(async (uid) => (await tokensForUser(env, uid)).map((token) => ({ uid, token }))));
  return lists.flat();
}

/** Removes tokens FCM reported as dead so the fan-out list stays clean. */
export async function purgeTokens(env: Env, uid: string | null | undefined, tokens: string[]): Promise<void> {
  if (!uid || tokens.length === 0) return;
  await Promise.all(
    tokens.map((token) =>
      fsDelete(env, `fcmTokens/${uid}/tokens/${token}`).catch(() => undefined),
    ),
  );
}
