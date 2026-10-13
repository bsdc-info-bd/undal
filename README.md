# Undal HomeKitchen

Single-kitchen food delivery for **Sylhet, Bangladesh** — 100 % cash on delivery,
one Android app that contains both portals (customer *and* kitchen), one
Cloudflare Worker for web, deep links and push, and a GitHub Actions pipeline
that builds, signs and publishes everything.

**Everything in this repository is operated from a phone browser.** There is no
laptop step, no CLI step, no paid service anywhere in the stack.

> Status of the build plan: **Response 1 of 5** is complete — foundation,
> security rules, Worker, pipelines and the counted feature list.
> `FEATURES.md` tracks the 235 features and ticks each one only when it is
> really implemented.

---

## 1 · The eight values this project needs (PART A)

| # | Key | What to put here | Where it comes from |
| --- | --- | --- | --- |
| A1 | `GITHUB_OWNER` | your GitHub username | your profile |
| A2 | `GITHUB_REPO` | `undal` | this repository |
| A3 | `PACKAGE_NAME` | `com.undal.syl.app` | **fixed** — never change it |
| A4 | `FIREBASE_PROJECT_ID` | e.g. `undal-kitchen` | Firebase Console |
| A5 | `CLOUDINARY_CLOUD_NAME` | e.g. `dxxxxxx` | Cloudinary dashboard |
| A6 | `CLOUDINARY_UPLOAD_PRESET` | `undal_unsigned` | Cloudinary → Settings → Upload |
| A7 | `WORKER_DOMAIN` | `undal.your-subdomain.workers.dev` | Cloudflare dashboard |
| A8 | `ORDER_SOUND_PATH` | `assets/sound/undal_order_alert.mp3` | already in this repository |

Canonical constants used everywhere: package `com.undal.syl.app` · minSdk 24 ·
target/compile SDK 35 · timezone **Asia/Dhaka** (no daylight saving, ever) ·
currency **BDT, stored as integer paisa** (100 paisa = ৳1) · order sound
resource `undal_order_alert` · APK asset name `undal.apk`.

---

## 2 · One-time setup, all from the phone browser

### 2a · Create the repository secrets

GitHub → your repository → **Settings → Secrets and variables → Actions → New
repository secret**. Add all of these (the preflight step in every workflow
lists exactly what is missing if you forget one):

| Secret | Where the value comes from |
| --- | --- |
| `GOOGLE_SERVICES_JSON` | Firebase Console → Project settings → Your apps → Android → `google-services.json` (paste the whole file) |
| `FIREBASE_WEB_API_KEY` | Firebase Console → Project settings → General → Web API Key |
| `FIREBASE_SA_JSON` | Firebase Console → Project settings → Service accounts → **Generate new private key** (paste the whole JSON) |
| `FIREBASE_RTDB_URL` | Firebase Console → Realtime Database → the URL at the top of the data tab |
| `VENDOR_EMAIL` | the kitchen login, e.g. `kitchen@undal.local` |
| `VENDOR_PASSWORD` | 12+ characters; this is the kitchen's own password |
| `CLOUDINARY_CLOUD_NAME` | Cloudinary dashboard, top bar |
| `CLOUDINARY_UPLOAD_PRESET` | `undal_unsigned` (must be an **unsigned** preset) |
| `WORKER_HOST` | `undal.your-subdomain.workers.dev` (no `https://`) |
| `WORKER_BASE_URL` | the same host **with** `https://` |
| `CLOUDFLARE_API_TOKEN` | Cloudflare → My Profile → API Tokens → Create Token → *Edit Cloudflare Workers* template |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare → Workers & Pages → right sidebar → Account ID |
| `SUPPORT_PHONE` | optional; the number customers may call |
| `RELEASE_WEBHOOK_SECRET` | invent any long random string (the Worker secret is pushed from it) |

`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` and
`APP_SIGNING_SHA256` are added in step 3a below — the keystore workflow prints
them for you.

### 2b · Firebase (Console, browser only)

1. **Authentication → Sign-in method → Email/Password → Enable.**
2. **Firestore Database → Create database → Production mode → region
   `asia-south1`** (closest to Sylhet).
3. **Realtime Database → Create database → region
   `asia-southeast1`** and copy the URL into `FIREBASE_RTDB_URL`.
