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
  return tokensForUser(env, await vendorUid(env));
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
