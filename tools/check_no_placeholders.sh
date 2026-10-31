#!/usr/bin/env bash
# ===========================================================================
# Undal HomeKitchen — LAW-1 gate.
#
# Fails the build if any shipped source file contains an unfinished-work marker
# (todo / fixme / lorem ipsum / demo / mock / stub / dummy ...).
#
# `PLACEHOLDER` is matched in upper case only, because Compose's own text-field
# API has a parameter named `placeholder`; LAW-1 is about unfinished work, not
# about that UI affordance. Every real marker in this repository is upper case.
#
# Two deliberate allowances, both documented configuration slots rather than
# unfinished code: `REPLACE_*` in wrangler.toml (substituted by deploy-worker)
# and `YOUR-SUBDOMAIN` in gradle.properties (substituted by the owner's secret).
# ===========================================================================
set -euo pipefail

PATTERN='TODO|FIXME|XXX:|lorem ipsum|Lorem ipsum|PLACEHOLDER|\bdemo\b|\bmock\b|\bstub\b|\bdummy\b|your code here|not implemented|coming soon|fill this in'

mapfile -t TARGETS < <(
  find app/src worker/src web firebase gradle \
    \( -path '*/node_modules' -o -path '*/build' -o -path '*/dist' -o -path '*/web/public' \) -prune -o -type f -print 2>/dev/null
  find . -maxdepth 1 -name '*.gradle.kts' -type f -print
  find . -maxdepth 1 -name 'gradle.properties' -type f -print
  find worker -maxdepth 1 -name 'wrangler.toml' -type f -print
  find web -maxdepth 1 -name 'wrangler.toml' -type f -print
)

if [ "${#TARGETS[@]}" -eq 0 ]; then
  echo "placeholder gate: nothing to scan"
  exit 0
fi

hits=0
for file in "${TARGETS[@]}"; do
  [ -f "$file" ] || continue
  matches=$(grep -nEI "$PATTERN" "$file" \
    | grep -vE 'REPLACE_[A-Z_]+|YOUR-SUBDOMAIN' || true)
  if [ -n "$matches" ]; then
    printf '\n%s\n' "$file"
    printf '%s\n' "$matches"
    hits=$((hits + 1))
  fi
done

if [ "$hits" -gt 0 ]; then
  printf '\nLAW-1 violation: %d file(s) contain unfinished-work markers.\n' "$hits"
  exit 1
fi

echo "placeholder gate: clean across ${#TARGETS[@]} files"