4. **Project settings → Your apps → Add app → Android** with package
   `com.undal.syl.app`, then download `google-services.json`.
5. **Project settings → Service accounts → Generate new private key.**

Keep the Spark (free) plan: everything here fits inside it.

### 2c · Cloudinary and Cloudflare (browser only)

* Cloudinary → create the free account → **Settings → Upload → Upload presets →
  Add upload preset**, name it `undal_unsigned`, signing mode **Unsigned**,
  allowed formats `jpg,png,webp`, maximum 10 MB.
* Cloudflare → create the free account → **Workers & Pages → Create →
  Worker**, name it `undal`, and copy the `*.workers.dev` sub-domain.
  You do **not** need to create the KV namespace by hand — the deploy workflow
  finds it by title and creates it if it is missing.

---

## 3 · Run the workflows — five buttons, in this order

GitHub → **Actions** → pick the workflow → **Run workflow**.

### 3a · `keystore` (once, ever)

Creates the release signing keystore **inside the runner** and prints
`KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`, `APP_SIGNING_SHA256` and
`KEYSTORE_BASE64` in the run summary. Copy each one into the matching
repository secret, and download the `undal-release-keystore` artifact and keep
it somewhere safe.

> **Without that keystore**, Android will refuse future updates, because the
> signature of every release must match. Keep the artifact.

### 3b · `bootstrap` (once)

* verifies every secret and stops with a checklist if one is missing,
* writes the Gradle wrapper (8.10.2),
* copies `assets/sound/undal_order_alert.mp3` → `app/src/main/res/raw/`,
* writes `app/google-services.json` from the secret (never committed),
* creates the kitchen Firebase account, grants the `vendor: true` claim and
  writes the `vendor/{uid}` document,
* commits the wrapper and the order sound back to the branch.

### 3c · `seed-config` (once, re-runnable)

Publishes `config/store` (hours, per-km fee, radius, minimum order, kitchen
pin), `config/promos` (the offer rotation the 18:00 cron walks through) and
`counters/orders` (shortId counter starting at `UND-1001`), then deploys the
Firestore rules, the composite indexes and the Realtime Database rules.

> The seeded kitchen pin is Zindabazar, Sylhet. **Move it** with the pin picker
> in the app (V-081) — the radius and the delivery fee depend on the real spot.

### 3d · `deploy-worker`

Typechecks the Worker, creates or reuses the `undal-kv` namespace, pushes the
Worker secrets, deploys and smoke-tests `https://<your-worker>/healthz`.

### 3e · `android-build`

Runs the no-unfinished-work gate, the unit tests, then builds and signs the
release APK, publishes it to the rolling release and tells the Worker about the
new version.

**Push to `main` runs this automatically.**

---

## 4 · Get the app onto the phone

| Step | What to do |
| --- | --- |
| 1 | GitHub → your repository → **Releases → latest** → download `undal.apk` |
| 2 | Open the file; Android asks to allow installs from this source → **Allow** |
| 3 | Install, open, and grant notifications and location when asked |
| 4 | The direct link never changes: `https://github.com/<owner>/undal/releases/latest/download/undal.apk` |

---

## 5 · Kitchen phone first-run checklist

1. Log in with `VENDOR_EMAIL` / `VENDOR_PASSWORD`.
2. Walk the **device diagnostics** wizard (V-004/V-087) and tap through, in
   order: notification permission, battery optimization exemption, autostart
   allowance, sound test.
3. Store settings → **move the pin** to the real kitchen location.
4. Delivery settings → set the per-km fee and the radius (the circle preview
   shows exactly what customers you are promising).
5. Schedule → apply the standard 09:00–22:00 template (or your own hours).
6. Dashboard → **Open store**.
7. Place a test order from a second phone and watch the alert loop.

---

## 6 · When the kitchen phone stays silent

Order alerts are deliberately loud: a high-priority data push wakes the app,
which starts a foreground service that loops the real Undal sound at alarm
volume until somebody accepts or rejects.

| Symptom | Fix |
| --- | --- |
| No sound, order visible in the app | Settings → Apps → Undal → **Notifications** enabled, then diagnostics → sound test |
| Sound stops after a while | Battery optimization **must** be disabled for Undal (below) |
| No push at all while the app is closed | Battery optimization + autostart (below); the in-app listener still catches the order |
| Alerts late at night only | Doze on the device; the diagnostics screen shows the exact state |

