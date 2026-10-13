# Undal HomeKitchen — counted feature checklist (LAW-8)

Every feature in the product plan has an ID, one line here, and a checkbox.
A box is ticked **only when the feature is implemented and wired to the real
backend** — never when it is merely planned or sketched.

| Block | Range | Count |
| --- | --- | --- |
| Customer portal | C-001 … C-080 | 80 |
| Vendor portal | V-001 … V-110 | 110 |
| Platform · web · deep links · CI | P-001 … P-045 | 45 |
| **Total** | | **235** |

**Progress: 63 / 235 implemented (27 %)** — verified by `grep -c '^- \[x\]' FEATURES.md`.

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
- [ ] C-021 · Greeting header with the saved address chip
- [ ] C-022 · Live store open/closed banner (schedule + manual + auto-offline aware)
- [ ] C-023 · Today's hours line derived from weekly and special schedules
- [ ] C-024 · Category chips with single-select and All
- [ ] C-025 · Subcategory filter row
- [ ] C-026 · Instant search across name, description and tags
- [ ] C-027 · Campaign hero banners
- [ ] C-028 · Lazy menu grid with shimmer loading state
- [ ] C-029 · Price display with strike-through when discounted
- [ ] C-030 · Sold-out badge with add disabled
- [ ] C-031 · Combo badge
- [ ] C-032 · Vegetarian dot
- [ ] C-033 · Spice level icons
- [ ] C-034 · Popular chip derived from real order counts
- [ ] C-035 · Quick add to cart with in-card quantity stepper
- [ ] C-036 · Pull to refresh
- [ ] C-037 · Sort by price ascending, price descending or popularity
- [ ] C-038 · Hand-designed empty and error states
- [ ] C-039 · Full-photo item header with shared-element transition
- [ ] C-040 · Description, price and discount block
- [ ] C-041 · Combo contents list with a savings line
- [ ] C-042 · Quantity stepper
- [ ] C-043 · Per-line cooking instructions field
- [ ] C-044 · Related items row
- [ ] C-045 · Share dish producing a deep link with an OG preview
- [ ] C-046 · Add-to-cart confirmation snackbar with View cart
- [ ] C-047 · Unavailable item guard states
- [ ] C-048 · Cart list with live totals
- [ ] C-049 · Inline quantity editing
- [ ] C-050 · Swipe to remove with undo
- [ ] C-051 · Clear cart
- [ ] C-052 · Coupon apply with transaction validation (expiry, minimum, usage limit, active) and precise error copy
- [ ] C-053 · Subtotal line
- [ ] C-054 · Live km-based delivery fee with a distance caption
- [ ] C-055 · Discount line item
- [ ] C-056 · Grand total, exact to the paisa
- [ ] C-057 · Cash-on-delivery-only ribbon and method card
- [ ] C-058 · Address card with Google embed, pin, title, description and note
- [ ] C-059 · Change-address shortcut that re-runs the pin flow
- [ ] C-060 · Checkout guards: store closed, outside radius, below minimum, offline — each with its own reason
- [ ] C-061 · Place order: atomic write of order, shortId counter and coupon usage, RTDB live mirror, Worker notification with WorkManager retry
- [ ] C-062 · Success screen with the UND-#### order number, celebratory motion and waiting-for-kitchen ETA
- [ ] C-063 · Live timeline stepper (placed → accepted → ready → in delivery → delivered)
- [ ] C-064 · Animated prep-time countdown ring driven by prepEndsAt
- [ ] C-065 · Ready state banner
- [ ] C-066 · In-delivery state with rider-neutral copy
- [ ] C-067 · Delivered celebration with order summary
- [ ] C-068 · Cancelled view showing the kitchen note verbatim plus Call kitchen
- [ ] C-069 · Sub-second updates through the RTDB orderLive mirror
- [ ] C-070 · In-order chat
- [ ] C-071 · Unread message badge
- [ ] C-072 · Call the kitchen through the dialer
- [ ] C-073 · Push notification on every status transition
- [ ] C-074 · Receipt-style order detail with the full breakdown
- [ ] C-075 · Paged order history with detail view
- [ ] C-076 · Reorder that rebuilds the cart and skips unavailable items with a notice
- [ ] C-077 · No customer self-cancel anywhere — rules-enforced, replaced by Contact vendor to cancel
- [ ] C-078 · In-app notification inbox with read state, images and order deep links
- [ ] C-079 · Profile: edit name, phone, photo (Cloudinary), re-pin address, change password
- [ ] C-080 · Logout and full account deletion (auth, user document, tokens) behind a confirmation sheet

