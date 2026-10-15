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
| Closed, with a fix and a gate in this repository | **58** |
| Recorded as deliberate, or checked and already correct (not counted) | 3 |
| Open leads (identified, not yet fixed) | 8 |
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

## A. Crashes and robustness

**001 — The Google Maps embed was a grey box on the last step of signing up.**
*Symptom.* The owner reported the map "doesn't work"; a customer picking their
delivery pin saw an empty rectangle where the street should be. *Cause.* The
"map" was a `WebView` pointed at a page Google serves, with no API key and no
network guarantee. *Fix.* `GoogleEmbedMap.kt` is deleted (212 lines) and the pin
picker is drawn in-process by osmdroid (`core/maps/UndalOsmPicker.kt`). *Proof.*
`FEATURES.md` C-014/C-058/V-027, `README.md`, the privacy policy, and the
placeholder gate.

**002 — A `Task` read as though it had already finished killed the cold start.**
*Symptom.* On a fresh launch the app sometimes vanished and the crash guard
reopened it. *Cause.* `Task.getResult()` throws `IllegalStateException` unless
the task is complete; the ID-token claims were read that way. *Fix.* The claims
come from the task's own callback (`core/session/AuthSession.kt`, and the same
mistake removed in `BootReceiver`, `PushDeviceRole`, `UndalFcmService`).
*Proof.* `tools/check_foreground_contract.py` — "no Task is read as though it
waited".

**003 — The kitchen phone was treated as a customer.** *Symptom.* A vendor
signed in on the kitchen phone and the customer screens came up. *Cause.* Same
`Task` read as above, so the `vendor` claim was empty on every cold start.
*Fix.* `AuthSession` republishes the session when the claims arrive.
*Proof.* the same gate, plus the CI compile.

**004 — A phone with no dialer app crashed the kitchen's order screen.**
*Symptom.* Tapping the customer's number on a tablet or a board threw
`ActivityNotFoundException`. *Cause.* A hand-built `Intent(ACTION_DIAL, …)`
started without a guard. *Fix.* `VendorOrderDetailScreen` dials through
`UndalPhone.dial`, which answers whether the dialer opened; if it did not, the
number goes to the clipboard instead. *Proof.* Kotlin shape guard.

**005 — `UndalPhone.dialableDigits` could throw on an empty string.** *Cause.*
`digits.all { it == digits[0] }` — on an empty string `all` is `true` and `[0]`
throws. *Fix.* The length is asked first, and a 10-digit number with a leading
zero (`0171234567`, one digit short) is refused rather than dialled.
*Proof.* Kotlin shape guard.

**006 — A broken order link left the customer watching "reading your bill"
forever.** *Symptom.* A shared, stale or mistyped `/order/{id}` link showed a
loading line for as long as the tab stayed open. *Cause.* The page's poll
ignored `ok: false` and never branched on the response status. *Fix.* 400/404 is
answered on the page — "We could not find an order with that id" — and the poll
is cleared; a dropped signal is still retried on the next tick.
*Proof.* `worker/test/store-order.test.ts`, "a link that resolves to nothing says
so instead of loading forever".

**007 — A link that resolved to nothing kept polling every 20 seconds.** Same
fix as 006: `stopPolling()` clears `window._undalPoll` when the server has said
its last word, so a dead link stops costing the customer data. *Proof.* the same
test asserts the interval was cleared.

**008 — The order page's JavaScript was never executed by anything.** *Cause.*
It is a string inside a template literal: TypeScript does not type-check it, the
linter does not read it, and no test ran it — the one place a browser-only
customer arrives. *Fix.* `worker/test/helpers/mini-dom.ts` gives a page a
document, a window and a fetch, and 8 tests run the real script against real
orders. *Proof.* `npm test`.

**009 — An order page with an item named `<img …>` could inject markup.**
*Cause.* Part of the daily-data string reached `innerHTML` with only `<`
escaped. *Fix.* one `escapeText()` for every string that comes from the
database, including item notes and cancel reasons. *Proof.* the test "escaping
what comes from the kitchen".