Battery and autostart paths by brand (Android 13–15):

| Brand | Battery optimization exemption | Autostart |
| --- | --- | --- |
| Xiaomi / Redmi / POCO | Settings → Apps → Undal → Battery saver → **No restrictions** | Settings → Apps → Undal → Autostart **on** |
| Oppo / Realme / OnePlus | Settings → Battery → App battery management → Undal → **Don't optimize** | Settings → App management → Undal → Allow auto launch |
| Vivo / iQOO | Settings → Battery → Background power consumption management → Undal → **Allow** | Settings → Apps → Autostart → Undal **on** |
| Samsung | Settings → Battery and device care → Battery → Background usage limits → **Never sleeping apps** → add Undal | (not required on One UI) |
| Stock Android | Settings → Apps → Undal → Battery → **Unrestricted** | (not required) |

**Honest caveat:** Android can always throttle background work under extreme
memory pressure, and a device that is switched off receives nothing. The app is
built for that reality: the alert has three independent triggers — the live
Firestore listener, the RTDB `orderLive` mirror and the Worker's FCM push — and
a fourth safety net on the server side: if the kitchen goes silent for fifteen
minutes while the store is open, the Worker closes the store automatically and
pushes you a **Reopen now** notice, so a sleeping phone can never quietly take
orders it does not see.

---

## 7 · What costs nothing

| Service | Free allowance used here | Notes |
| --- | --- | --- |
| Firebase Spark | Firestore 1 GiB, Realtime DB 1 GB, Auth 50k MAU, FCM unlimited | no billing account needed |
| Cloudinary free | 25 credits/month | photos are compressed to ≤1600 px before upload |
| Cloudflare Workers free | 100k requests/day, KV 1 GB, cron triggers included | one Worker, two crons |
| GitHub Actions free | 2 000 minutes/month for private repos, unlimited for public | a full Android build is ~4–6 minutes |
| OpenStreetMap tiles | free, attribution kept | osmdroid with a unique user agent |
| Google Maps | free embed view | no API key, display only |

---

## 8 · Version matrix (locked)

| Component | Version | Why this one |
| --- | --- | --- |
| Gradle wrapper | 8.10.2 | the version this build contract was written against |
| Android Gradle plugin | 8.8.2 | matches Gradle 8.10.2 exactly |
| Kotlin / kapt | 2.1.21 | verified to resolve with AGP 8.8.2 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 24 | Android 15 features on 24+ devices |
| Firebase BoM | 33.16.0 | verified resolvable with compileSdk 35 |
| Compose BoM | 2025.06.01 | Material 3 stable line |
| Hilt | 2.56.2 | last release verified on Maven Central for this Kotlin |
| osmdroid · Cloudinary · Coil | 6.1.20 · 3.1.3 · 3.2.0 | free tiles, unsigned uploads, image loading |
| Worker toolchain | wrangler 4 · TypeScript 5.9 · workers-types 5 | CI installs and typechecks on every deploy |

**Upgrade path (later, one commit):** AGP 9.x + Gradle 9 + compileSdk 36 +
Kotlin 2.4 + Firebase BoM 35 + Compose BoM 2026.09 + Hilt 2.60 + Coil 3.6.
Nothing in this repository blocks that move; it is deliberately deferred so the
first build of the project cannot fail on a bleeding-edge version skew.

---

## 9 · Repository map

```
undal/
├── README.md                       this runbook
├── FEATURES.md                     the counted 235-feature checklist
├── assets/sound/                   the official Undal order sound
├── app/                            the Android app (customer + kitchen)
├── firebase/                       rules, indexes, firebase.json
├── worker/                         Cloudflare Worker (FCM v1, crons, web, deep links)
├── tools/                          preflight, web/app parity gate, shape gate, provisioning and seeding scripts
├── gradle/libs.versions.toml       single source of truth for every version
└── .github/workflows/              keystore · bootstrap · seed-config · deploy-worker · android-build
```

---

---

## 10 · The five-response contract

