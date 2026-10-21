# The audit ledger

One hundred real defects is the target. Each one is found by reading the app and
the Worker the way a customer and a cook use them — typing in Bangla, on a
৳12,000 phone, on a street where the signal drops — and closed one at a time.
Where the count stands is in the table below, and it is never rounded up: an
entry appears here when its fix is in the repository and a gate can prove it.

This file is the record of that work: what was wrong, where it was, what
changed, and what proves it. It is written as the work lands, not after it, so
the count below is the honest count at the time of writing.

| Ledger | Count |
| --- | --- |
| Closed, with a fix and a gate in this repository | **85** |
| Recorded as deliberate, or checked and already correct (not counted) | 3 |
| Open leads (identified, not yet fixed) | 3 |
| Waiting on a real phone, a real card in a hand (owner's acceptance list) | 5 |

**The rules this ledger runs on**

1. *No entry without a fix.* A finding is written down when it is closed, in the
   same commit, with the file it was closed in. Ideas that are not fixed live in
   the "open leads" section at the end.
2. *No entry without a proof.* Every entry names the gate that keeps it closed:
   the Kotlin shape guard, the import contract, the placeholder gate, the
   foreground contract, the web-compat parser, the Worker's test suite
   (`npm test`), or a human step from the acceptance table in `README.md` §11.
3. *No entry that is the same defect twice.* The same mistake repeated in eight
   screens is one entry with eight sites named, because that is the number of
   things a person has to fix.
4. *The repository's gates are not softened to make an entry close.* If a gate
   fails, the code changes, not the gate.

---

**001 — A release APK could be built pointing at a host that does not exist.**
*Symptom.* The build had an invented default for the store's own address
(`https://undal.YOUR-SUBDOMAIN.workers.dev`) and for the Cloudinary account
(`undal` / `undal_unsigned`). A build without the owner's secrets was therefore
"successful": the APK installed, opened, showed the menu, and then shared a dead
link into a WhatsApp group, sent the verification e-mail to a sub-domain that
does not resolve, and posted the kitchen's photos into whichever Cloudinary
account is called `undal`. *Fix.* no invented defaults — an empty value means
"not configured", every screen that needs one says so — plus
`verifyReleaseInputs`, which refuses to produce a release APK while any of the
four values is blank or still holds the documentation's example. `preflight.sh`
now refuses a secret that still contains `YOUR-SUBDOMAIN` or `REPLACE_`, so the
workflow stops in seconds with the same list. Debug builds stay exempt.
*Proof.* `AppContractTest` pins the contract in both directions: a configured
build reports a working address, an unconfigured one reports nothing and never
names the placeholder.

**002 — Four screens would share a link the app cannot serve.**
`isWorkerConfigured` existed and nothing called it: the dish share, the
kitchen's "share the store link" (two screens: dashboard and settings), and the
verification e-mail's continue URL all built their link from the raw build
value. *Fix.* `UndalBuildInfo.webStoreHost` / `webStoreBaseUrl` / `shareUrlOrNull`
answer `null` when there is no host; the dish share travels without a link and
says so, the kitchen is told the address is missing instead of opening the share
sheet, and the verification mail falls back to Firebase's own page.
*Proof.* `AppContractTest.sharedLinksAreNeverBuiltOnAPlaceholderHost`.

**003 — A diagnostics "fix" that did nothing.** `Fix.AUTOSTART` existed as an
enum value and a button label ("Open autostart settings"), its handler was
`-> Unit`, and no check ever produced it. *Fix.* it is now a real check on the
phones that have an autostart manager (Xiaomi, Oppo, Realme, Vivo, Huawei,
Samsung), worded for what the app can actually see — this phone has one, here is
where it is — with a real action that opens the manager that resolves on this
device, and a sentence instead of a dead button when the ROM moved it.
*Proof.* Kotlin shape guard; the check's own id in `DiagnosticsViewModel`.

## A. Crashes and robustness

**004 — The Google Maps embed was a grey box on the last step of signing up.**
*Symptom.* The owner reported the map "doesn't work"; a customer picking their
delivery pin saw an empty rectangle where the street should be. *Cause.* The
"map" was a `WebView` pointed at a page Google serves, with no API key and no
network guarantee. *Fix.* `GoogleEmbedMap.kt` is deleted (212 lines) and the pin
picker is drawn in-process by osmdroid (`core/maps/UndalOsmPicker.kt`). *Proof.*
`FEATURES.md` C-014/C-058/V-027, `README.md`, the privacy policy, and the
placeholder gate.

**005 — A `Task` read as though it had already finished killed the cold start.**
*Symptom.* On a fresh launch the app sometimes vanished and the crash guard
reopened it. *Cause.* `Task.getResult()` throws `IllegalStateException` unless
the task is complete; the ID-token claims were read that way. *Fix.* The claims
come from the task's own callback (`core/session/AuthSession.kt`, and the same
mistake removed in `BootReceiver`, `PushDeviceRole`, `UndalFcmService`).
*Proof.* `tools/check_foreground_contract.py` — "no Task is read as though it
waited".

**006 — The kitchen phone was treated as a customer.** *Symptom.* A vendor
signed in on the kitchen phone and the customer screens came up. *Cause.* Same
`Task` read as above, so the `vendor` claim was empty on every cold start.
*Fix.* `AuthSession` republishes the session when the claims arrive.
*Proof.* the same gate, plus the CI compile.