**010 — The preview an operator looks at could not demonstrate the feature it
was there to demonstrate.** *Cause.* The fixture order id (`preview-1`) is
rejected by the route's own id rule (`/^[A-Za-z0-9]{12,64}$/`) and its item used
a `payablePaisa` field the app and the Worker do not write. *Fix.*
`tools/preview-web.mts` seeds `previewOrder1` in the canonical shape, freshly
placed, so the page shows the moment a customer gets. *Proof.* the preview's own
`README`-style page list and a live `curl` of `/api/store/order/previewOrder1`.

**011 — Two-button rows overflowed the screen.** *Symptom.* Save/Discard at the
bottom of a form pushed one button half off a 360 dp phone. *Cause.* Every
button composable applied `fillMaxWidth()` unconditionally.
*Fix.* `fillWidth` on all three button composables in
`core/design/UndalButtons.kt`, applied at the sites that sit in a row.
*Proof.* Kotlin shape guard; visual check on a 360 dp device.

## B. Inputs — the things people type

**012 — A phone number typed on a Bangla keyboard was refused as "not 11
digits".** *Symptom.* ০১৭১২৩৪৫৬৭৮ was rejected by the app's own validator.
*Cause.* `Char.isDigit()` is true for the Bangla row, so the filter kept the
digits and every parser after it said no. *Fix.* `core/util/UndalDigits.kt` — one
conversion (`Character.digit`, which answers for every script) applied where the
value enters the app. *Proof.* `UndalDigits` is the only place a digit is
converted; batch 1's commit and the shape guard.

**013 — A price typed in Bangla was "not a number".** ১৮০ refused by
`Money.parseTakaToPaisa`. *Fix.* the same conversion inside `Money`.
*Proof.* shape guard.

**014 — A coupon usage limit of ৫০ silently became 0 — which in that field means
*unlimited*.** *Fix.* `core/model/VendorDrafts.kt` parses through
`UndalDigits.toLongOrNull`. *Proof.* shape guard.

**015 — A section position of ৩ became 0, i.e. the top of the menu.** *Fix.*
`VendorMenuViewModel` reads the position with `UndalDigits.toIntOrNull`.
*Proof.* shape guard.

**016 — The kitchen's opening hours were typed on a keypad with no colon key.**
*Symptom.* `09:00` could not be typed; the field showed a validation error the
cook could not clear. *Fix.* The time fields are text fields that normalise as
you type (`930 → 9:30`) through `UndalDigits.cleanClock`, with
`Validators.clockTime` deciding whether the time is real.
*Proof.* `VendorScheduleScreen`, `VendorEditors`, `Validators.clockTime`.

**017 — ৳180.50 could not be entered at all.** *Cause.* Money fields opened a
numeric keypad with no decimal point. *Fix.* They are Decimal fields whose filter
accepts a comma as the decimal mark and drops thousands separators
(`UndalDigits.cleanDecimal`). *Proof.* shape guard.

**018 — The profile screen could still *store* a phone number in Bangla digits.**
*Symptom.* The app's own doctrine is that every number it writes is ASCII —
Firestore rules, the Worker and a GPS receiver all read ASCII — but the profile
field's filter used `Char.isDigit()`, which lets ০১৭… through into the document.
*Fix.* `features/profile/ProfileScreen.kt` converts on the way in.
*Proof.* import-contract gate and shape guard.

**019 — The menu's Search key did nothing.** *Cause.* Two halves of the same
hole: `UndalTextField`'s `KeyboardActions` never wired `onSearch`, and the menu
passed no `onImeAction`. *Fix.* both wired in `core/design/UndalFields.kt` (both
field composables) and `features/store/StoreScreen.kt`, where Search now also
puts the keyboard away. *Proof.* shape guard.