| Response | Ships |
| --- | --- |
| **1 — done** | Gradle foundation, manifest, Firebase rules + indexes, complete Worker, all workflows, tooling, feature list |
| 2 | Design system, core utilities + tests, maps kit, background services (order sound, prep timer, heartbeat), diagnostics, onboarding and auth |
| 3 | Customer portal (all C-### features) |
| 4 | Kitchen portal (all V-### features) |
| 5 | Web store PWA, OG cards, download page, final acceptance run, README acceptance table |

Steering the build with the task owner's command phrases: `Continue`,
`Go to Response {2|3|4|5}`, `Re-emit file {path}`,
`Run acceptance`, `Fix: {description}`, `Status`.

---

## 11 · Acceptance table (PART R)

Sixteen things that have to be true before this is called finished. The **result**
column is what was actually run for this build; the **your phone** column is the
step only you can do, because it needs your own Firebase project.

| # | What is checked | How | Result |
| --- | --- | --- | --- |
| 1 | The Worker compiles | `cd worker && npx tsc --noEmit` | **0 errors** |
| 2 | The Android sources hang together without a compiler | `python3 tools/check_kotlin_shape.py` | **120 files**: balanced, no split strings, every `com.undal.syl.app` import and member call resolves |
| 3 | No unfinished markers anywhere | `bash tools/check_no_placeholders.sh` | **clean across 168 files** |
| 4 | The web prices food exactly like the app | `node tools/check-web-rules.mjs` | **17 rule families · 130 comparisons**, all from the app's own test vectors (82 assertions shared, app-only rules declared out loud) |
| 5 | Every storefront page renders | local `wrangler dev` probe | `/ /store /login /basket /checkout /track /about /download /manifest.webmanifest /sw.js /icons/icon-512.png /healthz` → **all 200** |
| 6 | The API refuses politely | `curl` probes | no web key → a sentence, not a stack trace; order without a token → `missing-id-token`; rate buckets 90/120/300 per minute per IP |
| 7 | Icons and QR codes are real images | decoded with `pngjs`; QR matrix compared with `qrcode@1.5.4` | icons **1744 / 5868 / 4054 bytes**, QR **exact match on 43 URLs** |
| 8 | The web store installs as an app | manifest + service worker + `/download` with two QR codes and the unknown-sources guide | shipped (P-014, P-016) |
| 9 | Deep links go somewhere sensible | `/dish/{id}`, `/combo/{id}` → `/item/{id}`; `/order/{id}` shows the public status page, and the app-open page only to the app's own user agent | shipped; `.well-known/assetlinks.json` served |
| 10 | Money is one rule everywhere | parity gate, `money.format` | **11 vectors**, including `৳ 1,00,000` (lakh grouping) and `৳ 250.5` (trailing paisa trimmed) |
| 11 | The delivery fee is one rule everywhere | parity gate, `money.deliveryFeePaisa` | **6 vectors** through the Worker, the server-side pricing and the browser basket |
| 12 | A tampered browser cannot change a price | the browser sends dish ids and quantities only; the server prices from live Firestore; unknown coupon types discount nothing; rules re-check `discount ≤ subtotal` and `total = subtotal + delivery − discount` | by construction + rules |
| 13 | The radius and pin gate speak in words | parity gate, `distance.*` | **10 vectors**: an unpublished or half-published pin refuses with a sentence instead of inventing a fee |
| 14 | Failures are visible to you, not to customers | Crashlytics (user id, custom keys, non-fatal records, release builds only), Analytics (order placed, accept latency, offer opened), Performance (cold start → first frame, order submission) | wired in `core/telemetry` |
| 15 | It is usable without sight | 71 labelled controls and icons, 48 dp minimum targets, TalkBack reading order and spoken state on the timeline and countdown ring | `core/design/UndalAccessibility.kt` |
| 16 | Nothing here costs money | Spark plan, Cloudinary free, Workers free, OpenStreetMap tiles, Actions free — see §7 | no billing account anywhere |

**What only your project can prove.** Four checks need your real Firebase project,
your Cloudinary account and a signed keystore, so run them once after §3e:

1. **The build and the signature** — `android-build` finishes green and
   `releases/latest/download/undal.apk` installs on the customer phone.
2. **A real order, end to end** — place one order in the app, watch the kitchen
   phone ring within seconds, accept it, and follow the status to *delivered*.
3. **A real push** — the order alert and the status update arrive with the app in
   the background (that is the `FCM HTTP v1` path through the Worker).
4. **A real photo** — change one dish photo and see it appear on the menu, the
   web store and the shared dish link.

Everything in this table was run on the build date and the numbers are real; the
four checks above are the first things to do on the phone, in that order.
