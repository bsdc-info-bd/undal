/**
 * APK delivery (PART N-016 · P-006 · P-028).
 *
 * `/download` always points at the fixed asset name `undal.apk` on the newest
 * GitHub release, so the link the owner shares never changes.
 * `/latest.json` is the in-app update check: the build workflow posts the new
 * version here right after a successful release.
 */
import type { Env } from "../env";
import { HttpError, json, readJson, redirect } from "../util/http";
import { memoGet, memoPut, type KvBinding } from "../util/kv";

const LATEST_KV_KEY = "latest:json";

interface LatestRelease {
  versionName: string;
  versionCode: number;
  notes: string;
  apkUrl: string;
  releaseUrl: string;
  updatedAt: string;
}

function releaseUrls(env: Env): { apkUrl: string; releaseUrl: string } {
  const slug = env.GITHUB_REPO_SLUG || "bsdc-info-bd/undal";
  return {
    apkUrl: `https://github.com/${slug}/releases/latest/download/undal.apk`,
    releaseUrl: `https://github.com/${slug}/releases/latest`,
  };
}

export function handleDownload(env: Env): Response {
  return redirect(releaseUrls(env).apkUrl, 302);
}

export async function handleLatestJson(env: Env): Promise<Response> {
  const urls = releaseUrls(env);
  // Every app launch asks for this file, so the read is memoised for a minute:
  // a release is published a few times a month, and `max-age=300` already tells
  // the phone not to ask again for five minutes.
  const stored = (await memoGet<LatestRelease>(env.UNDAL_KV as KvBinding, LATEST_KV_KEY, {
    ttlMs: 60_000,
    json: true,
  })) as LatestRelease | null;
  const payload: LatestRelease = stored ?? {
    versionName: "1.0.0",
    versionCode: 1,
    notes: "First public build of the Undal HomeKitchen app.",
    apkUrl: urls.apkUrl,
    releaseUrl: urls.releaseUrl,
    updatedAt: new Date(0).toISOString(),
  };
  return json({ ...payload, apkUrl: payload.apkUrl || urls.apkUrl }, 200, {
    "cache-control": "public, max-age=300",
  });
}

/** Called by .github/workflows/android-build.yml after a successful release. */
export async function handleReleaseWebhook(env: Env, request: Request): Promise<Response> {
  const provided = request.headers.get("x-undal-release-secret") ?? "";
  if (!env.RELEASE_WEBHOOK_SECRET || provided !== env.RELEASE_WEBHOOK_SECRET) {
    throw new HttpError(403, "release-webhook-forbidden");
  }

  const body = await readJson<{ versionName?: string; versionCode?: number; notes?: string }>(request);
  const urls = releaseUrls(env);
  const payload: LatestRelease = {
    versionName: (body.versionName ?? "0.0.0").slice(0, 40),
    versionCode: Number.isFinite(body.versionCode) ? Number(body.versionCode) : 0,
    notes: (body.notes ?? "").slice(0, 500),
    apkUrl: urls.apkUrl,
    releaseUrl: urls.releaseUrl,
    updatedAt: new Date().toISOString(),
  };

  // Required, and the isolate memo is dropped with it: the next `/latest.json`
  // from this isolate must show the release that was just published.
  await memoPut(env.UNDAL_KV as KvBinding, LATEST_KV_KEY, JSON.stringify(payload), {
    memoTtlMs: 60_000,
    memoValue: payload,
    required: true,
  });
  return json({ ok: true, latest: payload });
}
