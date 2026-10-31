# Undal HomeKitchen — counted feature checklist (LAW-8)

Every feature in the product plan has an ID, one line here, and a checkbox.
A box is ticked **only when the feature is implemented and wired to the real
backend** — never when it is merely planned or sketched.

| Block | Range | Count |
| --- | --- | --- |
| Customer portal | C-001 … C-085 | 85 |
| Vendor portal | V-001 … V-112 | 112 |
| Platform · web · deep links · CI | P-001 … P-088 | 88 |
| **Total** | | **285** |

**Progress: 285 / 285 implemented (100 %)** — every block is ticked: the
customer portal (C-001 … C-085), the vendor portal (V-001 … V-112) and the
platform, web, deep-link and release block (P-001 … P-088). Nothing here is
"planned": each line is code in this repository, verified by
`grep -c '^- \[x\]' FEATURES.md` (285) and by the acceptance table in README §11.

Legend: `[x]` shipped and wired · `[ ]` still to ship.

---

## C · Customer portal (85)

- [x] C-001 · Branded splash with animated logo (core-splashscreen)
- [x] C-002 · Role auto-router from auth state plus Firestore role document
- [x] C-003 · Registration: full name field with validation
- [x] C-004 · Email format validation
- [x] C-005 · Bangladesh mobile validation `^01[3-9]\d{8}$`
- [x] C-006 · Password length, confirmation and strength meter
- [x] C-007 · Inline field errors on every form
- [x] C-008 · Firebase account creation plus users document in one transaction
- [x] C-009 · Verify-email banner with resend action
- [x] C-010 · Location permission rationale sheet
- [x] C-011 · Runtime permission flow with deny → app settings deep link
- [x] C-012 · GPS auto-capture with accuracy indicator
- [x] C-013 · Drag-pin fine adjustment on the osmdroid picker: the pin is drawn at the exact centre of the card and the map moves under it (the web picker's model), so the pin cannot be stranded in a corner of the card, and nudge arrows still move it six metres a tap
- [x] C-014 · The pin is placed on an OpenStreetMap street map drawn by osmdroid in this process — drag the pin, long-press the map, nudge it five metres at a time, or take the GPS fix. There is no embed and no third party between the app and the picture of the street. The map is built once per screen and torn down only when the screen goes (it used to detach itself the instant it was built, which left every map a blank grid), and a drag inside a scrolling screen moves the pin, not the page
- [x] C-015 · Address title as a required field
- [x] C-016 · Address description as a required field
- [x] C-017 · Delivery note as a required field
- [x] C-018 · Reverse-geocoded label preview (Nominatim with graceful fallback)
- [x] C-019 · Save address and enter the store
- [x] C-020 · Login, forgot-password email flow and persistent session
- [x] C-021 · Greeting header with the saved address chip
- [x] C-022 · Live store open/closed banner: the hours decide *when*, the owner's switch *whether*, the watchdog may pause — it turns at the boundary minute with nothing in Firestore changing, and a closed store names its next opening
- [x] C-023 · Today's hours line derived from weekly and special schedules
- [x] C-024 · Category chips with single-select and All
- [x] C-025 · Subcategory filter row
- [x] C-026 · Instant search across name, description and tags
- [x] C-027 · Campaign hero banners
- [x] C-028 · Lazy menu grid with shimmer loading state
- [x] C-029 · Price display with strike-through when discounted
- [x] C-030 · Sold-out badge with add disabled
- [x] C-031 · Combo badge
- [x] C-032 · Vegetarian dot
- [x] C-033 · Spice level icons
- [x] C-034 · Popular chip derived from real order counts
- [x] C-035 · Quick add to cart with in-card quantity stepper
- [x] C-036 · Pull to refresh
- [x] C-037 · Sort by price ascending, price descending or popularity
- [x] C-038 · Hand-designed empty and error states
- [x] C-039 · Full-photo item header with shared-element transition
- [x] C-040 · Description, price and discount block
- [x] C-041 · Combo contents list with a savings line
- [x] C-042 · Quantity stepper
- [x] C-043 · Per-line cooking instructions field
- [x] C-044 · Related items row
- [x] C-045 · Share dish producing a deep link with an OG preview
- [x] C-046 · Add-to-cart confirmation snackbar with View cart
- [x] C-047 · Unavailable item guard states
- [x] C-048 · Cart list with live totals
- [x] C-049 · Inline quantity editing
- [x] C-050 · Swipe to remove with undo
- [x] C-051 · Clear cart
- [x] C-052 · Coupon apply through one shared rule (percent, fixed, free delivery, cap, daily window, chosen days, expiry, usage limit) with precise error copy that names the fact — the date a code ended, the days it runs, the taka still short — and the best automatic coupon applied to the basket with no code typed. An expired code is refused with the date *and the minute* it ended, in Dhaka, so "it is not expired" can be settled by reading the sentence; an expiry typed by hand — epoch seconds, a number as text, a bare date — is read the way it was meant, never as 1970
- [x] C-053 · Subtotal line
- [x] C-054 · Live km-based delivery fee with a distance caption
- [x] C-055 · Discount line item
- [x] C-056 · Grand total, exact to the paisa
- [x] C-057 · Cash-on-delivery-only ribbon and method card
- [x] C-058 · Address card with the OpenStreetMap pin, the coordinates in writing, "open in a map app", "copy coordinates", title, description and note
- [x] C-059 · Change-address shortcut that re-runs the pin flow
- [x] C-060 · Checkout guards: store closed, outside radius, below minimum, offline — each with its own reason
- [x] C-061 · Place order: atomic write of order, shortId counter and coupon usage, RTDB live mirror, Worker notification with WorkManager retry; whether the kitchen is taking orders is re-read from the live store document inside the same transaction
- [x] C-062 · Success screen with the UND-#### order number, celebratory motion and waiting-for-kitchen ETA
- [x] C-063 · Live timeline stepper (placed → accepted → ready → in delivery → delivered)
- [x] C-064 · Animated prep-time countdown ring driven by prepEndsAt
- [x] C-065 · Ready state banner
- [x] C-066 · In-delivery state with rider-neutral copy
- [x] C-067 · Delivered celebration with order summary
- [x] C-068 · Cancelled view showing the kitchen note verbatim plus Call kitchen
- [x] C-069 · Sub-second updates through the RTDB orderLive mirror — the customer's tracking page merges the document and the mirror and never shows the older of the two, so a cancellation the mirror carried first appears immediately, with the kitchen's reason, while the history list and the tracking page can no longer disagree
- [x] C-070 · In-order chat
- [x] C-071 · Unread message badge
- [x] C-072 · Call the kitchen through the dialer
- [x] C-073 · Push notification on every status transition
- [x] C-074 · Receipt-style order detail with the full breakdown
- [x] C-075 · Paged order history with detail view
- [x] C-076 · Reorder that rebuilds the cart and skips unavailable items with a notice
- [x] C-077 · No customer self-cancel anywhere — rules-enforced, replaced by Contact vendor to cancel
- [x] C-078 · In-app notification inbox with read state, images and order deep links
- [x] C-079 · Profile: edit name, phone, photo (Cloudinary), re-pin address, change password
- [x] C-080 · Logout and full account deletion (auth, user document, tokens) behind a confirmation sheet
- [x] C-081 · Offer strip on the store screen: the kitchen's live coupons from the same public board the web store prints, each with its headline, its conditions, whether it applies itself or needs the code typed, and a tap that copies the code
- [x] C-082 · The system's animation preference is honoured in both portals: the loading skeleton holds still, a page intro is already in place on the first frame and a total does not count up, on a phone that has been asked to stop moving things — read from `ANIMATOR_DURATION_SCALE`, the switch Android's own "Remove animations" accessibility setting writes
- [x] C-083 · A cart line total counts to its new value when a quantity changes, formatted by the app's own `Money`, clamped so it can never show a number the server did not charge
- [x] C-084 · The delivery pin is a control, not a picture: on the web the map frame measures itself and writes its own height when a stylesheet could not, and it drags with touch events on an engine that has never heard of Pointer Events; in the app a map is never asked to centre on a coordinate that is not a place, so an address saved without a pin shows the kitchen's city rather than the Atlantic with a pin in the corner
- [x] C-085 · The menu's chip row starts with “Everything”, every filter and every sort ends at the food instead of back on the hero, and a filtered menu says in words what it is showing — how many choices, which words, which section — with the one tap that undoes it

## V · Vendor portal (112)

- [x] V-001 · Kitchen login using the account provisioned from repository secrets at bootstrap
- [x] V-002 · Stay logged in for a year: persisted session, token refresh, session keeper watchdog and role re-check
- [x] V-003 · `vendor=true` custom claim gate on every vendor surface
- [x] V-004 · First-login device diagnostics wizard (notifications, battery exemption, autostart hints)
- [x] V-005 · FCM token registration with a health chip
- [x] V-006 · Heartbeat status proven on the dashboard
- [x] V-007 · Credential change through the password reset email flow
- [x] V-008 · Wrong-role guard
- [x] V-009 · Rationale copy explaining why the kitchen phone needs these permissions
- [x] V-010 · Logout hidden in settings behind a confirmation
- [x] V-011 · Today's order count
- [x] V-012 · Today's revenue in exact paisa
- [x] V-013 · Pending and active queue size
- [x] V-014 · Week bar chart drawn with a custom Canvas from real data
- [x] V-015 · Live new-order queue cards with an age-seconds ticker
- [x] V-016 · Store status hero showing Open, Closed or the auto-closed reason
- [x] V-017 · One-tap open and close with a customer-facing note
- [x] V-018 · Mini list of running prep timers
- [x] V-019 · Calm low-attention state when there are no orders
- [x] V-020 · Shortcuts grid to menu, orders, schedule and chat
- [x] V-021 · Stream of today's status events
- [x] V-022 · Pull to refresh over realtime everywhere
- [x] V-023 · Realtime new-order stream under one second
- [x] V-024 · Full-screen ringing alert with the looping Undal order sound, kept audible by a ten-second self-check that rebuilds the player (or falls back to the phone's own alarm tone) if the tone ever stops on its own
- [x] V-025 · Order card with items, totals, fee, distance and address title, description, note
- [x] V-026 · Customer card with name, profile photo, phone number, Call button and copy-address
- [x] V-027 · Every order carries its pin on the street map — the kitchen's copy shows the kitchen and the customer framed together, with the coordinates, and hands the point to the rider's own navigation app
- [x] V-028 · Accept with a prep-time picker (10/15/20/25/30/45 minute chips plus custom)
- [x] V-029 · Reject with reason templates and a mandatory note that pushes instantly
- [x] V-030 · Confirmation sheet before accepting, showing the total
- [x] V-031 · "Is the order ready?" alert that sets ready, notifies the customer and stops the sound tick
- [x] V-032 · Mark in delivery with a customer push
- [x] V-033 · Mark delivered with a customer push, celebration and the collected-cash checkbox
- [x] V-034 · Sticky countdown notification with a ten-second tick — the tick sounds only while the promise is still being kept, stops at the deadline, is silenced by a real switch in store settings, and the countdown ends itself the moment the order is marked ready, collected, delivered or cancelled (on this phone or on the other kitchen phone) instead of ticking through a cancelled order
- [x] V-035 · Overdue escalation with a red banner and an extra push
- [x] V-036 · Per-order chat shortcut
- [x] V-037 · Unread customer message badge on the order
- [x] V-038 · Rendered timeline showing who did what and when
- [x] V-039 · Long-press to copy the customer phone number
- [x] V-040 · Order detail share-sheet text
- [x] V-041 · Filter tabs: New, Preparing, Ready, Out, Done, Cancelled
- [x] V-042 · Live age badge with a colour ramp
- [x] V-043 · Actions disabled by the state machine so impossible taps cannot happen
- [x] V-044 · Sound test button in settings
- [x] V-045 · Multi-device consistency: another device accepting stops this alert through RTDB
- [x] V-046 · Offline queue of status writes backed by Firestore persistence
- [x] V-047 · Vendor-cancelled orders visible in history with their note
- [x] V-048 · Daily closing digest card
- [x] V-049 · Category create, read, update, delete
- [x] V-050 · Subcategory create, read, update, delete
- [x] V-051 · Drag reorder writing the order field
- [x] V-052 · Item create with name, description, price, compare price, photo, veg flag, spice, tags, featured
- [x] V-053 · Item photo pick, compress, Cloudinary upload with progress
- [x] V-054 · Item edit with dirty-state awareness
- [x] V-055 · Item delete with confirmation and a summary of orders affected
- [x] V-056 · Instant availability toggle reflected in the customer app
- [x] V-057 · Bulk mode: availability, home-highlight and "move to section" in one atomic batch, with select-all over the current filter — plus quick filters for sold out, missing photo and not-featured dishes, a menu-health card that says what needs a look before service, and dish duplication that opens the copy in the editor switched off
- [x] V-058 · Search inside the manage list
- [x] V-059 · Combo builder: pick at least two items, quantities, combo price and automatic savings against regular price
- [x] V-060 · Combo create, read, update, delete with photo
- [x] V-061 · Campaign create with percent or fixed discount, date range, banner text and scope
- [x] V-062 · Campaign list, edit and disable
- [x] V-063 · Coupon studio: percent, fixed or free delivery; a maximum discount cap; a minimum basket; a daily window that may cross midnight; chosen days; an expiry and a usage limit — with a live preview of what it costs on a typical basket
- [x] V-064 · Coupon list showing the headline, conditions, used-against-limit, reach (applies itself or code only, shown on the web store or not) and an active toggle
- [x] V-065 · Coupon delete guard, keeping the used count across edits and moving a renamed code — used count included — instead of losing its history; an expiry can be cleared again (no end date), and the editor says the minute customers stop getting it
- [x] V-066 · Featured item toggle for the home highlight
- [x] V-067 · Optional category image through Cloudinary
- [x] V-068 · Preview-as-customer mode
- [x] V-069 · All lists realtime with no manual refresh
- [x] V-070 · Validation and taka formatting everywhere
- [x] V-071 · Weekly schedule builder with per-day multi-slot editing
- [x] V-072 · Schedule templates: 24/7, standard 09–22, lunch and dinner split, weekend special in one tap
- [x] V-073 · Special dates with a date picker for closed days or custom hours
- [x] V-074 · Explainer line showing today's derived status
- [x] V-075 · The owner's switch, with a note customers can see: on, the store opens and closes itself on its hours every day with nobody tapping anything; Pause holds it closed until it is opened again. The switch never opens a store outside its hours
- [x] V-076 · Auto-offline: ten minutes without a heartbeat while the store is taking orders closes it and pushes a notice saying why; the Worker reopens it by itself when the phone comes back inside thirty minutes. A phone left off outside the hours pauses nothing, so tomorrow's service still opens by itself
- [x] V-077 · Reopen and reconnect flow: the Worker restores a blink by itself, lifts the pause when the phone returns after hours (the schedule keeps the store closed until its next service), and asks the owner to confirm after a long outage mid-service. The opening reminder goes only to a paused store — a switched-on store opens itself
- [x] V-078 · Presence analytics: heartbeat age, uptime percentage from the Worker's own five-minute samples, the day's offline windows and every automatic pause and restore
- [x] V-079 · Delivery per-kilometre fee editor
- [x] V-080 · Delivery radius slider from one to fifteen kilometres with a live circle preview
- [x] V-081 · Store location pin picker with a grab-my-GPS button
- [x] V-082 · Minimum order amount editor
- [x] V-083 · Store profile with name, banner and logo upload
- [x] V-084 · Every edit reaching customers live through Firestore snapshots
- [x] V-085 · Save guards and confirmation snackbars
- [x] V-086 · Sound test and tick-sound test in settings
- [x] V-087 · Full device diagnostics screen
- [x] V-088 · Timezone hard-locked to Asia/Dhaka in every display
- [x] V-089 · Schedule customer-view parity check
- [x] V-090 · Special-date conflict warnings
- [x] V-091 · Warning that a radius change affects delivery logic
- [x] V-092 · Fee preview calculator for a typed distance
- [x] V-093 · Undo for the last schedule change from a local snapshot
- [x] V-094 · Export today's orders as CSV through the share sheet
- [x] V-095 · App version and build information
- [x] V-096 · Bundled offline-readable privacy and policy pages
- [x] V-097 · Chat list per order with unread counts
- [x] V-098 · Realtime thread on Realtime Database
- [x] V-099 · Quick replies such as On the way and Need ten more minutes
- [x] V-100 · Typing indicator from RTDB flags
- [x] V-101 · Read receipts
- [x] V-102 · Push when a customer writes
- [x] V-103 · Text-only chat in v1 to keep the free tier lean
- [x] V-104 · Jump to the order from a chat
- [x] V-105 · Smart scroll to the first unread message
- [x] V-106 · Crash-safe session restore across rotation and process death
- [x] V-107 · Keyboard-aware forms everywhere
- [x] V-108 · Haptic feedback on status actions
- [x] V-109 · Vendor inbox mirroring the kitchen alert history
- [x] V-110 · One-tap share of the store link
- [x] V-111 · The vendor store settings and the portal stages arrive with the same capped, draw-only reveal as the customer screens, and the kitchen's queue skeleton holds still when the system's animations are off — while the order alert's sound, full-screen intent, notification and watchdog stay exactly as they were, because no alarm is ever gated on an animation
- [x] V-112 · The kitchen's own pin keeps the zoom the owner chose: a pinch, a double tap or the zoom buttons are no longer animated back to street zoom by the next centre report, and a pin nobody has placed yet is captioned “No pin saved for this address yet” instead of being read out as 0.00000, 0.00000

## P · Platform, web, deep links and delivery (88)

- [x] P-001 · Single-deploy Cloudflare Worker architecture (router, routes, crons in one bundle)
- [x] P-002 · FCM HTTP v1 with service-account OAuth2 (RS256 JWT minted in Web Crypto, token cached in KV)
- [x] P-003 · Firebase ID-token RS256 verification against Google certificates plus the role claim check
- [x] P-004 · Daily 18:00 Asia/Dhaka offer broadcast to the promos topic
- [x] P-005 · Five-minute presence watchdog: ten minutes of silence pauses an open kitchen, restores it when the phone returns, and records a per-day online/offline document
- [x] P-006 · KV: OAuth token cache, menu snapshot cache and latest.json release manifest
- [x] P-007 · Edge rate limiting per caller per minute with a 429 JSON response
- [x] P-008 · Security headers (CSP, frame options, content-type options) on every web route
- [x] P-009 · Deep-link routes for dish, combo, order and store
- [x] P-010 · Smart open through an intent URL with a website fallback
- [x] P-011 · App Links verification document served at /.well-known/assetlinks.json
- [x] P-012 · Custom `undal://` scheme fallback declared in the manifest and used by the intent page
- [x] P-013 · Full web store with browsing, search, cart, login and cash-on-delivery checkout
- [x] P-014 · Installable manifest with a service-worker offline shell
- [x] P-015 · Open Graph and Twitter cards per dish using the real Cloudinary photo
- [x] P-016 · Download page with QR code and an unknown-sources guide
- [x] P-017 · Web page stating honestly which features are app-only
- [x] P-018 · Order sound asset copied into res/raw by the workflows
- [x] P-019 · Boot receiver rescheduling the heartbeat, the retry workers and the alert watchdog, plus a nine-minute alarm watchdog that rebuilds the order watcher and the heartbeat after Android kills or a swipe closes the app
- [x] P-020 · WorkManager retry queue for failed notification relays
- [x] P-021 · Firestore offline persistence tuned to a 100 MB local cache
- [x] P-022 · Realtime Database disk persistence, with chat and live order flags kept synced by the screens that read them rather than wholesale at every cold start
- [x] P-023 · Crashlytics wired and monitored for a crash-free rate
- [x] P-024 · Analytics events for order placed, accept latency and offer opens
- [x] P-025 · Performance traces for cold start and order submission
- [x] P-026 · Release signing from repository secrets
- [x] P-027 · versionCode from the workflow run number and semantic versionName
- [x] P-028 · Fixed release asset name `undal.apk`
- [x] P-029 · Bootstrap workflow provisioning the wrapper, sound, Firebase config and kitchen account
- [x] P-030 · Seed-config workflow publishing store, promos and counter documents
- [x] P-031 · Vendor provisioning through the Identity Toolkit REST API with the vendor claim
- [x] P-032 · Firestore and Realtime Database rules deployed from Actions
- [x] P-033 · Composite indexes deployed from Actions
- [x] P-034 · Secret preflight that fails fast with a copy-paste repair checklist
- [x] P-035 · Phone-only README runbook with the acceptance table
- [x] P-036 · Acceptance-test checklist executed and reported
- [x] P-037 · Money utility with unit tests
- [x] P-038 · Haversine distance utility with unit tests
- [x] P-039 · Order state machine with unit tests
- [x] P-040 · Paisa formatting such as `৳ 1,250`
- [x] P-041 · Dhaka time utilities for cron and schedule alignment
- [x] P-042 · Consistent motion system (spring specs and shared-element transitions)
- [x] P-043 · Automatic dark and light theme with dynamic colour on Android 12+
- [x] P-044 · Accessibility pass: descriptions, 48 dp targets, TalkBack order, and a back button that works on every screen — step back, then portal home, then a double-press exit with the door left open
- [x] P-045 · No-placeholder grep gate that fails CI on unfinished markers

### The web storefront, the web account and search (P-046 … P-059)

- [x] P-046 · Checkout map: an OpenStreetMap pin the customer drops and ticks before ordering, with a labelled "Use my current location" button and the fix's accuracy said out loud. A closed kitchen is said before the address is typed and refused by the order route with its next opening; an ID token that expired on an open page is renewed once instead of answering with its bare code
- [x] P-047 · Live delivery fee and radius verdict measured from the ticked pin
- [x] P-048 · Web sign-up against the same Firebase Authentication the app uses
- [x] P-049 · Web password reset by email, sent by Firebase itself
- [x] P-050 · Web session renewal past the ID token's hour
- [x] P-051 · Web profile: name, number and pinned address written to `users/{uid}`
- [x] P-052 · Web address book: up to twelve saved pins, added, edited and deleted
- [x] P-053 · Web order history: the caller's own orders, newest first, needing no composite index
- [x] P-054 · Web password change that returns a fresh session
- [x] P-055 · Web account closure behind a typed confirmation word
- [x] P-056 · About page with the kitchen's live schedule, delivery economics and FAQ; the menu page's open/closed banner follows the same hours rule as the checkout
- [x] P-057 · `robots.txt` and `sitemap.xml` built from the live catalogue
- [x] P-058 · Structured data: Restaurant, WebSite, Menu, Product, FAQPage, BreadcrumbList
- [x] P-059 · Canonical URL, Open Graph and locale metadata on every page

### The cleanup 502 and the alert wire (P-060 … P-066)

- [x] P-060 · Cleanup scan fallback when the composite index was never deployed
- [x] P-061 · Cleanup mirror queue in KV when the Realtime Database URL is missing
- [x] P-062 · `hint` and `detail` carried by every Worker refusal and shown by the app
- [x] P-063 · Cleanup diagnosis (`dryRun`) reporting verdict, strategy and the index link
- [x] P-064 · Firestore indexes deployed by the deploy-worker workflow
- [x] P-065 · APNs delivery headers alongside the Android alert priority
- [x] P-066 · `docs/ALERT-RELIABILITY.md`: the ladder, the manifest and the device acceptance matrix

### Opening the app without dying (P-067 … P-072)

- [x] P-067 · One door to Android's foreground-service API: `UndalForeground` takes the promise in the first statement of every `onStartCommand`, a stop is a plain `stopService`, and `tools/check_foreground_contract.py` refuses the old shape in CI
- [x] P-068 · A startup budget in the crash guard: a crash inside eight seconds of opening gets one reopen and then an honest screen, so the app can never loop itself back onto the glass
- [x] P-069 · `SafeModeActivity`: what Undal shows when it cannot start — the exception in words, a copyable report naming version, device and frames, one tap to try again and one to call the kitchen, built out of nothing that can itself fail
- [x] P-070 · The opening frame costs nothing: WorkManager, osmdroid, Cloudinary, the notification channels and the watchdog all start off the main thread, and each step fails alone rather than taking the process with it
- [x] P-071 · One synchronous answer to "is this phone the kitchen", read by the alert watchdog, the boot receiver, the push handler and the fifteen-minute heartbeat — no `Task` read as though `getResult()` waited for it
- [x] P-072 · The role read off the ID token by the router corrects the role the session guessed at, so a kitchen phone never spends the first second of a cold start as a customer's

### The website on Pages, prerendered (P-073 … P-088)

- [x] P-073 · The customer website runs on Cloudflare Pages: one catch-all Function calls the same router the Worker calls, and the artwork, manifest and service worker are excluded from Functions entirely, so a page load costs no invocation for its own files
- [x] P-074 · Every public page in the sitemap is prerendered at deploy time by the production router — the route list *is* the sitemap, so a dish cannot be advertised to Google and absent from the build — per origin, so a custom domain never inherits a pages.dev canonical URL
- [x] P-075 · A snapshot answers only a request with no cookies at all: a basket, a session or an `Authorization` header is rendered, because both cookies are `HttpOnly` and no script can put a customer's basket back into a file — and only a request with no query string, because `/store?q=bhaji` is a different page from the `/store` the build wrote, and a search answered from the whole-menu file is a search that silently did nothing
- [x] P-076 · When a live render fails and a snapshot exists, the customer gets a menu from five minutes ago (`x-undal-prerender: stale`, cached thirty seconds) instead of an error page
- [x] P-077 · Cloudflare's own cookies — `__cf_bm`, `cf_clearance`, `_cfuvid`, `__cfruid`, `cf_chl_*` — are not identity, so the fast path serves browsers and not only `curl`
- [x] P-078 · Cache API edge page cache keyed by the basket cookie, 30 s for the storefront with 900 s stale-while-revalidate, never storing a `set-cookie` or a `no-store`, and reporting itself in `x-undal-edge`
- [x] P-079 · A KV gateway in front of every namespace read and write: a per-isolate memo, single-flight on cold reads, a write brake on optional writes, and `kv.usage` / `kv.savings` published at `/healthz` so the budget is a number rather than a surprise
- [x] P-080 · Rate limiting is memory-first and escalates to KV only past 60% of a limit, which removed a KV read *and* a KV write from every request the site serves
- [x] P-081 · The re-alert cursor writes and deletes only when it has something to say: a cron that ran 1,440 times a day against an empty KV now costs nothing at all when nothing is waiting
- [x] P-082 · A dark theme that is an additive palette — no light-mode declaration changed value — decided by an inline head script before the first paint, following the system until the customer chooses, remembered after that, and toggled by a real button with `aria-pressed`
- [x] P-083 · A motion layer: scroll reveals with a capped stagger, a shimmer under a photograph that is still travelling, ripples, toasts that Escape dismisses, a reading-position bar, a sticky nav that knows it is stuck, a basket bar within reach of the thumb, the flight of a dish into it, and cross-document view transitions — every decoration off under `prefers-reduced-motion`, and every one of them tested against the real markup on a virtual clock
- [x] P-084 · The four facts a customer asks before reading a dish name — fee per kilometre, radius, minimum order, cash on delivery — as a definition list above the menu, from the live store document
- [x] P-085 · HTML 404 and 500 pages, negotiated: a browser gets a page with four ways back to the menu, the app and every webhook keep the JSON they already parse, and both are `noindex`
- [x] P-086 · One canonical address per page: `/store/`, `//store` and `/store/index.html` are a 301 to `/store` with the query string intact — never on a POST, because a 301 may drop the body of the no-JavaScript basket form
- [x] P-087 · Largest-contentful-paint discipline: the first three photographs eager and the first at high priority, the rest lazy, the dish page's own photograph square with its space reserved, and both photo hosts preconnected before a picture is asked for
- [x] P-088 · Stylesheets that survive an old engine: a pixel height before every `clamp()`, a floor under every box whose height came only from `aspect-ratio`, offsets written out instead of `inset`, prefixed filters and selection, and no map button smaller than 44 CSS px on a phone — because a dropped declaration is not a small difference, it is a collapsed box

---

## How to read this file between responses

The task owner can say **`Status`** at any time and the count above is the
answer: how many of the 285 features are actually shipped and wired.