**020 — The chat's Send key did nothing.** Same hole, `ImeAction.Send` site in
`features/order/OrderTrackingScreen.kt` (the customer's message box). *Fix.*
`onSend` wired in the shared field. *Proof.* shape guard.

**021 — The coupon field took a code with a space pasted from WhatsApp.**
*Fix.* the input filter keeps A–Z, 0–9, `-` and `_`, upper-cases as typed and
caps the length; `removeCoupon` gives the code back. *Proof.*
`features/cart/CartScreen.kt`, `CartViewModel.removeCoupon`.

**022 — A pull-to-refresh spinner could stay stuck forever.** *Symptom.* The
indicator never turned off, and the list behind it looked frozen.
*Cause.* `LaunchedEffect(refreshing)` reset the flag *after* a `delay`, so a
coroutine cancelled in between (a second swipe, a navigation, a rotation) never
reset it. *Fix.* `try/finally` in the three screens that pull to refresh
(`StoreScreen`, `VendorOrdersScreen`, `VendorDashboardScreen`).
*Proof.* shape guard.

**023 — The refresh indicator padded the work with a fake 600–700 ms.**
*Symptom.* Refreshing always took at least two-thirds of a second, on a phone
that had already finished. *Fix.* the artificial delay is 350 ms — just enough
for the indicator to be seen, not enough to be felt. *Proof.* the three screens
above.

## C. Structure and responsive layout

**024 — The kit's error text was invisible to a screen reader.** *Fix.*
`UndalFields`' supporting text carries `liveRegion = Polite`; the message that
blocks a save is now announced. *Proof.* `core/design/UndalFields.kt`.

**025 — A long value pushed its label off the screen.** *Symptom.* In the bill
and the order rows, a long address or dish name squeezed the label to nothing.
*Fix.* `UndalKeyValueRow` gives both halves `weight(1f)`, two lines and an
ellipsis. *Proof.* `core/design/UndalFields.kt`.

**026 — A blank rider note printed its own label.** *Symptom.* "Rider note: "
with nothing after it, on most orders. *Fix.* the note line renders only when
there is a note, and the tracking screen prints an honest alternative instead.
*Proof.* `CartScreen.kt`, `OrderTrackingScreen.kt`.

**027 — The reason a button was greyed out was not next to the button.**
*Fix.* the first blocker is repeated in the cart's bottom bar, beside the
disabled Place-order button, with a live region so it is announced.
*Proof.* `features/cart/CartScreen.kt`.

**028 — The placement moment did not exist on the phone.** *Symptom.* A
customer tapped "Place order · cash on delivery" and landed straight on a status
page: no confirmation, no tick, no bill — the single most reassuring moment of
the whole product was missing. *Fix.* `features/order/OrderConfirmationCard.kt`
(C-061/C-062): the ink-dark bill, a tick, "Order placed. We're on it.", the dish
rows, the ride, the coupon, one total labelled CASH TO THE RIDER, and a
non-interactive card so the actions stay under the thumb.
*Proof.* `OrderTrackingScreen` renders it while the order is `PLACED` and
suppresses the receipt card in exactly that state, so the bill is never printed
twice.

**029 — The same moment did not exist on the web storefront.** *Symptom.* A
customer who ordered in a browser got `/order/{id}?new=1` and a page that read
`?new=1` nowhere. *Fix.* the tracking page now prints the receipt, the tick
while the kitchen is deciding, and the timeline, in the app's own ink.
*Proof.* `worker/test/store-order.test.ts`.

**030 — The web tracking page had no bill to print.** *Cause.*
`GET /api/store/order/{id}` returned only a status summary. *Fix.* the route
answers with the lines, the subtotal, the ride, the coupon and the total — and
still nothing that identifies the customer, because the link is the key.
*Proof.* the test "the status route hands the page a bill and nothing private"
asserts the name, phone, street and customer id are absent from the JSON.

**031 — A hostile order document could break the page.** *Fix.*
`receiptLines()` in `routes/store.ts` repairs what it is given: names trimmed and
capped, quantities floored at 1, prices floored at 0, junk rows dropped, at most
40 lines. *Proof.* the test "a hostile order document produces a small bill, not
a broken page".

**032 — Customer-facing timestamps were printed as raw ISO strings.**
*Symptom.* "Last update: 2026-10-15T17:18:16.893Z". *Fix.* `dhakaClock()` on the
page renders "Updated 6:05 pm, Dhaka time" by fixed +6 offset arithmetic in UTC,
so a reader in Dubai and a reader in Sylhet see the same minute.
*Proof.* the test asserts the exact string.

**033 — Every user-typed field in the app was lost on rotation.** *Symptom.*
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

**034 — Every screen kept observing its data while in the background.**
*Fix.* 24 screens moved from `collectAsState()` to
`collectAsStateWithLifecycle()`. *Proof.* grep for `collectAsState()` is zero;
the shape gate counts 145 files.

**035 — The kitchen's age badges woke the phone once a second, forever.**
*Symptom.* A kitchen phone with the queue open in a pocket kept a wake-up per
second for hours, for a number nobody was reading — on a phone whose battery is
also running a presence heartbeat. *Fix.* the clock in
`VendorOrdersViewModel`, `VendorOrderDetailViewModel` and
`VendorDashboardViewModel` is a cold flow with
`stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L))`: it ticks while
a screen is looking and stops five seconds after the screen leaves.
*Proof.* the three `while (true)` loops in `viewModelScope` are gone; only the
`flow { }` builders remain.

**036 — The customer's tracking page woke the phone once a second for a
finished order.** The same clock as the entry above, on the screen a customer
leaves open the longest: after the food arrives, the only thing still moving on
it is a chat that is usually closed. *Fix.* the tick has two speeds — one second
while the kitchen or the rider still has the order, and fifteen once it is
delivered or cancelled. *Proof.* `OrderTrackingViewModel`, shape guard.

**037 — The vendor queue's rows were not keyed by the order.** *Symptom.* A new
order arriving at the top handed an existing row's composition — and its age
badge — to a different order, and the list under the finger jumped.
*Fix.* `items(state.visible, key = { it.id })` in `VendorOrdersScreen`.
*Proof.* shape guard.

**038 — The menu's placeholder rows were not keyed either.** Same class, lower
stakes: the shimmer restarted on every state change. *Fix.* keyed by position in
`StoreScreen`. *Proof.* shape guard.

**039 — The bill was printed twice on the placement screen.** The new
confirmation card carries the receipt, and the page's own receipt card would
have repeated it immediately below — the same numbers twice, which reads as a
duplicate charge. *Fix.* `ReceiptCard` is suppressed while the placement card is
showing (`showPlacementCard` in `OrderTrackingScreen`): the page repeats the
*status*, never the receipt. *Proof.* shape guard.

## E. Money, time and correctness

**040 — Seven places printed numbers in the phone's own locale.** *Symptom.* A
distance read ৫.০ on a Bangla phone and `4,5` on a German one, while every price
on the same screen was ASCII. *Fix.* `UndalDigits.format` / `formatWhole`, used by
the distances, the radius and the pin coordinates — the coordinates matter most,
because a reading that cannot be typed into another app is worse than no reading.
*Proof.* grep for `"%.1f".format` without `Locale.ENGLISH`.

**041 — Case folding was left to the default locale, in coupon keys.** *Symptom.*
On a Turkish-locale phone `UNDAL50I` is not `UNDAL50i`: a typed code failed to
match its own document, and a search for "I" missed. *Fix.* every
`uppercase()`/`lowercase()` in a key, a code or a search now names
`Locale.ENGLISH` — 24 sites across `CouponMath`, `CouponRepo`, `MenuRepo`,
`MenuModels`, `VendorDrafts`, `OrderRepo`, `VendorMenuRepo`, `Validators`,
`CartModels`, `VendorEditors`, `CartScreen` and the two vendor order screens.
*Proof.* grep for `lowercase()`/`uppercase()` without a locale is zero.

**042 — The coupon code written onto an order was not normalised the same way as
the coupon document's key.** *Fix.* the same `Locale.ENGLISH` upper-casing on
both sides. *Proof.* the same grep: no `lowercase()` or `uppercase()` without a named locale is left in `app/`.

**043 — A vendor's code typed lowercase produced a coupon nobody could redeem.**
*Fix.* `VendorEditors`' campaign code field upper-cases as typed.
*Proof.* shape guard.

**044 — The web receipt rounded money differently from the app.** *Cause.* a
naive `toFixed(2)` prints `৳ 320.00` where the app prints `৳ 320`. *Fix.* the
page's `taka()` shows paisa only when there are paisa, in `en-US`, and keeps the
test's `replace(/^/, "৳ ")` contract. *Proof.* `npm test`.

**045 — The preview order's line total was ৳ 0.** *Cause.* the fixture used
`payablePaisa` where the app and the Worker write `unitAmountPaisa` and
`lineTotalAmountPaisa`. *Fix.* the canonical shape, recorded in the commit for
the placement card. *Proof.* the live preview's bill.

**046 — The email verification banner told a verified customer they were not
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

**047 — Status was carried by colour.** *Fix.* `UndalStatusPill` and
`UndalAlertBanner` carry an icon and words as well
(`core/design/UndalStatus.kt`). *Proof.* shape guard.

**048 — The back arrow was a lucky tap.** *Fix.* a 48 dp target and a
`contentDescription` on every screen's back control
(`UndalScaffold`). *Proof.* `check_foreground_contract`, shape guard.

**049 — The pin editor could only be dragged.** *Fix.* five-metre nudge arrows
in `UndalOsmPicker` for one hand and for TalkBack, with the accuracy circle
drawn underneath so "accurate to 40 m" is visible rather than read.
*Proof.* the map commit.

## G. The web storefront and the Worker

**050 — The storefront's tracking page had no reduced-motion or small-screen
care for the new bill.** *Fix.* a `receipt-*` block in `design.ts` with a
`@media(max-width:640px)` variant and the repository's existing
`prefers-reduced-motion` rule covering it. *Proof.*
`tools/check-web-compat.mjs`.

**051 — The tracking page's numbers were not written the way the app writes
them.** *Fix.* one `taka()` (the symbol, no trailing `.00`), one `km()`, and
tabular numerals in the bill so the amounts line up with the rows.
*Proof.* `npm test`.

**052 — A cancelled order left the customer polling.** *Fix.* the poll stops on
`delivered` and `cancelled`, on 400/404, and nowhere else.
*Proof.* the tests for the cancelled and not-found pages assert
`window.cleared`.

**053 — The empty-bill case printed a row of nonsense.** *Fix.* "The dishes on
this order are on the kitchen's copy of the bill." *Proof.* `npm test`.

**054 — An order's items were passed to the page unvalidated.** The same fix as the hostile-document entry above,
from the other side: `receiptLines()` is the one place an item row is built.

**055 — The dashboard's "share today's orders" text had no escaping path to the
browser.** *Fix.* the export and share text is built by the Worker, not by the
page, and the page escapes what it prints. *Proof.* the test that asserts an
item named with an angle bracket cannot reach `innerHTML`.

**056 — The storefront's `.receipt-hero` used `display` in a way that fought the
`hidden` attribute.** *Cause.* a class rule with `display` beats the user
agent's `[hidden]` rule. *Fix.* the hero is hidden and shown with the attribute
alone and no `display` in the rule. *Proof.* `npm test` (hero hidden when
accepted, shown when placed).

## H. Build, process and documentation

**057 — The deploy summary described a watchdog that does not exist.**
*Fix.* `.github/workflows/deploy-worker.yml`'s summary now says what the
presence watchdog really does. *Proof.* `0fcd58b`.

**058 — The repository's word for the map was still "Google".** *Fix.*
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

These are real, they are next, and they are not counted above.

1. **The customer's order-tracking ticker** is slowed for a finished order (see
   the closed entry for it above), but while an order is live it still ticks
   inside `viewModelScope` (`OrderTrackingViewModel`) even when the screen sits
   on the back stack rather than in front of the customer. The three vendor
   screens were converted to `stateIn(WhileSubscribed)`; this one needs the same
   treatment without losing the imperative state writes around it.
2. **`ProfileScreen`'s phone field** now writes ASCII, but the *display* of a
   number stored by an older version can still show Bangla digits until the
   profile is saved once. A display-time `pretty()` is the fix.
3. **`UndalConfirmDialog` messages over ~6 lines** rely on the Material dialog's
   own scrolling; the delete-account copy is long enough that this should be
   measured on a 360 dp phone with a large font scale.
4. **Cart `bottomBar` on a phone with the keyboard open** — `UndalScaffold`
   handles the IME, but the coupon row directly above the bar should be checked
   with the keyboard up on a 5.3-inch device.
5. **Screens under `features/legal/`** have no `imePadding` need, but their
   headings are the app's longest strings and should be checked at 200% font
   scale.
6. **`VendorMenuScreen`'s image upload path** reports progress but no cancel
   affordance on a slow connection.
7. **The 40-line cap on a web receipt** is a judgement, not a measurement: an
   order with 41 lines loses one. It should instead say that the bill is long.
8. **`docs/ALERT-RELIABILITY.md`'s ladder** describes four fallbacks; the
   diagnostics screen shows three of them. The fourth is the watchdog's own
   alarm and is not surfaced.

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
