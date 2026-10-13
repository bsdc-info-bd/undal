/**
 * `/.well-known/assetlinks.json` (PART N-011).
 *
 * Android verifies App Links against this document. The certificate fingerprint
 * comes from the APP_SIGNING_SHA256 secret, which the build workflow prints in
 * its job summary after the first signed release.
 */
import type { Env } from "../env";

export function handleAssetLinks(env: Env): Response {
  const fingerprints = (env.APP_SIGNING_SHA256 ?? "")
    .split(",")
    .map((entry) => entry.trim().toUpperCase())
    .filter((entry) => entry.length > 0);

  const payload = [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: {
        namespace: "android_app",
        package_name: env.APP_PACKAGE_NAME || "com.undal.syl.app",
        sha256_cert_fingerprints: fingerprints,
      },
    },
  ];

  return new Response(JSON.stringify(payload, null, 2), {
    headers: {
      "content-type": "application/json",
      "cache-control": "public, max-age=3600",
    },
  });
}