## V · Vendor portal (110)

- [x] V-001 · Kitchen login using the account provisioned from repository secrets at bootstrap
- [x] V-002 · Stay logged in for a year: persisted session, token refresh, session keeper watchdog and role re-check
- [x] V-003 · `vendor=true` custom claim gate on every vendor surface
- [x] V-004 · First-login device diagnostics wizard (notifications, battery exemption, autostart hints)
- [x] V-005 · FCM token registration with a health chip
- [x] V-006 · Heartbeat status proven on the dashboard
- [x] V-007 · Credential change through the password reset email flow
- [ ] V-008 · Wrong-role guard
- [x] V-009 · Rationale copy explaining why the kitchen phone needs these permissions
- [ ] V-010 · Logout hidden in settings behind a confirmation
- [ ] V-011 · Today's order count
- [ ] V-012 · Today's revenue in exact paisa
- [ ] V-013 · Pending and active queue size
- [ ] V-014 · Week bar chart drawn with a custom Canvas from real data
- [ ] V-015 · Live new-order queue cards with an age-seconds ticker
- [ ] V-016 · Store status hero showing Open, Closed or the auto-closed reason
- [ ] V-017 · One-tap open and close with a customer-facing note
- [ ] V-018 · Mini list of running prep timers
- [ ] V-019 · Calm low-attention state when there are no orders
- [ ] V-020 · Shortcuts grid to menu, orders, schedule and chat
- [ ] V-021 · Stream of today's status events
- [ ] V-022 · Pull to refresh over realtime everywhere
- [ ] V-023 · Realtime new-order stream under one second
- [x] V-024 · Full-screen ringing alert with the looping Undal order sound
- [ ] V-025 · Order card with items, totals, fee, distance and address title, description, note
- [ ] V-026 · Customer card with name, profile photo, phone number, Call button and copy-address
- [ ] V-027 · Google embed map and pin on every order, with an osmdroid toggle
- [x] V-028 · Accept with a prep-time picker (10/15/20/25/30/45 minute chips plus custom)
- [x] V-029 · Reject with reason templates and a mandatory note that pushes instantly
- [x] V-030 · Confirmation sheet before accepting, showing the total
- [x] V-031 · "Is the order ready?" alert that sets ready, notifies the customer and stops the sound tick
- [x] V-032 · Mark in delivery with a customer push
- [ ] V-033 · Mark delivered with a customer push, celebration and the collected-cash checkbox
- [x] V-034 · Sticky countdown notification with a ten-second tick
- [ ] V-035 · Overdue escalation with a red banner and an extra push
- [ ] V-036 · Per-order chat shortcut
- [ ] V-037 · Unread customer message badge on the order
- [ ] V-038 · Rendered timeline showing who did what and when
- [ ] V-039 · Long-press to copy the customer phone number
- [ ] V-040 · Order detail share-sheet text
- [ ] V-041 · Filter tabs: New, Preparing, Ready, Out, Done, Cancelled
- [ ] V-042 · Live age badge with a colour ramp
- [ ] V-043 · Actions disabled by the state machine so impossible taps cannot happen
- [ ] V-044 · Sound test button in settings
- [ ] V-045 · Multi-device consistency: another device accepting stops this alert through RTDB
- [ ] V-046 · Offline queue of status writes backed by Firestore persistence
- [ ] V-047 · Vendor-cancelled orders visible in history with their note
- [ ] V-048 · Daily closing digest card
- [ ] V-049 · Category create, read, update, delete
- [ ] V-050 · Subcategory create, read, update, delete
- [ ] V-051 · Drag reorder writing the order field
- [ ] V-052 · Item create with name, description, price, compare price, photo, veg flag, spice, tags, featured
- [ ] V-053 · Item photo pick, compress, Cloudinary upload with progress
- [ ] V-054 · Item edit with dirty-state awareness
- [ ] V-055 · Item delete with confirmation and a summary of orders affected
- [ ] V-056 · Instant availability toggle reflected in the customer app
- [ ] V-057 · Bulk availability mode
- [ ] V-058 · Search inside the manage list
- [ ] V-059 · Combo builder: pick at least two items, quantities, combo price and automatic savings against regular price
- [ ] V-060 · Combo create, read, update, delete with photo
- [ ] V-061 · Campaign create with percent or fixed discount, date range, banner text and scope
- [ ] V-062 · Campaign list, edit and disable
- [ ] V-063 · Coupon create with upper-normalised code, type, value, minimum order, expiry and usage limit
- [ ] V-064 · Coupon list showing used against limit with an active toggle
- [ ] V-065 · Coupon delete guard
- [ ] V-066 · Featured item toggle for the home highlight
- [ ] V-067 · Optional category image through Cloudinary
- [ ] V-068 · Preview-as-customer mode
- [ ] V-069 · All lists realtime with no manual refresh
- [ ] V-070 · Validation and taka formatting everywhere
- [ ] V-071 · Weekly schedule builder with per-day multi-slot editing
- [ ] V-072 · Schedule templates: 24/7, standard 09–22, lunch and dinner split, weekend special in one tap
- [ ] V-073 · Special dates with a date picker for closed days or custom hours
- [ ] V-074 · Explainer line showing today's derived status
- [ ] V-075 · Manual open/close override with a note customers can see
- [ ] V-076 · Auto-offline: fifteen minutes without a heartbeat closes the store and pushes a Reopen now notice
- [ ] V-077 · Reopen and reconnect flow
- [ ] V-078 · Presence and heartbeat status tile
- [ ] V-079 · Delivery per-kilometre fee editor
- [ ] V-080 · Delivery radius slider from one to fifteen kilometres with a live circle preview
- [ ] V-081 · Store location pin picker with a grab-my-GPS button
- [ ] V-082 · Minimum order amount editor
- [ ] V-083 · Store profile with name, banner and logo upload
- [ ] V-084 · Every edit reaching customers live through Firestore snapshots
- [ ] V-085 · Save guards and confirmation snackbars
- [ ] V-086 · Sound test and tick-sound test in settings
- [x] V-087 · Full device diagnostics screen
- [ ] V-088 · Timezone hard-locked to Asia/Dhaka in every display
- [ ] V-089 · Schedule customer-view parity check
- [ ] V-090 · Special-date conflict warnings
- [ ] V-091 · Warning that a radius change affects delivery logic
- [ ] V-092 · Fee preview calculator for a typed distance
- [ ] V-093 · Undo for the last schedule change from a local snapshot
- [ ] V-094 · Export today's orders as CSV through the share sheet
- [ ] V-095 · App version and build information
- [ ] V-096 · Bundled offline-readable privacy and policy pages
- [ ] V-097 · Chat list per order with unread counts
- [ ] V-098 · Realtime thread on Realtime Database
- [ ] V-099 · Quick replies such as On the way and Need ten more minutes
- [ ] V-100 · Typing indicator from RTDB flags
- [ ] V-101 · Read receipts
- [ ] V-102 · Push when a customer writes
- [ ] V-103 · Text-only chat in v1 to keep the free tier lean
- [ ] V-104 · Jump to the order from a chat
- [ ] V-105 · Smart scroll to the first unread message
- [ ] V-106 · Crash-safe session restore across rotation and process death
- [ ] V-107 · Keyboard-aware forms everywhere
- [ ] V-108 · Haptic feedback on status actions
- [ ] V-109 · Vendor inbox mirroring the kitchen alert history
- [ ] V-110 · One-tap share of the store link

