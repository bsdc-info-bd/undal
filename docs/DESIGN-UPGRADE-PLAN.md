# Undal — whole-product UI/UX upgrade plan

Status: **implementation started — first batch in source, native verification pending**.
See [implementation progress](DESIGN-UPGRADE-PROGRESS.md) for shipped source changes, checks and remaining scope.
Date: 26 October 2026.
Direction: **Warm Kitchen, Precision Console**.

## 1. The intended transformation

Make ordering feel welcoming and effortless; make running the kitchen feel controlled and dependable.

This is not a colour swap. The work covers layout, navigation, component states, content hierarchy, forms, interaction feedback, accessibility and responsive behaviour across Android and the existing website.

### Customer character

Warm ivory canvas, deep evergreen actions, restrained turmeric accents, generous whitespace and food-forward imagery. A warm editorial display treatment for a small number of hero headings; highly legible platform text everywhere else. Bengali fallback must remain intact. Never require a remote font to use the app.

### Vendor character

The same evergreen brand with quieter surfaces, stronger numeric hierarchy, compact-but-touchable rows, clear age/status labels and action-first order detail. Operational data, not decorative dashboards. Use red for errors/destructive actions and amber for attention; never make a routine button look like an alarm.

### Three signature experiences

1. **The local kitchen storefront:** delivery context, honest opening status, a curated-looking menu and an always-understandable basket summary.
2. **The doorstep checkout:** a clear delivery point, visible confirmation state, readable receipt and an explicit cash-on-delivery action. No ambiguous map pin or hidden fee.
3. **The kitchen command centre:** waiting orders first, clear service health, glanceable queue and an action panel that stays reachable while staff read an order.

## 2. Starting point and evidence

Source inventory found 23 `*Screen.kt` files. Other surfaces live outside those filenames: support, customer chat, vendor promotions, five menu editor sheets and the order-alert overlay. Android uses Compose and already has shared theme, surface, button, field, scaffold, status and accessibility primitives. The website has shared Worker-rendered layout/design primitives and ten exported page handlers across the page modules inspected.

Existing strengths to retain:

- Shared palette, type, spacing, shapes and motion tokens.
- Adaptive menu grid, checkout blockers and an existing sticky checkout action.
- Data-only FCM order alerts, looping service, persisted queue, watchdogs and delivery diagnostics.
- Mandatory delivery location, server-owned pricing and role routing.
- Server-rendered browser fallback paths and older-WebView compatibility checks.

Review priorities, not yet verified visual defects:

- Shared scaffold owns content padding while exposing padding to callers: audit all callers to prevent duplicate insets during migration.
- App-bar actions and status/filter rows need testing at narrow widths and large font scales.
- Long forms need consistent grouping, keyboard behaviour and validation hierarchy.
- Loading, empty, stale-cache, error and success presentations must feel like one product.
- Customer and vendor information density should differ intentionally.

This is a source-based inventory, not a completed screenshot or usability audit. Baseline captures and device tests are part of implementation.

## 3. Design-system v2

| Layer | Planned change | Acceptance rule |
|---|---|---|
| Colour | Semantic action, surface, border, attention and status tokens; light/dark pairings | Text contrast checked per actual pairing; status also has words/icons |
| Typography | Defined display, page, section, body, label and numeric roles | No clipped prices/counts; platform Bengali fallback; large-text support |
| Spacing | Explicit component vs page spacing and responsive gutters | No ad hoc screen-by-screen padding inflation |
| Surfaces | Separate cards, grouped forms, inline rows and critical notices | Not every paragraph becomes a raised card |
| Buttons | Primary, secondary, quiet and destructive families; pending/success states | One dominant action per task; minimum 48dp Android targets |
| Inputs | Labels, helper/error slots, optional/required language and validation timing | Error is adjacent to the field and announced accessibly |
| Navigation | Consistent back behaviour, page actions and role-specific navigation | Cart, draft edits and scroll position survive ordinary navigation |
| States | Shared loading, empty, offline/stale, partial failure, retry and success patterns | Every migrated screen has a state checklist |
| Motion | Short transitions, restrained item feedback and reduced-motion rules | No animation required to understand state; no endless decorative pulses |
| Maps | Consistent picker frame, coordinate/readout, accuracy explanation and confirmation | Moving a point invalidates its previous confirmation |

Use existing `core/theme` and `core/design` modules as the implementation foundation. Refactor components with compatibility defaults, then migrate callers in small batches. Do not replace working navigation or backend systems merely to change appearance.

## 4. Responsive architecture

Use available window/container width, not device model or orientation alone.

