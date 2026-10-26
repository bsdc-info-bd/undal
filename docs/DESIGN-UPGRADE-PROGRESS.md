# Design upgrade — implementation progress

26 October 2026 · six source implementation batches. First whole-product source migration pass complete; release QA remains.

This records actual source changes, not a claim that every screen has been redesigned or tested on a device.

## First implementation batch

### Foundations

- Shared scaffold centres content in a bounded readable column by default (840dp), with wider opt-ins for store (1200dp), item/checkout and vendor queue (1100dp).
- Bottom actions follow the same width boundary. System/IME inset ownership stays in the scaffold; screen bodies must not consume its padding twice.
- New `UndalAdaptivePair` stacks below 840dp and at font scales above 1.3; otherwise it places two panels alongside one another.
- New reading-column and editorial intro primitives, plus pure layout-policy functions.
- Warm neutral canvas, refined shape/spacing tokens, flatter cards and an editorial heading role without remote font downloads.
- More legible dark-amber text/action colour; corrected outline vs decorative-border roles. Shared primary/secondary button labels allow two lines.
- New contrast gate reads actual Kotlin theme values and checks 26 text/control pairings. It runs in Android build CI.

### Store

- Intro and delivery/open-state panel adapt from stacked to paired layout.
- Readable menu section hierarchy, search icon and consistent grid spacing.
- Responsive menu-card minimum width grows with text scale.
- Overflow menu retains orders, notifications and profile without crowding narrow app bars.
- Card prices and quantity controls no longer compete in one rigid row; wrapping badges/attributes and fallback image added.
- Quantity controls use 48dp icon-button targets and a flexible count width.
- Saved basket can be opened while the kitchen is closed. Checkout still controls whether an order can be placed.

### Item detail

- Image/details split on wide windows; stack on phones/large text.
- Sticky add-to-basket action with current line amount, existing-basket context and a guarded pending state.
- Quantity and note are captured together before the asynchronous add, preventing a second tap or in-flight edit from changing that request.
- Loading skeleton, useful missing/error state, image fallback and factual availability wording.
- Original price is struck through independently of the readable saving amount.
- Quantity/line-total layout wraps; removed redundant nested click handling on the basket action.

### Checkout

- Basket/note/coupon and delivery/receipt panels stack or split adaptively.
- Delivery details precede the receipt in the secondary panel; server-backed totals and coupon rules are unchanged.
- Bounded sticky action with clear cash-on-delivery total and adjacent blocker explanation.
- Cart-line controls wrap and expose a visible Remove action in addition to swipe removal.

### Vendor queue specimen

- Clear waiting-order heading, stage filters and adaptive one/two-column order grid.
- Order ID, age and status wrap; address and price no longer fight for horizontal space.
- Useful loading, stale-cache and error/retry presentations.
- Removed an unsupported claim that an empty queue means the alarm is armed.
- Fixed retry handling: inner listener failures now leave the refresh stream alive instead of making the Retry button ineffective. Successful reads clear the error.

## Second implementation batch

### Shared responsive actions

- `UndalActionGroup` uses the actual container width, not an assumed device size. Paired controls stack below 360dp of available width or above 1.3 font scale. A single control keeps the full width.
- Applied to tracking contact actions, profile activity/session actions, dashboard statistics and dashboard navigation pairs.

### Order history and tracking

- History is now a keyed lazy list with separate active/past headings, skeleton rows, readable wrapping metadata and an explicit tracking action.
- Active orders are prioritized **before** the twenty-order display window, so an older active order in the fetched fifty-order window is not buried behind recent completed orders. Ordering within each section is preserved.
- Reordering disables competing reorder actions, retains its pending label, and clears pending state in `finally`, including early exits and exceptions.
- Tracking pairs status/timeline with receipt/delivery details on expanded windows; phones and large text stack these panels.
- Chat/call controls stay at the bottom while the receipt scrolls; they disappear in chat mode so the existing composer retains its space. Missing phone details do not leave an empty half-width action slot.
- Replaced an unsupported ringing/acceptance-time assertion with factual waiting copy. Lifecycle-bound countdown behavior and real order state remain unchanged.
- Distinguished an actual null order result from loading. Flow-backed notices no longer vanish on the next ticker emission; read failures can show an explanation instead of sitting behind the loader.

### Authentication and profile

