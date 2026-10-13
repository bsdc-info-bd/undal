#!/usr/bin/env bash
# ===========================================================================
# Undal HomeKitchen — secret preflight.
#
# Usage:  bash tools/preflight.sh NAME_A NAME_B ...
# Fails fast (exit 1) with a copy-paste repair checklist when a required
# repository secret is missing, so a workflow never half-runs.
# ===========================================================================
set -uo pipefail

if [ "$#" -eq 0 ]; then
  echo "preflight: no variable names supplied"
  exit 0
fi

missing=()
for name in "$@"; do
  value="${!name:-}"
  if [ -z "$value" ]; then
    missing+=("$name")
  fi
done

if [ "${#missing[@]}" -gt 0 ]; then
  printf '\n=== Undal preflight failed ===\n'
  printf 'Missing %d required secret(s):\n' "${#missing[@]}"
  for name in "${missing[@]}"; do
    printf '  · %s\n' "$name"
  done
  cat <<'CHECKLIST'

Add each one on your phone:
  GitHub → repository → Settings → Secrets and variables → Actions → New repository secret

  GOOGLE_SERVICES_JSON       Firebase Console → Project settings → Your apps → Android app → google-services.json
                             (paste the whole file, including the outer braces)
  FIREBASE_WEB_API_KEY       Firebase Console → Project settings → General → Web API Key
  FIREBASE_SA_JSON           Firebase Console → Project settings → Service accounts → Generate new private key
                             (paste the entire JSON file)
  FIREBASE_RTDB_URL          Firebase Console → Realtime Database → copy the URL shown at the top
                             (looks like https://PROJECT-default-rtdb.asia-southeast1.firebasedatabase.app)
  VENDOR_EMAIL               The kitchen login email, e.g. kitchen@undal.local
  VENDOR_PASSWORD            The kitchen login password (12+ characters)
  CLOUDINARY_CLOUD_NAME      Cloudinary dashboard → top bar cloud name
  CLOUDINARY_UPLOAD_PRESET   Cloudinary → Settings → Upload → Upload presets → the exact name of
                             your Unsigned preset (this project's account uses bsdc_unsigned)
  WORKER_HOST                Cloudflare → Workers & Pages → undal → the workers.dev host, without https://
                             (looks like undal.your-subdomain.workers.dev)
  WORKER_BASE_URL            The same host WITH https://
  CLOUDFLARE_API_TOKEN       Cloudflare → My Profile → API Tokens → Create Token → Edit Cloudflare Workers template
  CLOUDFLARE_ACCOUNT_ID      Cloudflare dashboard → Workers & Pages → right sidebar → Account ID
  KEYSTORE_BASE64            Run the "keystore" workflow once, then copy the value it prints into this secret
  KEYSTORE_PASSWORD          The password printed by the "keystore" workflow
  KEY_ALIAS                  undal
  KEY_PASSWORD               The key password printed by the "keystore" workflow
  APP_SIGNING_SHA256         Printed in the android-build summary after the first signed build
  RELEASE_WEBHOOK_SECRET     Any long random string you invent; paste the same value in the Worker secret step
                             (deploy-worker pushes it for you from this secret)
  SUPPORT_PHONE              Optional: the number customers should call, e.g. 01700000000

CHECKLIST
  exit 1
fi

printf 'preflight ok: %s\n' "$*"