- **Compact, below 600dp:** single-column task flows; wrapping actions; reachable bottom action; no dense desktop tables.
- **Medium, 600–839dp:** wider adaptive grids; bounded forms; selective two-column summary/detail arrangements where content fits.
- **Expanded, 840dp and above:** navigation rail where justified; vendor queue/detail split; customer checkout content plus receipt panel.
- These are starting breakpoints. Large text or constrained height can force simpler layouts even on a wide window.
- Keep prose/forms at a comfortable maximum width rather than stretching fields across tablets.
- Audit system bars, display cutouts, landscape, split-screen, keyboard and bottom sheets. Assign inset ownership once per surface.
- Website uses matching hierarchy with CSS-native breakpoints and real keyboard/mouse interactions, not stretched Android screenshots.

## 5. Full screen/surface coverage

### Entry, account and shared utility

| Surface | Planned upgrade |
|---|---|
| Splash | Clean brand transition; no artificial delay; accessible launch handling |
| Login | Clear Google/email choice, balanced hierarchy, visible busy/error state and keyboard-safe layout |
| Registration | Simplified section hierarchy, progressive password guidance and Google onboarding explanation |
| Location setup | Doorstep task hierarchy: name/phone, map selection, address details, confirmation and save; no inaccessible gestures |
| Profile | Clear identity/address/security groups; editable information separated from destructive actions |
| Support | Scannable delivery/payment/help sections and direct existing contact actions |
| Policies | Readable line lengths, headings and predictable back navigation |
| Router/recovery error | Explain what failed, retain safe context, offer retry/sign-out without a dead end |
| Shared dialogs/sheets | Consistent titles, focus, close behaviour, IME handling and destructive confirmation |

### Customer experience

| Surface | Planned upgrade |
|---|---|
| Store | Delivery/open-state context, restrained hero, clearer category/search/sort controls, responsive food cards and basket bar |
| Item detail | Strong image/title/price hierarchy, clear availability, readable description, quantity and add-to-basket summary |
| Cart + checkout | Group basket, delivery and receipt; make coupon effects transparent; keep blocker explanation beside place-order action |
| Order tracking | Stage-led timeline, factual status updates, delivery details and contextual contact; do not invent ETA/location data |
| Order history | Compact status-led cards, clear dates/totals and convenient access to tracking |
| Customer chat | Legible message groups, accessible timestamps, keyboard-safe composer and truthful send/retry status |
| Customer inbox | Distinct unread/read states, useful grouping and clear order destinations |

### Kitchen experience

| Surface | Planned upgrade |
|---|---|
| Dashboard | Waiting orders and store state first; useful existing metrics second; health/readiness summary without false guarantees |
| Order queue | Clear status counts, readable order age, consistent card density and responsive queue/detail layout |
| Order detail | Customer/address/items/receipt hierarchy with reachable stage actions; separate destructive cancellation |
| Ringing overlay | Large order identity and accessible review actions; unmistakable interruption without decorative clutter |
| Diagnostics | Prioritised blockers, repair actions, delivery timeline and explicit platform limits; distinguish FCM acceptance from receipt/playback |
| Menu management | Clear sections, availability and price states; improve editing discoverability and list/grid density |
| Dish editor | Group identity, photo, price and availability; clear save state and unsaved-change handling |
| Section editor | Focused naming/order controls and predictable save/cancel behaviour |
| Combo editor | Explain item composition and existing pricing rules; prevent visually ambiguous selections |
| Campaign editor | Clear scheduling/content grouping, preview and field-level feedback |
| Coupon editor | Explain type, minimum, cap and eligibility; preview the offer using existing pricing logic |
| Promotions | Align campaign/coupon summaries, effective dates and active/inactive labels |
| Schedule | Day/time grouping, understandable overnight ranges and exception-date editing |
| Store settings | Separate customer-facing information, delivery/pricing and operational settings; existing validation remains authoritative |
| Chat list | Clear unread context and useful order association |
| Vendor chat | Match customer chat interaction quality while keeping order context easy to reach |
| Vendor inbox | Clear event categories, age, unread status and destination |

### Website

Apply the same brand and state vocabulary to storefront, item detail, basket, checkout/map, tracking lookup, order tracking, login/account, about/help and download/deep-link pages. Audit shared header/navigation/footer, notices, cookies/no-JavaScript fallback and mobile keyboard behaviour. Preserve public metadata, CSP and accessible form semantics. Browser Google OAuth remains a separate functional scope, not something a visual redesign silently introduces.

## 6. Functional UX improvements within this scope

- Consistent pending state and duplicate-submit protection for mutating actions.
- Contextual recovery: retry the failed task rather than make the user restart an entire flow.
- Clear unsaved-change warnings in editors; preserve drafts where existing storage safely supports it.
- Make search/filter selection and relevant scroll position survive back navigation.
- Clear confirmation feedback for cart changes and successful saves; use undo only where reversal is safe.
- Better disclosure: show delivery fee and coupon consequences beside the receipt; put secondary technical details behind expandable sections.
- Clear stale/offline labels with last-update information only when real timestamps exist.
- Use accessible haptic/visual feedback as an enhancement, never as the sole confirmation.