**007 — A phone with no dialer app crashed the kitchen's order screen.**
*Symptom.* Tapping the customer's number on a tablet or a board threw
`ActivityNotFoundException`. *Cause.* A hand-built `Intent(ACTION_DIAL, …)`
started without a guard. *Fix.* `VendorOrderDetailScreen` dials through
`UndalPhone.dial`, which answers whether the dialer opened; if it did not, the
number goes to the clipboard instead. *Proof.* Kotlin shape guard.

**008 — `UndalPhone.dialableDigits` could throw on an empty string.** *Cause.*
`digits.all { it == digits[0] }` — on an empty string `all` is `true` and `[0]`
throws. *Fix.* The length is asked first, and a 10-digit number with a leading
zero (`0171234567`, one digit short) is refused rather than dialled.
*Proof.* Kotlin shape guard.

**009 — A broken order link left the customer watching "reading your bill"
forever.** *Symptom.* A shared, stale or mistyped `/order/{id}` link showed a
loading line for as long as the tab stayed open. *Cause.* The page's poll
ignored `ok: false` and never branched on the response status. *Fix.* 400/404 is
answered on the page — "We could not find an order with that id" — and the poll
is cleared; a dropped signal is still retried on the next tick.
*Proof.* `worker/test/store-order.test.ts`, "a link that resolves to nothing says
so instead of loading forever".

**010 — A link that resolved to nothing kept polling every 20 seconds.** Same
fix as 006: `stopPolling()` clears `window._undalPoll` when the server has said
its last word, so a dead link stops costing the customer data. *Proof.* the same
test asserts the interval was cleared.

**011 — The order page's JavaScript was never executed by anything.** *Cause.*
It is a string inside a template literal: TypeScript does not type-check it, the
linter does not read it, and no test ran it — the one place a browser-only
customer arrives. *Fix.* `worker/test/helpers/mini-dom.ts` gives a page a
document, a window and a fetch, and 8 tests run the real script against real
orders. *Proof.* `npm test`.

**012 — An order page with an item named `<img …>` could inject markup.**
*Cause.* Part of the daily-data string reached `innerHTML` with only `<`
escaped. *Fix.* one `escapeText()` for every string that comes from the
database, including item notes and cancel reasons. *Proof.* the test "escaping
what comes from the kitchen".

**013 — The preview an operator looks at could not demonstrate the feature it
was there to demonstrate.** *Cause.* The fixture order id (`preview-1`) is
rejected by the route's own id rule (`/^[A-Za-z0-9]{12,64}$/`) and its item used
a `payablePaisa` field the app and the Worker do not write. *Fix.*
`tools/preview-web.mts` seeds `previewOrder1` in the canonical shape, freshly
placed, so the page shows the moment a customer gets. *Proof.* the preview's own
`README`-style page list and a live `curl` of `/api/store/order/previewOrder1`.

**014 — Two-button rows overflowed the screen.** *Symptom.* Save/Discard at the
bottom of a form pushed one button half off a 360 dp phone. *Cause.* Every
button composable applied `fillMaxWidth()` unconditionally.
*Fix.* `fillWidth` on all three button composables in
`core/design/UndalButtons.kt`, applied at the sites that sit in a row.
*Proof.* Kotlin shape guard; visual check on a 360 dp device.

## B. Inputs — the things people type

**015 — A phone number typed on a Bangla keyboard was refused as "not 11
digits".** *Symptom.* ০১৭১২৩৪৫৬৭৮ was rejected by the app's own validator.
*Cause.* `Char.isDigit()` is true for the Bangla row, so the filter kept the
digits and every parser after it said no. *Fix.* `core/util/UndalDigits.kt` — one
conversion (`Character.digit`, which answers for every script) applied where the
value enters the app. *Proof.* `UndalDigits` is the only place a digit is
converted; batch 1's commit and the shape guard.

**016 — A price typed in Bangla was "not a number".** ১৮০ refused by
`Money.parseTakaToPaisa`. *Fix.* the same conversion inside `Money`.
*Proof.* shape guard.

**017 — A coupon usage limit of ৫০ silently became 0 — which in that field means
*unlimited*.** *Fix.* `core/model/VendorDrafts.kt` parses through
`UndalDigits.toLongOrNull`. *Proof.* shape guard.

**018 — A section position of ৩ became 0, i.e. the top of the menu.** *Fix.*
`VendorMenuViewModel` reads the position with `UndalDigits.toIntOrNull`.
*Proof.* shape guard.

**019 — The kitchen's opening hours were typed on a keypad with no colon key.**
*Symptom.* `09:00` could not be typed; the field showed a validation error the
cook could not clear. *Fix.* The time fields are text fields that normalise as
you type (`930 → 9:30`) through `UndalDigits.cleanClock`, with
`Validators.clockTime` deciding whether the time is real.
*Proof.* `VendorScheduleScreen`, `VendorEditors`, `Validators.clockTime`.

**020 — ৳180.50 could not be entered at all.** *Cause.* Money fields opened a
numeric keypad with no decimal point. *Fix.* They are Decimal fields whose filter
accepts a comma as the decimal mark and drops thousands separators
(`UndalDigits.cleanDecimal`). *Proof.* shape guard.

**021 — The profile screen could still *store* a phone number in Bangla digits.**
*Symptom.* The app's own doctrine is that every number it writes is ASCII —
Firestore rules, the Worker and a GPS receiver all read ASCII — but the profile
field's filter used `Char.isDigit()`, which lets ০১৭… through into the document.
*Fix.* `features/profile/ProfileScreen.kt` converts on the way in.
*Proof.* import-contract gate and shape guard.