- Login and registration forms are bounded to 600dp, with clearer entry headings. Google and email actions have distinct pending indicators while retaining the shared duplicate-request guard and existing role routing.
- Profile is bounded to 760dp. Photo editing moved out of the crowded toolbar; address editing gets its own full-width action.
- Activity and session controls wrap/stack through the shared policy. Account deletion still requires its existing confirmation.
- Profile now distinguishes loading from failed initial reads and offers an explicit retry rather than presenting empty editable details as if they had loaded.

### Vendor dashboard and order detail

- Waiting orders follow the store controls and precede statistics, weekly analytics and connection history. Cache/permission warnings and pause/reopen controls remain intact.
- Statistic and shortcut pairs stack at narrow widths or large text. Removed unsupported empty-queue assertions about armed alarms and one-second delivery.
- Vendor order detail has compact, labelled chat/share icons and a persistent next-step action outside chat mode.
- Accept still opens prep-time selection; ready and delivered still open their existing confirmation sheets; handing to the rider uses the existing state transition. Cancellation remains a separate in-body action with its reason sheet.
- Previously invisible `infoMessage` feedback is now displayed, including failures when no order has loaded. Status/age badges wrap.
- No FCM payload, alarm, pricing, location-confirmation or server-validation logic was changed by this batch. This is not a phone-delivery/audibility certification.

## Third implementation batch

### Delivery setup

- Map and rider-details panels now stack/split through the adaptive layout, with a persistent save action and a quieter GPS helper action.
- Added explicit pin confirmation to both UI and ViewModel validation, including the keyboard-submit path. Moving the pin, starting a GPS lookup or restoring coordinates clears confirmation. A pending lookup/save cannot enable submission.
- The location-permission settings fallback now sits inside the scrollable page rather than outside/over the scaffold. Manual map use and the sign-out confirmation remain available.
- Existing address/name/phone validation, profile saving and draft persistence remain the authoritative save path. Pin confirmation does not replace those checks.

### Notifications and help

- Customer notifications use one keyed lazy list for introduction, mark-all-read, loading skeletons, recovery and messages. The bulk action no longer competes with the title in the toolbar.
- Read/unread is written in text as well as indicated visually. Notification text is not truncated into an unreadable message with no detail view; missing timestamps no longer pretend to be “just now.” Order navigation/read-marking is unchanged.
- Failed initial reads end loading and distinguish failure from an empty inbox.
- Help has a bounded reading width, an editorial introduction and a direct call action when a support number exists. Missing configuration shows a customer-facing explanation, not a repository-secret instruction.

### Menu management and schedule

- Menu tabs and action groups wrap; ten choice groups across the dish/combo/campaign/coupon editors now wrap instead of overflowing horizontally. This is a management source pass, not a claim that every editor field is fully migrated.
- All five menu editors share a bounded dialog with a scrollable form and persistent full-width save/close controls. Existing drafts, validation, upload state, dirty-warning and delete-confirmation callbacks remain in place.
- Dish price/previous-price fields use the shared responsive pair policy.
- Weekday and special-date opening/closing fields stack for narrow containers/large text; Remove sits separately. Special-date add/cancel controls also adapt.
- Schedule save stays at the bottom, through the original save callback, with undo, validation warnings and timezone/overnight rules retained.
- Replaced another unsupported one-second synchronization claim with factual connected-save wording. No alert-delivery implementation changed.

## Fourth implementation batch

### Operational settings and readiness

- Store settings use a bounded reading width and editorial entry. Initial loading/read failure blocks editing default values as if they had loaded from the store.
- Delivery price/minimum-basket fields and kitchen GPS/save controls use the shared responsive pair policy.
- Feedback now lives inside the settings list, with a dismiss action, rather than being drawn over the form. Success is not implied by a check icon on every message.
- Permanent history cleanup moved below everyday settings and uses destructive styling. Its existing detailed warning, dry-run check, role gate and explicit confirmation remain intact.
- Diagnostics uses a static, wrapping readiness summary instead of an infinite pulse/fixed-size badge. Required/optional checks, repair callbacks, local delivery journal, heartbeat and real relay test remain intact.
- Console navigation stays at the bottom. Readiness and test copy distinguish device configuration, a successful test attempt and future delivery; force-stop/offline/OEM limitations remain explicit.

### Kitchen communication