New business features such as live rider tracking, new payment methods, favourites/reordering or analytics pipelines need separate data/security design. They are not smuggled into a styling change or mocked as working features.

## 7. Delivery sequence and review gates

### Phase 0 — Baseline and coverage map

Capture current key flows and enumerate each screen's states, navigation entry/exit, forms and inset ownership. Record actual issues, not assumptions. Produce a screen/state checklist before edits.

**Gate:** entry → sign-in → location → menu → checkout → tracking and vendor queue → review → action flows are mapped.

### Phase 1 — Design foundations and adaptive shell

Implement semantic tokens, type/spacing rules, responsive containers, app-bar action behaviour, shared form/state components and representative light/dark examples.

**Gate:** component gallery and one customer/one vendor specimen work at narrow width and large text. Existing call sites compile.

### Phase 2 — Customer golden path

Redesign store → item → cart/checkout → tracking, including map interaction states and loading/error/empty variations. Use real data and pricing.

**Gate:** a complete order can be placed without a misleading total, lost basket or obscured CTA.

### Phase 3 — Entry and account

Migrate Google/email authentication, name/phone/address onboarding, profile, history, inbox, customer chat, support and policies.

**Gate:** new and returning account paths work, including Google cancellation, denied location and missing profile information.

### Phase 4 — Kitchen golden path

Migrate dashboard → queue → detail/action, ringing overlay and diagnostics. Preserve the alert subsystem and test its critical navigation surfaces.

**Gate:** two simultaneous orders remain actionable; answering one does not silence the other; visual readiness does not claim delivery guarantees.

### Phase 5 — Kitchen management

Migrate menu and all five editor sheets, promotions, schedules, store settings, vendor chat and inbox. Add unsaved/pending/recovery handling where missing.

**Gate:** staff can make and verify changes without losing edits or confusing draft and saved data.

### Phase 6 — Website parity

Bring existing web pages to the shared visual standard, including compact/wide layouts, form states and the no-JavaScript path. Share concepts and tokens, not platform-specific interaction assumptions.

**Gate:** browser checkout and account fallback routes still work; CSP/SEO/compatibility checks remain intact.

### Phase 7 — Release quality pass

Review every screen/state, polish copy/motion, run regressions and document real-device results. No phase is called complete solely because the palette changed.

**Gate:** screen/state checklist is complete and unresolved issues are explicitly listed.

## 8. Quality bar and release matrix

- Android widths: 320, 360, 412, 600, 840 and 1024dp; portrait, constrained landscape and split-screen samples.
- Website widths: 320, 375, 768, 1024 and 1440 CSS px.
- Font scales: 1.0, 1.3 and 2.0, including long names, addresses, amounts and Bengali text.
- Normal text contrast at least 4.5:1; large text at least 3:1; meaningful non-text UI contrast at least 3:1 where applicable.
- Android interactive targets at least 48dp; web touch controls target at least 44 CSS px.
- TalkBack traversal, labels, headings, live status and focus return checked on primary flows; keyboard and visible focus checked on web.
- Light/dark states checked on Android; never claim an unimplemented web theme.
- Slow network, image failure, denied permissions, partial data, stale cache, empty lists, failed saves and duplicate taps included.
- Existing Worker tests, pricing parity, browser compatibility, alert contracts and native checks remain passing.
- Add Compose/component tests and screenshots for layout and key states where a working Android toolchain is available. Add browser interaction/viewport checks where tooling is available.
- Profile real scroll/image behaviour on a representative lower-end phone. Do not invent performance improvements without measurements.

Native Java/Android tooling is unavailable in the current workspace. Source/static checks cannot certify Android builds, accessibility or device behaviour. Those checks must run in configured CI and on real hardware before release.

## 9. Non-negotiable preservation rules

- No changes to FCM data-only semantics, alert sound lifecycle, watchdogs or retry policy solely for visuals.
- Never gate an alarm behind an animation, screen opening or navigation completion.
- Preserve vendor custom-claim authority, Firebase rules, paisa-based pricing, server validation, cash-on-delivery rules and explicit pin confirmation.
- No fake delivery promises, customer testimonials, live-map movement, order counts or readiness guarantees.
- Do not delete working features to simplify screenshots. Every existing action needs a retained destination.
- Preserve accessibility, reduced motion, draft privacy and no-JavaScript web ordering.
- Review by phase; keep changes testable and reversible rather than landing a single unverified rewrite.

## 10. First implementation batch

Start with **Phase 1 plus Store, Item Detail and Cart/Checkout specimens**. This establishes the new visual language on the customer's most important journey before spreading it across every screen. Include a vendor queue specimen early to prove that the system supports operational density too.

Deliver: shared tokens/components, adaptive page container, the selected screens and their loading/empty/error states, responsive comparison captures where executable, checks run and explicit remaining device-verification requirements.
