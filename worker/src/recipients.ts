/**
 * Who gets a notification: the single vendor account and the customer who owns
 * an order. Device tokens live at fcmTokens/{uid}/tokens/{token}.
 */
import type { Env } from "./env";
import { fsDelete, fsQuery } from "./firestore";

const VENDOR_UID_KEY = "vendor:uid";
const VENDOR_UID_TTL_SECONDS = 600;

export async function vendorUid(env: Env): Promise<string | null> {
  const cached = await env.UNDAL_KV.get(VENDOR_UID_KEY);
  if (cached) return cached;
  const vendors = await fsQuery(env, { collectionId: "vendor", limit: 1 });
  const uid = vendors[0]?.id ?? null;
  if (uid) {
    await env.UNDAL_KV.put(VENDOR_UID_KEY, uid, { expirationTtl: VENDOR_UID_TTL_SECONDS });
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