- Chat list and kitchen history now use one keyed lazy list each for headings, saved-copy notices, errors, loading rows, empty states and actual records. Feedback no longer overlays those pages.
- Thread IDs/status/read-count actions wrap, while customer names have their own readable line. Existing unread-first ordering and order/chat navigation remain unchanged.
- Kitchen history describes saved notifications, not proven alarm audibility. Bulk read has pending/duplicate guards and explicit failure feedback; missing timestamps do not print the Unix epoch.
- Added `UndalMessageComposer`: stacks below 480dp of actual available width and above 1.3 font scale; otherwise reserves flexible space for the field beside Send. Multiline input, blank-draft disabling and a pending state are explicit.
- Chat errors sit in the page rather than over its composer. A draft cannot be replaced by quick replies while its snapshot is sending; a thrown send always releases pending state. Existing saved drafts, unread-position logic, typing/read marks and notification retry enqueue remain intact.
- Shared kitchen chips now have at least 48×48dp targets. Message bubble metadata uses the matching container foreground colour.
- No FCM payload/service/continuous-alarm implementation was changed. Native audibility/delivery proof is still outstanding.

## Fifth implementation batch

### Website golden-path parity

- Added dedicated responsive item/image-information, basket/review-guidance and checkout/details-receipt panels. Checkout fields, hidden coordinates, result host and submit button remain inside the original POST form; client element IDs and action routes are preserved.
- Expanded panels use a 60rem breakpoint; menu card minima and text/control sizing use rem units. Narrow search, category/sort controls, receipt amounts and account/history actions reflow rather than creating whole-page horizontal overflow.
- Styled the existing real coupon strip as warm offer cards; no invented offers or testimonials were added. Store status/facts are a distinct full-width surface.
- Added labelled category/sort navigation, item back navigation, readable image fallbacks, proper control borders/focus colour and reduced-motion transform overrides. Login form width is bounded without removing any no-JavaScript panels.
- Checkout initially renders the known food subtotal and explains that delivery still needs checking, rather than presenting a zero grand total before the script runs. The authoritative server validation/order path is unchanged.
- Removed a kitchen-ringing assertion from checkout copy and labelled dish preparation time as a guide confirmed on acceptance.
- Seven additional Worker tests cover adaptive markup, sold-out recovery, the single real checkout POST form/input IDs, navigation labels, CSS rules and eight actual web-token contrast pairs. The expired-coupon test now checks banner markup rather than accidentally matching newly added CSS class names.

### Native entry and policy polish

- Splash uses a static logo, a bounded scrollable layout with safe drawing insets, and a polite loading-message announcement. Removed its perpetual logo scaling.
- Account-routing failure uses the shared accessible buttons, a bounded reading width and factual retry guidance.
- Offline policy content uses an editorial introduction, bounded reading width and a call action only when support is configured. Removed an unsupported sub-second synchronization assertion.

### Browser verification actually executed

- **49 Chromium reflow cases passed**: eight routes at 320/375/768/1024/1440px, plus menu/item/checkout at 200% root text size at 320/768/1440px. The assertion checks whole-page horizontal overflow; it is not a full accessibility audit or proof of every control's visibility.
- With JavaScript disabled, the real sample-server forms successfully added an item, incremented quantity and removed it; checkout retained one real POST submit action. No real order was placed.
- Third-party traffic was blocked for deterministic QA. Live Firebase sign-in, map tiles/geolocation and production order delivery were not tested by this run.
- Captured and reviewed desktop menu/mobile item screenshots in `docs/design-previews/`. Sample dishes have no real product imagery, so image fallbacks are shown.
- Live sample preview runs on port 8790, labelled **Redesigned storefront — sample data**. This is not a deployment.

Optional reproduction (Chromium requires compatible system libraries and font configuration):

```sh
npm install --prefix "$HOME/.cache/undal-browser" --no-save --no-package-lock playwright-core @sparticuz/chromium
# Run the sample server separately: cd worker && npx tsx ../tools/preview-web.mts 8790
UNDAL_BROWSER_MODULES="$HOME/.cache/undal-browser" node tools/check-design-browser.cjs
```

`UNDAL_BROWSER_FONT` can point at a locally installed Bengali TTF for the taka glyph; `UNDAL_DESIGN_SCREENSHOTS` optionally selects the output directory. In this sandbox the packaged AL2023 NSS libraries were extracted to `/tmp/undal-browser-libs/lib` and supplied through `LD_LIBRARY_PATH`; Chromium/font dependencies remain outside Git.

## Final source pass and integration