## P · Platform, web, deep links and delivery (45)

- [x] P-001 · Single-deploy Cloudflare Worker architecture (router, routes, crons in one bundle)
- [x] P-002 · FCM HTTP v1 with service-account OAuth2 (RS256 JWT minted in Web Crypto, token cached in KV)
- [x] P-003 · Firebase ID-token RS256 verification against Google certificates plus the role claim check
- [x] P-004 · Daily 18:00 Asia/Dhaka offer broadcast to the promos topic
- [x] P-005 · Five-minute presence watchdog that auto-closes an unreachable kitchen
- [ ] P-006 · KV: OAuth token cache, menu snapshot cache and latest.json release manifest
- [x] P-007 · Edge rate limiting per caller per minute with a 429 JSON response
- [x] P-008 · Security headers (CSP, frame options, content-type options) on every web route
- [x] P-009 · Deep-link routes for dish, combo, order and store
- [x] P-010 · Smart open through an intent URL with a website fallback
- [x] P-011 · App Links verification document served at /.well-known/assetlinks.json
- [x] P-012 · Custom `undal://` scheme fallback declared in the manifest and used by the intent page
- [ ] P-013 · Full web store with browsing, search, cart, login and cash-on-delivery checkout
- [ ] P-014 · Installable manifest with a service-worker offline shell
- [x] P-015 · Open Graph and Twitter cards per dish using the real Cloudinary photo
- [ ] P-016 · Download page with QR code and an unknown-sources guide
- [ ] P-017 · Web page stating honestly which features are app-only
- [x] P-018 · Order sound asset copied into res/raw by the workflows
- [x] P-019 · Boot receiver rescheduling the heartbeat and the notification retry workers
- [x] P-020 · WorkManager retry queue for failed notification relays
- [x] P-021 · Firestore offline persistence tuned to a 100 MB local cache
- [x] P-022 · Realtime Database keepSynced for chat and live order flags
- [ ] P-023 · Crashlytics wired and monitored for a crash-free rate
- [ ] P-024 · Analytics events for order placed, accept latency and offer opens
- [ ] P-025 · Performance traces for cold start and order submission
- [x] P-026 · Release signing from repository secrets
- [x] P-027 · versionCode from the workflow run number and semantic versionName
- [x] P-028 · Fixed release asset name `undal.apk`
- [x] P-029 · Bootstrap workflow provisioning the wrapper, sound, Firebase config and kitchen account
- [x] P-030 · Seed-config workflow publishing store, promos and counter documents
- [x] P-031 · Vendor provisioning through the Identity Toolkit REST API with the vendor claim
- [x] P-032 · Firestore and Realtime Database rules deployed from Actions
- [x] P-033 · Composite indexes deployed from Actions
- [x] P-034 · Secret preflight that fails fast with a copy-paste repair checklist
- [ ] P-035 · Phone-only README runbook with the acceptance table
- [ ] P-036 · Acceptance-test checklist executed and reported
- [ ] P-037 · Money utility with unit tests
- [ ] P-038 · Haversine distance utility with unit tests
- [ ] P-039 · Order state machine with unit tests
- [ ] P-040 · Paisa formatting such as `৳ 1,250`
- [ ] P-041 · Dhaka time utilities for cron and schedule alignment
- [ ] P-042 · Consistent motion system (spring specs and shared-element transitions)
- [ ] P-043 · Automatic dark and light theme with dynamic colour on Android 12+
- [ ] P-044 · Accessibility pass: descriptions, 48 dp targets and TalkBack order
- [x] P-045 · No-placeholder grep gate that fails CI on unfinished markers

---

## How to read this file between responses

The task owner can say **`Status`** at any time and the count above is the
answer: how many of the 235 features are actually shipped and wired.
