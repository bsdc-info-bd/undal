# Crash discipline: nothing in a ViewModel may end the process

The symptom this document exists for, in the words it was reported in:

> When I open the Android app and login or signup as a customer the app
> automatically reopens sometimes and then crashes and goes to the device
> homepage.

That is three separate facts, and each one points at a different layer:

1. **something threw an exception nobody caught** — the cause;
2. **the app reopened itself** — `UndalCrashGuard` doing exactly its job;
3. **then it went to the home screen** — the guard's restart budget running out.

The guard is the last line of defence. It is not the fix, and treating a
successful reopen as a recovery is how an app ends up looking haunted: it opens,
works for a few seconds, closes, opens again. Fixing this means making the throw
impossible, not making the restart prettier.

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

## What the guard still does, and why it stays

`UndalCrashGuard` (`app/…/core/util/UndalCrashGuard.kt`) is installed in
`UndalApp.onCreate`, before Crashlytics installs its own handler, so it can hand
over deliberately. It records the exception, reopens `MainActivity` in a clean
task with `EXTRA_RECOVERED` set, and the root shows one honest sentence:

> Undal hit a problem and reopened itself. Nothing in your basket or your orders
> was lost.

Three rules keep it from becoming the nuisance it would otherwise be:

1. **It may not loop.** Three restarts inside ten minutes is a crash loop, not a
   recovery; the fourth is handed to the platform's own handler and the app
   closes as Android intends. This budget is the reason the reported symptom ends
   at the home screen rather than cycling forever.
2. **It only ever restarts the app that crashed**, with the launcher's own task
   flags, so the system does not end up with two windows.
3. **It cannot break a release that never crashes.** One handler replaced, every
   call inside it wrapped.

With the coroutine rule in place the guard should never fire. It stays because
"should never" is not a guarantee, and because a native crash, an
`OutOfMemoryError` on a low-end phone and a bug nobody has met yet all still end
the same way — and the difference between an app that vanishes and an app that
says what happened is the whole of the customer's trust.

## How to keep it true

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
