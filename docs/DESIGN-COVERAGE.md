# Design upgrade source coverage and release handoff

26 October 2026. The first whole-product **source migration pass** is complete. This is not a claim that every release/device acceptance criterion has passed.

## Android inventory

| Existing surface | Source treatment |
|---|---|
| Splash | Static brand, safe insets, bounded scroll, polite progress announcement |
| Login / registration | Bounded forms, warm hierarchy, Google/email pending states |
| Delivery setup | Adaptive map/details panels, explicit confirmed pin, persistent save |
| Store / item | Editorial entry, adaptive catalogue/cards, image/details split, sticky guarded add |
| Basket / checkout | Adaptive delivery/receipt panels, total/blockers, wrapping quantity/remove actions |
| Tracking / history | Status/timeline/receipt hierarchy, chat/call controls, active-before-paging lazy history |
| Profile | Readable identity/security groups, responsive actions, initial-read recovery |
| Customer notifications / help | Keyed loading/empty/error/message states; bounded help and actual support action |
| Policy / routing recovery | Offline reading hierarchy, shared buttons and factual recovery |
| Vendor dashboard / queue / order detail | Waiting work first, adaptive queue, wrapping metadata, confirmed next-action flow |
| Vendor menu and all five editors | Wrapping navigation/choices, bounded scrollable editor shell, persistent save/close; adaptive price/time fields |
| Vendor hours | Responsive time fields, validation/undo retained, sticky save |
| Vendor settings / diagnostics | Bounded sections, initial-read protection, destructive cleanup separated, static readiness and actual tests |
| Vendor chat list / chat / history | Keyed feedback/loading rows, readable threads, adaptive composer, saved draft/pending recovery |
| Safe-mode recovery | Existing dependency-light, selectable native-view report and scrollable recovery retained intentionally; not replaced with Compose in a crash handler |
| Order alert / full-screen action | Uses Android notification/full-screen intents into the existing order confirmation sheets; no separate Compose overlay route exists. OS delivery, data-only payload and continuous alarm semantics retained |

All surfaces inherit the shared warm-paper/evergreen theme, spacing, bounded scaffold, typography and accessible button foundations as appropriate. The debug design gallery is a specimen, not a new production destination.

## Website inventory

Menu, item, basket, checkout, login/account, tracking, about/help and download use the shared warm theme and reflow controls. Dedicated item/basket/checkout/tracking panels, labelled navigation, offer cards, account keyboard tabs, no-script recovery and transient tracking-error feedback are implemented. Existing account/address/security panels and real server form/action IDs are retained.

## Verification and merge gates

- Local Worker regression suite: **205 passed**; TypeScript and shared rule/browser/alert wiring gates pass.
- Local browser: **59 reflow cases passed**, plus keyboard tabs, tracking refresh failure, no-script account fallback and real no-script basket add/increment/remove + checkout form.
- Native static gates: 159 Kotlin files and 26 theme contrast pairs pass. Eight web token contrast pairs pass in the Worker tests.
- PR CI must run Android release unit tests, compile debug previews/instrumentation sources and assemble the release APK. These are separate from the unavailable local Java/SDK environment.
- Actual Android instrumentation execution, TalkBack, IME/landscape review, OEM/locked-phone alert audibility, Google OAuth signing configuration and live Firebase/map/production browser tests remain **release QA**, not certified by static checks or screenshots.

The merge requested by the owner is a source integration. Existing main-branch workflows may publish an APK and deploy the Worker after merge; their results must be reported separately. No production reliability guarantee follows from a successful build or merge.
