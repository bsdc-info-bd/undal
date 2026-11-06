# Multi-branch migration runbook

This runbook moves the original single-store records to the reserved `default`
branch and only then enables `config/store.branchIsolationEnabled`.

**No migration or production activation is performed by CI, by installing an
APK, or by opening the admin.** The commands below are deliberate operator
actions. `scan` and a plain `backfill` are read-only. Never paste the service
account into the repository, a pull request, a screenshot, or a chat message.

## Before touching production data

1. Register the Android application id `com.undal.syl.vendor` in the same
   Firebase project and configure `GOOGLE_SERVICES_VENDOR_JSON`. A compile-only
   CI fixture is not a production Firebase registration.
2. Deploy the Worker, Firestore/RTDB rules, Firestore indexes, admin Pages app,
   and the new customer/vendor Android releases through the normal reviewed
   deployment process. Keep the compatibility flag **false** during this stage.
3. Retire customer builds that create orders directly in Firestore. The new
   rules deliberately make checkout Worker-authoritative (`allow create: if
   false`), so an older direct-write client cannot safely coexist with those
   rules. Use the staged rollout/enforced-upgrade path before enabling them.
4. In the admin, create and verify every real branch, including its kitchen pin,
   delivery coverage, fees, hours and accepting-orders state. Assign each active
   staff account explicit branches. Staff must sign out and back in after an
   assignment so Firebase Auth refreshes `branchIds` and `branchAccess`.
5. Keep a Firebase backup/export and a tested restore procedure. This script
   does not delete orders or rewrite money, but no migration should be the only
   copy of a recovery plan.

The Firestore emulator suite covers the branch order query, legacy gate,
customer ownership, staff profile restrictions, coupons, presence history,
RTDB branch queries, immutable mirror ownership and chat writes. That is a
pre-deployment check, not evidence that a particular production dataset is
ready; the migration scan is the dataset check.

## Credentials

Use the same `FIREBASE_SA_JSON` service-account JSON and `FIREBASE_RTDB_URL`
used by the existing provisioning/deployment tooling, in a trusted local shell
or a manually approved secret-bearing runner. Node 22 or newer is sufficient;
the script has no npm dependencies.

```sh
export FIREBASE_SA_JSON='...secret JSON supplied through the secret manager...'
export FIREBASE_RTDB_URL='https://YOUR_REAL_PROJECT-default-rtdb.REGION.firebasedatabase.app'
```

Use the real project URL, not that illustrative value. The service account
needs Firestore, Realtime Database and Identity Toolkit read access, plus write
access only for the apply/activation steps.

## 1. Scan (read-only)

```sh
node tools/migrate-branches.mjs scan
```

The JSON report scans every page of:

- Firestore `menuItems`, `categories`, `offers`, `coupons`, `orders`, `chats`;
- RTDB `orderLive` and `chats` (shallow key listing, then bounded record pages);
- `branches/default` and all assigned branch ids;
- active legacy `vendor/{uid}` profiles joined to Firebase Auth claims.

`readyToActivate` must be false until every blocker is resolved. A missing,
null or blank `branchId` is eligible for `default`; a malformed non-string
value is reported as invalid instead of being silently overwritten. Staff need
explicit matching `branchIds` and `branchAccess` claims. Super-admin claims are
not downgraded by this script.

## 2. Backfill (also read-only until explicitly confirmed)

Preview the same operation without writes:

```sh
node tools/migrate-branches.mjs backfill
```

Apply it only after reviewing the report:

```sh
node tools/migrate-branches.mjs backfill --apply --confirm=BACKFILL_DEFAULT
```

The apply operation:

- creates `branches/default` from the legacy store configuration if it is
  absent and the legacy kitchen pin is valid;
- patches only `branchId: "default"` with a field mask and an exists
  precondition on eligible legacy records;
- pages Firestore in groups of 200 documents and commits at most 400 writes;
- pages RTDB records in groups of 40 and uses multi-path branch-only patches;
- runs a **complete second scan** after writing. It does not infer success from
  accepted writes, so a concurrent legacy write or failed page remains a
  blocker.

It never changes an order total, status, customer id, menu price, coupon use,
message, branch assignment, or the compatibility flag. Re-running it is safe:
records already owned by `default` or a real branch are not patched.

If staff blockers remain, assign them in the admin, have them sign out/in, and
repeat the scan/backfill verification. Do not manufacture broad `*` staff
claims to make the report green.

## 3. Activate isolation (the only flag-writing command)

First run one final read-only scan and inspect `readyToActivate` and `blockers`:

```sh
node tools/migrate-branches.mjs scan
```

Only when the report is clean:

```sh
node tools/migrate-branches.mjs activate --confirm=ACTIVATE_BRANCH_ISOLATION
```

Activation performs its own fresh scan and refuses to write if anything is
missing. Only then does it set `branchIsolationEnabled: true` and server-stamped
activation metadata on `config/store`.

After activation, verify with non-production test accounts where possible:

- each branch sees only its own live orders, coupons, presence and chats;
- default-branch staff can still see the migrated legacy records;
- customers see their own orders across branches but cannot write an order;
- a vendor account in the customer APK is handed to the vendor app, and vice
  versa;
- checkout, pricing, notifications, auto-close/reopen and staff sign-in all work
  against the deployed Worker and indexes.

Do not casually toggle the flag back. After real branches contain live data,
rollback requires a reviewed data/access plan, not a console edit.

## Exit status and reports

- `0`: the requested operation completed; for an applied backfill, verification
  has no activation blockers.
- `2`: the backfill finished but verification still has blockers. Read
  `verification.blockers`; activation was **not** attempted.
- `1`: authentication, an upstream read/write, confirmation, or an activation
  precondition failed. Activation does not write when its fresh scan is not
  clean.

Reports contain document/user ids for diagnosis. Treat them as operational
metadata and do not commit production reports to Git.