- Web account/profile/security panels now use bounded shared surfaces, wrapping action/tab groups, explicit no-script recovery and arrow/Home/End keyboard navigation with roving tab focus. Existing sign-in forms and security confirmations remain intact.
- Tracking receipt/progress panels stack or split; status announcements, no-script explanation and retrying refresh-failure feedback are visible without inventing ETA, kitchen attention or alarm-delivery assertions.
- The last coupon opening/closing field pair now stacks at narrow widths/large text.
- Audited remaining surfaces: safe-mode intentionally keeps dependency-light native views; order-alert full-screen actions use Android notification intents into existing order sheets, not a separate Compose overlay.
- Added a secret-free Worker PR workflow and Android debug-preview/instrumentation-source compilation alongside the existing release unit-test/build gates.
- Full inventory and remaining release gates: `docs/DESIGN-COVERAGE.md`.
- CI found and fixed implicit Compose delegate/unit imports and duplicated dashboard stat arguments. The import gate now has five regression tests covering implicit getValue/setValue/dp usage. **137 Android release unit tests passed**; debug source compile deliberately excludes production-only Firebase debug processing, not release validation.
- Updated evidence: **205 Worker tests; 59 browser reflow cases**, keyboard tab navigation, tracking refresh recovery and no-script account/basket/checkout paths all passed locally.

## Test and review assets

- Eleven `UndalLayoutPolicyTest` unit tests cover breakpoints, large text, invalid dimensions, minimum card widths, action grouping and composer breakpoints.
- Four `UndalMessageComposerTest` instrumented tests cover compact/wide/large-text placement and blank-draft disabling.
- Six `UndalAdaptiveTest` instrumented tests cover panel placement, narrow/large-text action groups and full-width single actions.
- Four `LocationSetupStateTest` unit tests cover explicit confirmation and lookup/save eligibility.
- Four `OrderHistoryStateTest` unit tests cover active-before-paging behavior, stable section order, page expansion and empty history.
- Four Android Studio preview specimens in `app/src/debug/.../UndalDesignGallery.kt`: compact, expanded, large text and dark. They now include account-action, kitchen-next-action and message-composer specimens. Sample content is explicitly labelled as a design specimen, not live product data.
- `tools/check-design-contrast.py` checks the shipping light/dark theme. It does not evaluate photography, arbitrary component overrides, opacity/gradients, focus order or screen-reader operation.

## Checks actually run

- 205 Worker tests: pass (including nine new redesign tests).
- Worker TypeScript no-emit check: pass.
- 26 native theme contrast checks: pass; eight shipping web-token pairs also pass in the Worker suite.
- Kotlin shape/import and foreground-service contract gates: pass across 159 Kotlin files.
- Alert ladder, browser compatibility, shared pricing/distance rules and coupon parity: pass.
- Git whitespace/diff check: pass.

## Not run / not certified

Java and the Android SDK are not installed here. Native compilation, new native tests, Android Studio preview rendering, native screenshots, TalkBack and actual device responsiveness have **not** been executed. Website Chromium screenshots/reflow checks are separate evidence, not native validation. Source/static gates are not compilation or visual proof.

In particular, verify 320/360/412dp phones, 840/1024dp windows, 2.0 font scale, landscape/keyboard insets, two simultaneous kitchen orders and every changed callback on a signed device build before release.

No FCM payload/alert-service changes or pricing/business-rule changes were made as part of this design batch. No deployment was performed.

## Phase status and next batch

| Plan phase | Current state |
|---|---|
| Baseline | Source inventory complete; visual/device baseline captures outstanding |
| Foundations | Source implementation started; native review pending |
| Customer golden path | Store, item, checkout, tracking and history migrated in source; device QA pending |
| Entry/account | Login, registration and profile migrated in source; delivery setup, notifications and help also migrated; remaining entry/legal states still need review |
| Kitchen golden path | Queue, dashboard and detail migrated in source; readiness and chat/history sources also migrated; alert-intent audit complete; real-device review remains |
| Management | Menu navigation, shared editor shell, selected field pairs and schedule migrated in source; store settings/diagnostics also migrated; source pass complete; device/editor-interaction QA outstanding |
| Website parity | Website source parity completed with browser evidence; live integration/accessibility/browser-device release QA remains |
| Release quality | Static/regression checks pass; native build/device/accessibility gates outstanding |

Next: PR CI/source integration, followed by native instrumentation, screenshots, accessibility and real-device release QA. Source coverage is recorded in `docs/DESIGN-COVERAGE.md`; do not treat it as certification of those release gates. Do not mark all screens complete based only on inherited token changes.
