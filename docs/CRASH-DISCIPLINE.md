# Crash discipline: nothing in Undal may end the process

The symptom this document exists for, in the words it was reported in:

> When I open the Android app and login or signup as a customer the app
> automatically reopens sometimes and then crashes and goes to the device
> homepage.

and, after the first round of fixes, in the words it was reported in again:

> Again when I open the app it's automatically reopen and reopen and say, undal
> app is responding automatically, and after some times it crashes.

That is three separate facts, and each one points at a different layer:

1. **something threw an exception nobody caught** — the cause;
2. **the app reopened itself** — `UndalCrashGuard` doing exactly its job;
3. **then it went to the home screen** — the guard's restart budget running out.

The guard is the last line of defence. It is not the fix, and treating a
successful reopen as a recovery is how an app ends up looking haunted: it opens,
works for a few seconds, closes, opens again. Fixing this means making the throw
impossible, not making the restart prettier.

The first round of fixes found the throws that live *inside* this app's own
coroutines. The second round — the one this document now covers — found the two
that do not: a promise made to Android and then broken, and a `Task` read as
though it waited. Neither is a bug in Undal's logic, neither can be caught by a
handler Undal installs, and between them they produced the whole reported
symptom on their own.

## Why an uncaught exception is the whole app

`viewModelScope` is `SupervisorJob() + Dispatchers.Main.immediate`. A supervisor
job does not cancel its siblings when one fails, and it does not catch anything
either — so an exception that escapes a `viewModelScope.launch` block has no
handler on any parent. It reaches the thread's default uncaught-exception
handler, and on Android that ends the process.

There is no dialog, no log line the user can read, no state to recover. To the
person holding the phone the app simply stops existing.

This is why the failure is *intermittent* and *device-dependent*, which is how it
was reported. The throws are not in the business logic; they are in the I/O:

| The call | What it can throw | When |
| --- | --- | --- |
| DataStore `.first()` / `.collect {}` | `IOException`, a corrupt preferences file, a full disk | any cold start; the cart, the address draft and the onboarding progress all live there |
| a Firestore `combine(...)` transform | anything the mapping code touches — a `null` where a document was expected, a `NumberFormatException` on a price a tool wrote as a string | when the kitchen's data has one document in a shape the app did not expect |
| `FirebaseAuth` token reads | `FirebaseNetworkException` on the weakest connection the device will ever have | signing in on a *new* phone, which is precisely when the token and the first profile read happen for the first time |
| `WorkManager` / service scope work | anything, in a process nobody is looking at | a push arriving in a fresh process |

The last row is why a push service is in this document at all: `UndalFcmService`
runs in a process the push itself started, so a throw there kills an app that was
only doing its job in the background, and the kitchen's phone goes silent with no
screen to explain it.

## The rule

**Every coroutine this app starts carries a handler.** Two of them, in
`app/…/core/util/UndalGuards.kt`:

```kotlin
// A screen exists to put the sentence on.
private val guard = UndalGuards.viewModel("reading the menu") { message ->
    _state.value = _state.value.copy(errorMessage = message, busy = false)
}
viewModelScope.launch(guard) { … }

// No screen exists: log it, record it, and swallow it deliberately.
private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO + UndalGuards.service("handling a push")
)
```

`UndalGuards.viewModel` does three things and nothing else: logs the throwable
(a failure nobody recorded is a failure that happens again), translates it
through `UndalErrors` — the same translator the repositories use, so a customer
reads "you appear to be offline" rather than a class name — and hands the
sentence to the ViewModel's own state field, which the screen already renders.

Two details that matter:

* **`busy = false` is part of the recovery, not a courtesy.** A guard that
  leaves a spinner turning has replaced a crash with a frozen screen, which the
  person experiences as the same failure.
* **Every step inside the handler is wrapped.** A handler that can itself throw
  is worse than no handler, because it runs on the way to the process's death.

`UndalGuards.service` is named `service` and not `background` for a mundane
reason: `tools/check_import_contracts.py` polices every bare `background(` call
in the app as Compose's `Modifier.background()`, which is an extension that has
to be imported. A function of our own with that name trips a gate that is right
about everything else it checks, so the name moved.