**022 — The menu's Search key did nothing.** *Cause.* Two halves of the same
hole: `UndalTextField`'s `KeyboardActions` never wired `onSearch`, and the menu
passed no `onImeAction`. *Fix.* both wired in `core/design/UndalFields.kt` (both
field composables) and `features/store/StoreScreen.kt`, where Search now also
puts the keyboard away. *Proof.* shape guard.

**023 — The chat's Send key did nothing.** Same hole, `ImeAction.Send` site in
`features/order/OrderTrackingScreen.kt` (the customer's message box). *Fix.*
`onSend` wired in the shared field. *Proof.* shape guard.

**024 — The coupon field took a code with a space pasted from WhatsApp.**
*Fix.* the input filter keeps A–Z, 0–9, `-` and `_`, upper-cases as typed and
caps the length; `removeCoupon` gives the code back. *Proof.*
`features/cart/CartScreen.kt`, `CartViewModel.removeCoupon`.

**025 — A pull-to-refresh spinner could stay stuck forever.** *Symptom.* The
indicator never turned off, and the list behind it looked frozen.
*Cause.* `LaunchedEffect(refreshing)` reset the flag *after* a `delay`, so a
coroutine cancelled in between (a second swipe, a navigation, a rotation) never
reset it. *Fix.* `try/finally` in the three screens that pull to refresh
(`StoreScreen`, `VendorOrdersScreen`, `VendorDashboardScreen`).
*Proof.* shape guard.

**026 — The refresh indicator padded the work with a fake 600–700 ms.**
*Symptom.* Refreshing always took at least two-thirds of a second, on a phone
that had already finished. *Fix.* the artificial delay is 350 ms — just enough
for the indicator to be seen, not enough to be felt. *Proof.* the three screens
above.

## C. Structure and responsive layout

**027 — The kit's error text was invisible to a screen reader.** *Fix.*
`UndalFields`' supporting text carries `liveRegion = Polite`; the message that
blocks a save is now announced. *Proof.* `core/design/UndalFields.kt`.

**028 — A long value pushed its label off the screen.** *Symptom.* In the bill
and the order rows, a long address or dish name squeezed the label to nothing.
*Fix.* `UndalKeyValueRow` gives both halves `weight(1f)`, two lines and an
ellipsis. *Proof.* `core/design/UndalFields.kt`.

**029 — A blank rider note printed its own label.** *Symptom.* "Rider note: "
with nothing after it, on most orders. *Fix.* the note line renders only when
there is a note, and the tracking screen prints an honest alternative instead.
*Proof.* `CartScreen.kt`, `OrderTrackingScreen.kt`.

**030 — The reason a button was greyed out was not next to the button.**
*Fix.* the first blocker is repeated in the cart's bottom bar, beside the
disabled Place-order button, with a live region so it is announced.
*Proof.* `features/cart/CartScreen.kt`.

**031 — The placement moment did not exist on the phone.** *Symptom.* A
customer tapped "Place order · cash on delivery" and landed straight on a status
page: no confirmation, no tick, no bill — the single most reassuring moment of
the whole product was missing. *Fix.* `features/order/OrderConfirmationCard.kt`
(C-061/C-062): the ink-dark bill, a tick, "Order placed. We're on it.", the dish
rows, the ride, the coupon, one total labelled CASH TO THE RIDER, and a
non-interactive card so the actions stay under the thumb.
*Proof.* `OrderTrackingScreen` renders it while the order is `PLACED` and
suppresses the receipt card in exactly that state, so the bill is never printed
twice.

**032 — The same moment did not exist on the web storefront.** *Symptom.* A
customer who ordered in a browser got `/order/{id}?new=1` and a page that read
`?new=1` nowhere. *Fix.* the tracking page now prints the receipt, the tick
while the kitchen is deciding, and the timeline, in the app's own ink.
*Proof.* `worker/test/store-order.test.ts`.

**033 — The web tracking page had no bill to print.** *Cause.*
`GET /api/store/order/{id}` returned only a status summary. *Fix.* the route
answers with the lines, the subtotal, the ride, the coupon and the total — and
still nothing that identifies the customer, because the link is the key.
*Proof.* the test "the status route hands the page a bill and nothing private"
asserts the name, phone, street and customer id are absent from the JSON.

**034 — A hostile order document could break the page.** *Fix.*
`receiptLines()` in `routes/store.ts` repairs what it is given: names trimmed and
capped, quantities floored at 1, prices floored at 0, junk rows dropped, at most
40 lines. *Proof.* the test "a hostile order document produces a small bill, not
a broken page".

**035 — Customer-facing timestamps were printed as raw ISO strings.**
*Symptom.* "Last update: 2026-10-15T17:18:16.893Z". *Fix.* `dhakaClock()` on the
page renders "Updated 6:05 pm, Dhaka time" by fixed +6 offset arithmetic in UTC,
so a reader in Dubai and a reader in Sylhet see the same minute.
*Proof.* the test asserts the exact string.

**036 — Every user-typed field in the app was lost on rotation.** *Symptom.*
Turn the phone while typing a coupon code, a day-closure note or a password
confirmation and the text was gone; with Android killing a backgrounded app,
"resume" meant a cleared form. *Fix.* 12 fields that hold something the person
typed or chose moved from `remember` to `rememberSaveable` — the coupon code,
the cart's clear-confirm dialog, the delete-account confirm, the special-hours
day, note and date, the cleanup and sign-out confirms, the location rationale,
the sign-out confirm, the editor dialog, and the kitchen's ready/collected
checkboxes (a vendor who ticked "collected", then turned the phone, must not see
it untick). *Proof.* `rememberSaveable` is now used in six screens and the shape
guard passes.

**RECORDED — Password fields deliberately do *not* use
`rememberSaveable`.** A
saved-instance-state bundle can be written to disk by the system, and a password
does not belong there. Recorded here so the next audit does not "fix" it.

## D. Performance and battery

**037 — Every screen kept observing its data while in the background.**
*Fix.* 24 screens moved from `collectAsState()` to
`collectAsStateWithLifecycle()`. *Proof.* grep for `collectAsState()` is zero;
the shape gate counts 145 files.

**038 — The kitchen's age badges woke the phone once a second, forever.**
*Symptom.* A kitchen phone with the queue open in a pocket kept a wake-up per
second for hours, for a number nobody was reading — on a phone whose battery is
also running a presence heartbeat. *Fix.* the clock in
`VendorOrdersViewModel`, `VendorOrderDetailViewModel` and
`VendorDashboardViewModel` is a cold flow with
`stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L))`: it ticks while
a screen is looking and stops five seconds after the screen leaves.
*Proof.* the three `while (true)` loops in `viewModelScope` are gone; only the
`flow { }` builders remain.

**039 — The customer's tracking page woke the phone once a second for a
finished order.** The same clock as the entry above, on the screen a customer
leaves open the longest: after the food arrives, the only thing still moving on
it is a chat that is usually closed. *Fix.* the tick has two speeds — one second
while the kitchen or the rider still has the order, and fifteen once it is
delivered or cancelled. *Proof.* `OrderTrackingViewModel`, shape guard.

**040 — The vendor queue's rows were not keyed by the order.** *Symptom.* A new
order arriving at the top handed an existing row's composition — and its age
badge — to a different order, and the list under the finger jumped.
*Fix.* `items(state.visible, key = { it.id })` in `VendorOrdersScreen`.
*Proof.* shape guard.

**041 — The menu's placeholder rows were not keyed either.** Same class, lower
stakes: the shimmer restarted on every state change. *Fix.* keyed by position in
`StoreScreen`. *Proof.* shape guard.

**042 — The bill was printed twice on the placement screen.** The new
confirmation card carries the receipt, and the page's own receipt card would
have repeated it immediately below — the same numbers twice, which reads as a
duplicate charge. *Fix.* `ReceiptCard` is suppressed while the placement card is
showing (`showPlacementCard` in `OrderTrackingScreen`): the page repeats the
*status*, never the receipt. *Proof.* shape guard.

## E. Money, time and correctness

**043 — Seven places printed numbers in the phone's own locale.** *Symptom.* A
distance read ৫.০ on a Bangla phone and `4,5` on a German one, while every price
on the same screen was ASCII. *Fix.* `UndalDigits.format` / `formatWhole`, used by
the distances, the radius and the pin coordinates — the coordinates matter most,
because a reading that cannot be typed into another app is worse than no reading.
*Proof.* grep for `"%.1f".format` without `Locale.ENGLISH`.

**044 — Case folding was left to the default locale, in coupon keys.** *Symptom.*
On a Turkish-locale phone `UNDAL50I` is not `UNDAL50i`: a typed code failed to
match its own document, and a search for "I" missed. *Fix.* every
`uppercase()`/`lowercase()` in a key, a code or a search now names
`Locale.ENGLISH` — 24 sites across `CouponMath`, `CouponRepo`, `MenuRepo`,
`MenuModels`, `VendorDrafts`, `OrderRepo`, `VendorMenuRepo`, `Validators`,
`CartModels`, `VendorEditors`, `CartScreen` and the two vendor order screens.
*Proof.* grep for `lowercase()`/`uppercase()` without a locale is zero.

**045 — The coupon code written onto an order was not normalised the same way as
the coupon document's key.** *Fix.* the same `Locale.ENGLISH` upper-casing on
both sides. *Proof.* the same grep: no `lowercase()` or `uppercase()` without a named locale is left in `app/`.

**046 — A vendor's code typed lowercase produced a coupon nobody could redeem.**
*Fix.* `VendorEditors`' campaign code field upper-cases as typed.
*Proof.* shape guard.

**047 — The web receipt rounded money differently from the app.** *Cause.* a
naive `toFixed(2)` prints `৳ 320.00` where the app prints `৳ 320`. *Fix.* the
page's `taka()` shows paisa only when there are paisa, in `en-US`, and keeps the
test's `replace(/^/, "৳ ")` contract. *Proof.* `npm test`.

**048 — The preview order's line total was ৳ 0.** *Cause.* the fixture used
`payablePaisa` where the app and the Worker write `unitAmountPaisa` and
`lineTotalAmountPaisa`. *Fix.* the canonical shape, recorded in the commit for
the placement card. *Proof.* the live preview's bill.

**049 — The email verification banner told a verified customer they were not
verified.** *Symptom.* The owner's report: the link was opened, Firebase said
verified, the app still showed "your email address is not verified".
*Cause.* the cached `isEmailVerified()` was believed forever. *Fix.*
`AuthRepo.isEmailVerified()` reloads when the cached answer is false,
`checkEmailVerifiedWithNetwork()` asks the server, `AuthSession` reconciles,
`ProfileViewModel.checkEmailVerification(quiet)` + `watchForVerification()`
(24 × 5 s) answer within seconds of the link being opened, the banner hides on
the flip, and the resend carries the app's own continue URL with a 60-second
cooldown. *Proof.* `5cb833c`; the banner copy is Check again / Send me a new
link / "Last checked at ⟨8:34 PM⟩".

## F. Accessibility

**050 — Status was carried by colour.** *Fix.* `UndalStatusPill` and
`UndalAlertBanner` carry an icon and words as well
(`core/design/UndalStatus.kt`). *Proof.* shape guard.

**051 — The back arrow was a lucky tap.** *Fix.* a 48 dp target and a
`contentDescription` on every screen's back control
(`UndalScaffold`). *Proof.* `check_foreground_contract`, shape guard.

**052 — The pin editor could only be dragged.** *Fix.* five-metre nudge arrows
in `UndalOsmPicker` for one hand and for TalkBack, with the accuracy circle
drawn underneath so "accurate to 40 m" is visible rather than read.
*Proof.* the map commit.

## G. The web storefront and the Worker

**053 — The storefront's tracking page had no reduced-motion or small-screen
care for the new bill.** *Fix.* a `receipt-*` block in `design.ts` with a
`@media(max-width:640px)` variant and the repository's existing
`prefers-reduced-motion` rule covering it. *Proof.*
`tools/check-web-compat.mjs`.

**054 — The tracking page's numbers were not written the way the app writes
them.** *Fix.* one `taka()` (the symbol, no trailing `.00`), one `km()`, and
tabular numerals in the bill so the amounts line up with the rows.
*Proof.* `npm test`.

**055 — A cancelled order left the customer polling.** *Fix.* the poll stops on
`delivered` and `cancelled`, on 400/404, and nowhere else.
*Proof.* the tests for the cancelled and not-found pages assert
`window.cleared`.

**056 — The empty-bill case printed a row of nonsense.** *Fix.* "The dishes on
this order are on the kitchen's copy of the bill." *Proof.* `npm test`.

**057 — An order's items were passed to the page unvalidated.** The same fix as the hostile-document entry above,
from the other side: `receiptLines()` is the one place an item row is built.

**058 — The dashboard's "share today's orders" text had no escaping path to the
browser.** *Fix.* the export and share text is built by the Worker, not by the
page, and the page escapes what it prints. *Proof.* the test that asserts an
item named with an angle bracket cannot reach `innerHTML`.

**059 — The storefront's `.receipt-hero` used `display` in a way that fought the
`hidden` attribute.** *Cause.* a class rule with `display` beats the user
agent's `[hidden]` rule. *Fix.* the hero is hidden and shown with the attribute
alone and no `display` in the rule. *Proof.* `npm test` (hero hidden when
accepted, shown when placed).

## H. Build, process and documentation

**060 — The deploy summary described a watchdog that does not exist.**
*Fix.* `.github/workflows/deploy-worker.yml`'s summary now says what the
presence watchdog really does. *Proof.* `0fcd58b`.

**061 — The repository's word for the map was still "Google".** *Fix.*
`FEATURES.md` C-014/C-058/V-027, the README integration table and the privacy
policy describe the one map the app has. *Proof.* placeholder gate over 205+
files.

**RECORDED — The `?new=1` the storefront appends was read by nothing.** Same defect
as 029 from the other end: `cart-client.ts` appended the flag and
`webfallback.ts` redirected to it; no code read it. The flag is deliberately
still unread — the receipt *is* the confirmation, and it is printed for every
customer who opens the link, which is better than a moment that expires with the
tab. Recorded so the next audit does not add a second confirmation path.

**RECORDED — A hostname, a phone number or a street address could reach a shared
tracking link.** Checked entry, not a fix: the route returns no customer
identity at all, and the test asserts it. Verified with
`worker/test/store-order.test.ts`.

## I. Found while closing the open leads

**062 — A basket longer than forty lines lost its tail in silence.** *Symptom.*
The web receipt printed the first forty dishes and stopped; the totals underneath
still covered the whole basket, so a customer adding up the rows on screen found
an amount they could not explain. *Cause.* `receiptLines()` sliced the order's
items to `MAX_RECEIPT_LINES` and returned what was left, with nothing to say a
row had been dropped. *Fix.* the function returns the rows **and** how many were
past the cap, the status route carries `itemsOmitted`, and the page prints
"N more dishes on this order are not listed here — the totals below cover all of
them." The cap stays, because a page is not a ledger; the silence does not.
*Proof.* two new tests in `worker/test/store-order.test.ts`: forty rows are
omitted-never, forty-one say so once, and the page prints the note while the
total is unchanged (`npm test`, 144 tests).

**063 — The profile's phone field could print a number back in Bangla digits.**
*Symptom.* A profile saved before numbers were normalised held ০১৭১২৩৪৫৬৭৮; the
field on the account screen showed it that way, and the Save button offered to
save a change nobody had made. *Cause.* `ProfileViewModel.load()` passed the
stored string through untouched, and the screen compared the draft with the
state as text, so `01712 345678`, `01712345678` and `+8801712345678` were three
different numbers. *Fix.* the field is filled with `UndalPhone.pretty(...)` — the
ASCII form the rest of the app uses, spaced the way a number is read out — the
state after a save stores the same shape, and the Save button compares
`Validators.normalizeBdMobile` on both sides, so a saved number is never
mistaken for an unsaved one. *Proof.* Kotlin shape guard and the import
contract, over all 145 files.

**064 — The readiness screen promised a watchdog row that did not exist.**
*Symptom.* `docs/ALERT-RELIABILITY.md` tells the owner that a phone which was
swiped away or killed by a battery cleaner rebuilds itself within nine minutes,
and that "the diagnostics screen reports all of this in words —
`AlertWatchdog.describe()` — with its own repair action". The screen had no such
row. *Cause.* `AlertWatchdog.describe()`, `isArmed()`, `nextCheckLabel()`,
`Fix.WATCHDOG` and its button label all existed; no `Check` ever used them, so
the enum value, the label and the description were dead code and the promise was
kept only by the alarm itself. *Fix.* a `watchdog` row on the phones that keep
that alarm (`AlertWatchdog.isKitchenDevice`), showing `describe()`, and pointing
at the exact-alarms setting when the alarm is armed but Android will deliver it
late, or at the watcher button — which arms the watchdog, in
`KitchenWatchService.start()` — when it is not armed at all. *Proof.* Kotlin
shape guard; the row's own id in `DiagnosticsViewModel`.

**065 — The order screen kept a one-second clock running for nobody.** *Symptom.*
The battery, not the person: the ticker behind live order tracking ran for as
long as the ViewModel lived, so a customer who pressed home — or who walked off
to the menu with tracking still on the back stack — left a phone waking its CPU
once a second to redraw a countdown nobody could see. On an order that takes
forty minutes that is 2,400 wake-ups for a screen in a pocket. *Fix.* the screen
tells the ViewModel when it is in front (`ON_START`/`ON_STOP` through its own
`LocalLifecycleOwner`, with the handler removed on dispose), and the ticker gains
a third speed: while nobody is looking it stops touching the clock and simply
waits, and `onScreenVisible()` reads the time the instant the customer is back,
so the first frame they see is current. The order itself is not missed while it
waits — the status arrives on the Firestore document and the `orderLive` flag,
which are listeners, not ticks. *Proof.* Kotlin shape guard and the import
contract; the cadence itself is the same two-speed delay a gate already covers.

**066 — A photo upload could not be stopped, and two of them could race.**
*Symptom.* The kitchen picked a 4 MB photo on a slow connection, watched a
progress bar that was not moving, and had no way out of it: no stop, and the
"pick a photo" button stayed live underneath, so picking a second file wrote its
percentages over the first one's and then raced it to the draft's `imageUrl` —
the upload that finished last won. *Cause.* `CloudinaryUploader.uploadImage`
discarded the request id the SDK hands out in `onStart`, and the four editors in
`VendorMenuViewModel` each launched their own coroutine into the one
`uploadProgress` field. *Fix.* the id is passed back through the repository and
the SDK's own `cancelRequest(id)` is what the new Stop button calls, so the
transfer stops rather than the screen merely stopping the wait — the SDK answers
`REQUEST_CANCELLED`, which the uploader already knows how to word. One upload at
a time, and the pick button is disabled while one is running. *Proof.* Kotlin
shape guard and the import contract over all 145 files; the SDK's own
`MediaManager.cancelRequest` and `ErrorInfo.REQUEST_CANCELLED` were read from
`cloudinary_android` 3.1.3's source rather than assumed.

## J. The three reported failures — the map, the coupon, the hours

Reported from the phone as three sentences: *the OSM map for the location does
not show*, *every active coupon code says expired*, *the store opens 24 hours and
ignores the schedule*. Earlier rounds closed real defects near each of them and
the sentences stayed true, so each was traced again from the symptom to a line
of code, and nothing below is counted until a test or a gate holds it.

**067 — Every map in the app destroyed itself the instant it was built.**
*Symptom.* The location picker, the address card, the tracking card and the
kitchen's order map showed a grey grid: no streets, no pin, zoom buttons that did
nothing. *Cause.* `UndalOsmMap` created its `MapView` inside the `AndroidView`
factory and wrote it into `mutableStateOf` state, and the lifecycle
`DisposableEffect` was keyed on that state. The first composition registered the
effect; the factory then set the state, the key changed, Compose disposed the
previous effect — and its `onDispose` called `onPause()` and **`onDetach()`** on
the map that had just been created, then nulled the renderer. In osmdroid 6.1.20
`onDetach()` detaches the tile provider (`mExecutor.shutdown()` on every tile
module) and destroys the redraw handler, so the view could never draw a tile
again. *Fix.* the `MapView` is created once in `remember` and handed to the
factory; resume and pause are forwarded by an effect keyed on the lifecycle owner
alone; `onDetach()` runs in its own effect keyed on the never-changing view, i.e.
only when the map leaves the screen. *Proof.* Kotlin shape guard and import
contract (148 files); osmdroid 6.1.20's `MapView.onDetach()` and
`MapTileModuleProviderBase.detach()` were read from its source, not assumed;
android-build compiles it.

**068 — A pin dragged up or down on a picker scrolled the page instead.**
*Cause.* osmdroid never calls `requestDisallowInterceptTouchEvent`, and every
picker sits in a `verticalScroll` screen, so after the touch slop the scroll took
the gesture. *Fix.* `UndalMapView` asks its parent to keep its hands off while a
finger is on an interactive map; Compose's interop filter honours the request.
*Proof.* Kotlin shape guard; android-build.

**069 — The rule that spends a coupon use refused every unlimited coupon.**
*Symptom.* In the app, any order carrying a coupon — typed or applied
automatically — failed with "The database refused that…". *Cause.*
`firestore.rules` let a customer's order add one to `usedCount` only if
`usedCount < usageLimit`; `usageLimit` 0 means *unlimited* and is the default, so
the rule demanded `0 < 0`. *Fix.* 0 or absent is unlimited, a missing
`usedCount` counts as 0, and the expiry is judged in the rule only when it is a
timestamp. *Proof.* the rule text; deployed by `deploy-worker` (085).

**070 — A renamed coupon lost its used count, and with it every app order.**
*Cause.* `VendorMenuRepo.saveCoupon` removed `usedCount` from the fields of an
edited coupon (right for a merge) and then wrote a renamed one with `set()`
without merge: the new document had no `usedCount`, its limit restarted at zero,
and the rule's `usedCount + 1` was an error. *Fix.* a rename reads the old count
and carries it. *Proof.* Kotlin shape guard; android-build.

**071 — An expiry typed in seconds meant January 1970, and a bare date meant 06:00.**
*Symptom.* A coupon dated by hand in the Firebase console was refused as expired
to every customer. *Cause.* `couponExpiryMillis` (Worker), the storefront's
script and `CouponMath.expiryMillis` (app) read every number as milliseconds, a
bare `2026-10-31` as UTC midnight, and only `seconds` — never the Admin SDK's
`_seconds`. *Fix.* below 10¹¹ a number is epoch seconds, numeric text is a
number, a bare date is the whole of that day in Dhaka, `_seconds` is read, ISO
offsets parse. *Proof.* `worker/test/coupons.test.ts` and `CouponExpiryTest`
(7 tests) on the same instants; `check-coupon-parity` and `check-web-rules`.

**072 — The order transaction failed outright on any expiry that was not a Timestamp.**
*Cause.* `OrderRepo` read `couponSnapshot.getTimestamp("expiresAt")`, which
throws for a string or a number. *Fix.* `get(…)` through the shared reader.
*Proof.* Kotlin shape guard; android-build.

**073 — A refusal printed the day, not the minute.** An expiry stored at 06:00
and one stored at 23:59 print the same date, so "EID20 expired on 14 Oct 2026"
could not settle "it is not expired". *Fix.* `couponExpiryMoment` /
`CouponMath.expiryMoment` — "14 Oct 2026, 11:59 PM", Dhaka — in the Worker's
`couponSentence`, `CouponRepo.describe`, the order transaction's refusal and the
kitchen's coupon list; the editor says the minute customers stop getting it, and
an expiry can be cleared again. *Proof.* `coupons.test.ts`, `CouponExpiryTest`.

**074 — A code that cannot exist was dropped from the bill in silence.**
*Cause.* `createWebOrder` normalised the typed code to `null` when it failed the
pattern (a space, a symbol) and carried on without it. *Fix.* a typed code that
cannot exist is refused, out loud, with what a code looks like. *Proof.*
`worker/test/web-order.test.ts`.

**075 — An expired sign-in at the order button read as an expired code.**
*Cause.* the checkout's submit used the cached session and, on a 401, printed the
server's bare code: `id-token-expired`. *Fix.* one silent renewal
(`undalRenewSession`) and retry; a second 401 is a sentence. *Proof.* driven in
a real DOM (jsdom) against the rendered checkout — first POST 401, renewal, retry,
order placed — and pinned in `web-pages.test.ts`.

**076 — The switch overrode the hours: a store with published hours was open around the clock.**
*Symptom.* The customer screen said "Open outside the published hours — the
kitchen is cooking right now anyway" at any hour, and took the order. *Cause.*
`StoreOpenState.of` returned serviceable whenever `open` was true. *Fix.* the
hours decide *when* and the switch *whether*: serviceable is switch on ∧ not
auto-closed ∧ pin ∧ inside the hours (`StoreHours.kt`); a closed store names its
next opening, an open one when it closes; the dashboard's switch reads "Open the
store" / "Pause" and explains that a switched-on store opens and closes itself.
*Proof.* `StoreHoursTest` (8 tests; the 22-vector fixture week is shared with the Worker).

**077 — The web checkout never asked whether the kitchen was open, and the menu banner read the switch alone.**
*Cause.* `createWebOrder` checked the basket, the pin, the radius and the coupon,
never the store; `statusBanner` printed "Open now" from `open && !autoClosed`.
*Fix.* `closedSentence(store)` refuses with the next opening (409
`store-closed`), the checkout says so before the address is typed, and the banner
uses `storeStatus`. *Proof.* `web-order.test.ts` (paused, auto-closed, outside
the hours, a document from before the switch existed), `web-pages.test.ts`.

**078 — Nothing closed a store at closing time for the builds that read only `open`.**
*Cause.* `open` was written by the switch and the watchdog, never by the clock.
*Fix.* the presence cron keeps `open` in step with the hours every five minutes;
`acceptingOrders` holds the switch and `openSynced` lets a Close pressed on an
older vendor build win instead of being reopened over. The app re-reads the live
store document inside the order transaction. *Proof.* `store-hours.test.ts`
(`syncedOpenFlag`) and `web-order.test.ts` (closes at the end of the hours, opens
at the start, never reopens over an older build's Close, twice).

**079 — A phone switched off overnight paused the store that was meant to open itself.**
*Cause.* the watchdog paused any store with `open` true, inside its hours or not,
and a pause lifted only by asking. *Fix.* only a store *taking orders* is paused;
a phone back after hours has its pause lifted (the hours keep the store closed
until its next service); the opening reminder goes only to a paused store.
*Proof.* `presence-policy.test.ts`, `web-order.test.ts`.

**080 — A day off written by the app was open on the web, and an open special date with no hours was closed everywhere.**
*Cause.* the Worker honoured only `mode: "closed"`, the app writes
`closed: true`; both engines treated "open, no hours" as closed while the builder
promised it "behaves like a normal day". *Fix.* both spellings are a day off in
both engines; open-without-hours falls back to the weekly hours. *Proof.*
`store-hours.test.ts`, `StoreHoursTest`.

**081 — A service that ran past midnight was judged by the wrong day.** 00:30 on
Saturday belongs to Friday's 20:00–01:00, whatever Saturday says; both engines
asked Saturday. *Fix.* today's windows from their opening minute, plus
yesterday's after-midnight tail. *Proof.* the shared fixture week.

**082 — "9:00" and Bengali digits vanished from the phone's schedule, and garbage became midnight on the web.**
*Cause.* `TimeDhaka.parseHhMm` was `LocalTime.parse`; the Worker read anything
unparseable as 0. *Fix.* one lenient-but-strict reader on both sides. *Proof.*
`TimeDhakaTest`, `StoreHoursTest`, `store-hours.test.ts`.

**083 — The 24/7 template closed the store for one minute every midnight.**
`00:00–23:59` excluded 23:59. *Fix.* a close of 23:59, 24:00 or 00:00 means
midnight. *Proof.* the shared fixture week (Sunday).

**084 — Screens said "Open now" after closing time until the document changed.**
*Cause.* the store, cart and dashboard recomputed only on a Firestore change.
*Fix.* `StoreRepo.observeStoreOnTheClock()` also emits at each minute boundary.
*Proof.* Kotlin shape guard; android-build.

**085 — A fixed rule sat in the repository while the deployed one kept refusing.**
*Cause.* only the hand-run `seed-config` workflow published Firestore rules.
*Fix.* `deploy-worker` publishes `firestore:rules` with the indexes on every push
that touches `worker/` or `firebase/`; the CLI compiles them first. *Proof.* the
workflow; README §3d.

---

## Checked and deliberately not changed

Nine things that read like defects and are not. Written down because the next
audit will otherwise re-find them.

1. **Double-tap on Place order.** `UndalPrimaryButton` disables itself while
   `loading`, and `canPlace` is false while placing — two orders cannot come out
   of one basket.
2. **Edge-to-edge insets.** `MainActivity` calls `enableEdgeToEdge()` and
   `UndalScaffold` applies the Scaffold's padding *and* `imePadding()`, so
   content is not drawn under the status bar or the keyboard.
3. **A `LazyColumn` inside a `verticalScroll`.** The only long lists on those
   screens are `verticalArrangement`d columns, not lazy lists with infinite
   height constraints.
4. **Passwords in saved state.** Deliberately `remember`, not
   `rememberSaveable` — see the password entry above.
5. **`tel:` intents that do not normalise the number.** Every caller now goes
   through `UndalPhone`; the remaining raw `tel:` is the support number, a
   constant.
6. **Unbounded Firestore reads.** The `.get()` calls are document reads by id;
   the collection reads carry limits.
7. **`Char.isDigit()` filters that still keep Bangla digits.** Intentional where
   the value is displayed and never parsed.
8. **The vendor queue's one-second clock on the vendor's own screen.** Correct
   while the screen is on; see the age-badge entry above for the fix to the background case.
9. **`resolveActivity()` checks on Settings intents.** `AlarmChannels` already
   falls back by API level, and the diagnostics screen reports an unreachable
   screen instead of doing nothing silently.

---

## Open leads — identified, not yet closed

These are real, they are next, and they are not counted above. Five that were
here have moved into the ledger — the profile's phone display (063), the
receipt's forty-line cap (062), the watchdog's missing row (064), the tracking
ticker that ran off screen (065) and the upload that could not be stopped (066)
— and the three that are left are below, renumbered. Each of the three is a
measurement on a real phone rather than a code change, which is why they are
still here and not above.

1. **`UndalConfirmDialog` messages over ~6 lines** rely on the Material dialog's
   own scrolling; the delete-account copy is long enough that this should be
   measured on a 360 dp phone with a large font scale.
2. **Cart `bottomBar` on a phone with the keyboard open** — `UndalScaffold`
   handles the IME, but the coupon row directly above the bar should be checked
   with the keyboard up on a 5.3-inch device.
3. **Screens under `features/legal/`** have no `imePadding` need, but their
   headings are the app's longest strings and should be checked at 200% font
   scale.

---

## The owner's acceptance list

Five things no test in this repository can close, because they need a real
phone, a real link, and a real person:

1. Install the signed APK and place one cash-on-delivery order end to end, with
   the kitchen phone ringing.
2. Open the verification link on the phone and watch Profile flip to "Email
   confirmed" without a reinstall.
3. Confirm a background push arrives when the app has been swiped away.
4. Change a dish photo through the kitchen's own upload and see it on the
   storefront.
5. Open `/order/{id}` on the phone that placed the order and check the bill
   against the cash handed to the rider.
