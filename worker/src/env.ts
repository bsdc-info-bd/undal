/**
 * Bindings and configuration visible to the Undal Worker.
 *
 * `vars` come from wrangler.toml, `secrets` from `wrangler secret put`.
 * The service-account JSON is the only credential: it is used both for the
 * FCM HTTP v1 OAuth2 flow and for every Firestore / RTDB REST call.
 */
export interface Env {
  // --- bindings ------------------------------------------------------------
  UNDAL_KV: KVNamespace;

  // --- secrets -------------------------------------------------------------
  FIREBASE_SA_JSON: string;
  FIREBASE_RTDB_URL: string;
  APP_SIGNING_SHA256: string;
  RELEASE_WEBHOOK_SECRET: string;

  // --- vars ----------------------------------------------------------------
  CLOUDINARY_CLOUD_NAME: string;
  APP_PACKAGE_NAME: string;
  GITHUB_REPO_SLUG: string;
  PROMO_TOPIC: string;
  /**
   * Firebase Web API key: the public identifier used for anonymous sign-in on
   * the web store. It is not a secret (it ships inside every Firebase app), but
   * it is kept in the deploy workflow's secrets so no key ever lands in a file.
   */
  FIREBASE_WEB_API_KEY: string;
}

/** Google service-account document shape (only the fields we rely on). */
export interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

let cachedServiceAccount: ServiceAccount | null = null;

/** Parses and validates FIREBASE_SA_JSON exactly once per isolate. */
export function serviceAccount(env: Env): ServiceAccount {
  if (cachedServiceAccount) return cachedServiceAccount;
  let parsed: unknown;
  try {
    parsed = JSON.parse(env.FIREBASE_SA_JSON);
  } catch {
    throw new Error("FIREBASE_SA_JSON is not valid JSON — re-run `wrangler secret put FIREBASE_SA_JSON`.");
  }
  const candidate = parsed as Partial<ServiceAccount>;
  if (!candidate.project_id || !candidate.client_email || !candidate.private_key) {
    throw new Error("FIREBASE_SA_JSON is missing project_id / client_email / private_key.");
  }
  cachedServiceAccount = {
    project_id: candidate.project_id,
    client_email: candidate.client_email,
    private_key: candidate.private_key,
    token_uri: candidate.token_uri,
  };
  return cachedServiceAccount;
}

/** Firebase project id — never hard-coded, always read from the credential. */
export function projectId(env: Env): string {
  return serviceAccount(env).project_id;
}

/** Realtime Database origin with no trailing slash. */
export function rtdbOrigin(env: Env): string {
  return env.FIREBASE_RTDB_URL.replace(/\/+$/, "");
}