## The second way: a promise made to Android and then broken

An uncaught exception is not the only route to a dead process. Android kills an
app itself when the app breaks a contract, and it does it from the main thread,
in a callback, long after the code that made the promise has returned — so no
`runCatching` at the call site can see it.

### A foreground service that stops without taking the foreground

`Context.startForegroundService()` creates an obligation: the service must call
`startForeground()` within about five seconds, **including when the intent it was
handed says "stop"**. Break it and Android raises
`ForegroundServiceDidNotStartInTimeException` (Android 12+) or the "isn't
responding" dialog (older releases).

Undal had this in three services, and the one that mattered was the kitchen
watcher, because of *who asked for the stop*:

```
open the app
  → UndalRootViewModel collects SessionState.SignedIn
  → registerDevice(uid, isVendorClaim)          // see the next section: this was false
  → KitchenWatchService.sync(context, false)
  → stop() → ContextCompat.startForegroundService(ACTION_STOP)
  → onStartCommand sees ACTION_STOP → stopWatching() → stopSelf()
  → startForeground() was never called
  → ~5 s later the platform kills the process from the main thread
  → UndalCrashGuard reopens the app, which does all of the above again
```

That is the reported loop exactly: it opened, it "wasn't responding", it
reopened, it reopened, and then the guard's budget ran out and the launcher came
back. And it happened to *customers*, because a customer login is what asks a
kitchen service to stop.

Two rules fix it structurally rather than carefully, in
`app/…/core/background/UndalForeground.kt`:

1. **A stop is never a foreground start.** `UndalForeground.stop` uses
   `Context.stopService`, which reaches `onDestroy` without ever creating the
   promise. The cleanup a stop intent used to do in `onStartCommand` now lives in
   `onDestroy`, so both routes behave identically.
2. **The first statement of `onStartCommand` takes the foreground**, before the
   intent's meaning is even read. Whatever the branch decides to do next — stop,
   ignore a bad payload, restart — the promise is already kept.

`tools/check_foreground_contract.py` enforces both in CI, so the shape cannot
come back by accident. Every foreground API call in the app now lives in that one
file, and a refusal there is a logged `false` rather than a dead process.

### A `Task` read as though `getResult()` waited

`Task.getResult()` does not block. It throws `IllegalStateException` unless the
task has *already* finished, and a token being fetched has not. Four sites read
`getIdToken(false).result?.claims?.get("vendor")`, every one of them inside a
`runCatching`, so every one of them silently answered **"this phone is not the
kitchen"** — always, on every device, on every start:

| Site | What the wrong `false` did |
| --- | --- |
| `AuthSession.toSessionState` | the router's first emission called a vendor a customer, which asked the kitchen's watcher to stop — through the crashing route above |
| `AlertWatchdog.isKitchenDevice` | the watchdog disarmed itself the first time it fired, so a swiped-away kitchen never came back |
| `BootReceiver` | a rebooted kitchen phone never restarted its order watcher, and rang only for pushes |
| `UndalFcmService` | a kitchen notice was handled as a customer's; only the remembered role saved it |

The correct reads are `await()` inside a coroutine, or the task's own callback.
`AuthSession` now keeps the claims in a `@Volatile` field filled from
`addOnSuccessListener` and publishes a second `SessionState` when they land; the
three background paths read `PushDeviceRole`, which is the role the router
established off the token the last time it ran and costs no network.

## What the guard still does, and why it stays

`UndalCrashGuard` (`app/…/core/util/UndalCrashGuard.kt`) is installed in
`UndalApp.onCreate`, before Crashlytics installs its own handler, so it can hand
over deliberately. It records the exception, reopens `MainActivity` in a clean
task with `EXTRA_RECOVERED` set, and the root shows one honest sentence:

> Undal hit a problem and reopened itself. Nothing in your basket or your orders
> was lost.

Three rules keep it from becoming the nuisance it would otherwise be:

