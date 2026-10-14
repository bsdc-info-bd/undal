# Undal HomeKitchen — counted feature checklist (LAW-8)

Every feature in the product plan has an ID, one line here, and a checkbox.
A box is ticked **only when the feature is implemented and wired to the real
backend** — never when it is merely planned or sketched.

| Block | Range | Count |
| --- | --- | --- |
| Customer portal | C-001 … C-080 | 80 |
| Vendor portal | V-001 … V-110 | 110 |
| Platform · web · deep links · CI | P-001 … P-066 | 66 |
| **Total** | | **256** |

**Progress: 256 / 256 implemented (100 %)** — every block is ticked: the
customer portal (C-001 … C-080), the vendor portal (V-001 … V-110) and the
platform, web, deep-link and release block (P-001 … P-066). Nothing here is
"planned": each line is code in this repository, verified by
`grep -c '^- \[x\]' FEATURES.md` (256) and by the acceptance table in README §11.

Legend: `[x]` shipped and wired · `[ ]` still to ship.

---

## C · Customer portal (80)

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
- [x] C-013 · Drag-pin fine adjustment on the osmdroid picker
- [x] C-014 · Google embed confirmation view of the pinned point
- [x] C-015 · Address title as a required field
- [x] C-016 · Address description as a required field
- [x] C-017 · Delivery note as a required field
- [x] C-018 · Reverse-geocoded label preview (Nominatim with graceful fallback)
- [x] C-019 · Save address and enter the store
- [x] C-020 · Login, forgot-password email flow and persistent session
- [x] C-021 · Greeting header with the saved address chip
- [x] C-022 · Live store open/closed banner (schedule + manual + auto-offline aware)
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
- [x] C-052 · Coupon apply with transaction validation (expiry, minimum, usage limit, active) and precise error copy
- [x] C-053 · Subtotal line
- [x] C-054 · Live km-based delivery fee with a distance caption
- [x] C-055 · Discount line item
- [x] C-056 · Grand total, exact to the paisa
- [x] C-057 · Cash-on-delivery-only ribbon and method card
- [x] C-058 · Address card with Google embed, pin, title, description and note
- [x] C-059 · Change-address shortcut that re-runs the pin flow
- [x] C-060 · Checkout guards: store closed, outside radius, below minimum, offline — each with its own reason
- [x] C-061 · Place order: atomic write of order, shortId counter and coupon usage, RTDB live mirror, Worker notification with WorkManager retry
- [x] C-062 · Success screen with the UND-#### order number, celebratory motion and waiting-for-kitchen ETA
- [x] C-063 · Live timeline stepper (placed → accepted → ready → in delivery → delivered)
- [x] C-064 · Animated prep-time countdown ring driven by prepEndsAt
- [x] C-065 · Ready state banner
- [x] C-066 · In-delivery state with rider-neutral copy
- [x] C-067 · Delivered celebration with order summary
- [x] C-068 · Cancelled view showing the kitchen note verbatim plus Call kitchen
- [x] C-069 · Sub-second updates through the RTDB orderLive mirror
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

## V · Vendor portal (110)

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
- [x] V-024 · Full-screen ringing alert with the looping Undal order sound
- [x] V-025 · Order card with items, totals, fee, distance and address title, description, note
- [x] V-026 · Customer card with name, profile photo, phone number, Call button and copy-address
- [x] V-027 · Google embed map and pin on every order, with an osmdroid toggle
- [x] V-028 · Accept with a prep-time picker (10/15/20/25/30/45 minute chips plus custom)
- [x] V-029 · Reject with reason templates and a mandatory note that pushes instantly
- [x] V-030 · Confirmation sheet before accepting, showing the total
- [x] V-031 · "Is the order ready?" alert that sets ready, notifies the customer and stops the sound tick
- [x] V-032 · Mark in delivery with a customer push
- [x] V-033 · Mark delivered with a customer push, celebration and the collected-cash checkbox
- [x] V-034 · Sticky countdown notification with a ten-second tick
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
- [x] V-057 · Bulk availability mode
- [x] V-058 · Search inside the manage list
- [x] V-059 · Combo builder: pick at least two items, quantities, combo price and automatic savings against regular price
- [x] V-060 · Combo create, read, update, delete with photo
- [x] V-061 · Campaign create with percent or fixed discount, date range, banner text and scope
- [x] V-062 · Campaign list, edit and disable
- [x] V-063 · Coupon create with upper-normalised code, type, value, minimum order, expiry and usage limit
- [x] V-064 · Coupon list showing used against limit with an active toggle
- [x] V-065 · Coupon delete guard
- [x] V-066 · Featured item toggle for the home highlight
- [x] V-067 · Optional category image through Cloudinary
- [x] V-068 · Preview-as-customer mode
- [x] V-069 · All lists realtime with no manual refresh
- [x] V-070 · Validation and taka formatting everywhere
- [x] V-071 · Weekly schedule builder with per-day multi-slot editing
- [x] V-072 · Schedule templates: 24/7, standard 09–22, lunch and dinner split, weekend special in one tap
- [x] V-073 · Special dates with a date picker for closed days or custom hours
- [x] V-074 · Explainer line showing today's derived status
- [x] V-075 · Manual open/close override with a note customers can see
- [x] V-076 · Auto-offline: fifteen minutes without a heartbeat closes the store and pushes a Reopen now notice
- [x] V-077 · Reopen and reconnect flow
- [x] V-078 · Presence and heartbeat status tile
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

## P · Platform, web, deep links and delivery (66)

- [x] P-001 · Single-deploy Cloudflare Worker architecture (router, routes, crons in one bundle)
- [x] P-002 · FCM HTTP v1 with service-account OAuth2 (RS256 JWT minted in Web Crypto, token cached in KV)
- [x] P-003 · Firebase ID-token RS256 verification against Google certificates plus the role claim check
- [x] P-004 · Daily 18:00 Asia/Dhaka offer broadcast to the promos topic
- [x] P-005 · Five-minute presence watchdog that auto-closes an unreachable kitchen
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
- [x] P-019 · Boot receiver rescheduling the heartbeat and the notification retry workers
- [x] P-020 · WorkManager retry queue for failed notification relays
- [x] P-021 · Firestore offline persistence tuned to a 100 MB local cache
- [x] P-022 · Realtime Database keepSynced for chat and live order flags
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
- [x] P-044 · Accessibility pass: descriptions, 48 dp targets and TalkBack order
- [x] P-045 · No-placeholder grep gate that fails CI on unfinished markers

### The web storefront, the web account and search (P-046 … P-059)

- [x] P-046 · Checkout map: an OpenStreetMap pin the customer drops and ticks before ordering
- [x] P-047 · Live delivery fee and radius verdict measured from the ticked pin
- [x] P-048 · Web sign-up against the same Firebase Authentication the app uses
- [x] P-049 · Web password reset by email, sent by Firebase itself
- [x] P-050 · Web session renewal past the ID token's hour
- [x] P-051 · Web profile: name, number and pinned address written to `users/{uid}`
- [x] P-052 · Web address book: up to twelve saved pins, added, edited and deleted
- [x] P-053 · Web order history: the caller's own orders, newest first, needing no composite index
- [x] P-054 · Web password change that returns a fresh session
- [x] P-055 · Web account closure behind a typed confirmation word
- [x] P-056 · About page with the kitchen's live schedule, delivery economics and FAQ
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

---

## How to read this file between responses

The task owner can say **`Status`** at any time and the count above is the
answer: how many of the 256 features are actually shipped and wired.