1. **It may not loop — and a startup crash is its own kind of crash.** This is
   what the second report was about. A crash eight seconds into the process is a
   broken *opening*: reopening runs the opening again, to the same failure. So a
   startup crash gets **one** reopen and then stops. A crash two hours in is a
   screen, a payload or a listener, where reopening genuinely helps, and that
   keeps the ordinary budget of three in ten minutes.
2. **Running out of budget is not the home screen.** It is `SafeModeActivity`: a
   plain `Activity` with no Compose, no Hilt and no Firebase in it, which says
   what happened, shows the exception in words, offers a report the person can
   copy and send, and offers one tap to try again and one to call the kitchen.
   It cannot fail the way the app failed, because nothing in it is what broke.
3. **It only ever starts Undal's own activities**, with the launcher's own task
   flags, so the system does not end up with two windows; and it cannot break a
   release that never crashes — one handler replaced, every call inside it
   wrapped, and if the guard itself fails the platform's handler still runs.

The counting is in `app/…/core/util/CrashRecoveryPolicy.kt`, which is pure, and
`CrashRecoveryPolicyTest` pins it: one startup reopen then safe mode, three
ordinary reopens then safe mode, two safe modes then the platform, a window that
expires on its own, and a clock that went backwards (a timezone change on first
boot) read as a window that has not begun rather than one that is enormous.

`MainActivity` tells the guard when the app has been up for twenty seconds, which
clears the *startup* counters and leaves the ordinary budget and its window
alone — a phone that fails every forty seconds must not reset itself forever.

With the coroutine rule in place the guard should never fire. It stays because
"should never" is not a guarantee, and because a native crash, an
`OutOfMemoryError` on a low-end phone and a bug nobody has met yet all still end
the same way — and the difference between an app that vanishes and an app that
says what happened is the whole of the customer's trust.

## The opening frame

`Application.onCreate` runs on the main thread, and the main thread is what
Android measures "isn't responding" against. Undal used to do all of this there:
initialise Crashlytics and Performance, configure Cloudinary, open Firestore's
100 MB cache, open the Realtime Database and `keepSynced` the whole `chats` and
`orderLive` trees, create five notification channels, initialise WorkManager
(which opens a database on first touch), load osmdroid's tile cache and arm an
exact alarm — before the first screen was composed.

Two things genuinely have to run first, and both are *configuration* rather than
work: Firestore's cache policy must be set before the first query, and Realtime
Database persistence must be switched on before the first reference. Everything
else is dispatched onto an `IO` scope, and every step is its own `runCatching`
with its own name in the log, so one SDK that refuses to initialise costs that
SDK and nothing else.

`keepSynced(true)` on a whole tree is gone rather than moved. It asks the
database to download and hold *every message of every order the kitchen has ever
had*, at every cold start, growing forever — and it buys nothing, because
`keepSynced` only pre-warms a node for a listener that has not attached yet, and
both of those nodes are read by listeners that attach themselves when a
conversation or an order is opened.

## How to keep it true

* A new service takes its foreground through `UndalForeground`, its first
  statement of `onStartCommand` is `UndalForeground.enter(...)`, and it is
  stopped with `UndalForeground.stop` — never with a start intent.
* Nobody reads a `Task` synchronously. `await()` in a coroutine, the task's own
  callback, or a fact this phone already established.
* A new ViewModel starts with its `guard` property, next to `_state`. Every
  `viewModelScope.launch` in the file takes it: `launch(guard) {`.
* A new background scope takes `UndalGuards.service("what it was doing")` in its
  context, so launches added later inherit it.
* The operation string is written in the words the person would use — "reading
  the menu", "saving your address" — because it ends up in the sentence on screen
  and in the kitchen reference line.
* A refusal to save is never a silent one. Drafts expose `firstError`, not a
  hand-chained pair of errors: `CouponDraft` used to check
  `codeError ?: valueError` at the call site, so a wrong *window* or a wrong
  *date* refused with no sentence at all and the Save button simply did nothing.

## Related

* `docs/ALERT-RELIABILITY.md` — the alert ladder, where a missed ring is
  retried by a watchdog rather than trusted to one player object.
* `README.md` §6 — what to check when the kitchen phone stays silent.
* `tools/check_foreground_contract.py` — the CI gate that refuses both shapes.
