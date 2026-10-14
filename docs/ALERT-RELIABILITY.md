# Kitchen alerts: deployment and device acceptance

## What the system does

The Worker sends high-priority **data-only** FCM messages to registered kitchen
phones, with a topic fallback. New-order messages are non-collapsible so a burst
of orders cannot evict other orders through FCM's four-collapse-key limit.
One failed token transport no longer aborts the remaining fan-out. FCM acceptance
is not a receipt from a device and does not prove that sound played.

Android starts its existing foreground media service immediately from the push
callback. It loops the bundled tone, publishes an actionable notification, and
watches order status. A second order is queued, not substituted for the first.
Duplicate delivery now preserves each queued order's identity. Status callbacks
capture their own order ID so a late callback cannot silence the next order.

The queue is saved locally after foreground promotion and restored on a sticky
service restart, for the same signed-in user only and within 15 minutes of the
last save. Diagnostic tests are never restored. The live status listener removes
answered orders after reconnecting; a restored order can briefly ring before
that reconciliation. Queue restoration is not a guarantee that Android restarts
the service. Existing foreground watching and server re-alert sweeps are retained.

When Android rejects a background foreground-service start, a normal high-priority
order notification is attempted instead. This fallback is not a looping alarm.
The service retains the existing audio-volume behaviour; kitchen owners should
understand that it raises alarm volume while ringing and restores it afterward.

## Platform limits — do not promise guaranteed or instant delivery

- Swiping away a task and **Force stop** are different. FCM can wake a normally
  terminated process; after Force stop Android generally blocks delivery until
  the owner launches the app again.
- Offline/powered-off phones cannot receive immediate alerts. OEM battery
  restrictions, Doze, FCM priority downgrading, DND and disabled notification
  channels affect delivery or visibility.
- Full-screen notifications require OS permission/eligibility; Android and Play
  restrict their use. Do not assume an order app automatically qualifies.
- This repository contains an Android app, not an iOS app. No iOS background
  capability was added.
- The web remains a customer storefront. Browsers cannot reliably play looping
  audio with a closed page or locked device. Use the Android kitchen app for
  kitchen alerts; no web-push delivery guarantee is provided.

## Release prerequisites

1. Configure the existing deployment workflow's Firebase service account, RTDB
   URL, public web API key, Cloudflare account and KV binding. The checked-in
   `worker/wrangler.toml` contains deployment placeholders, not working credentials.
2. Verify Firebase project/package configuration and deploy Firestore/RTDB rules.
   Provision the kitchen account and confirm its vendor claim and device tokens.
3. Install the signed Android build, log into the kitchen account, allow
   notifications, inspect the order channel, and run the existing readiness test.
   Review battery and full-screen settings with the owner where supported.
4. Verify the every-minute re-alert cron is deployed. Check real orders, not just
   the diagnostics route. Monitor missing-token and failed-FCM outcomes.
5. Android foreground-service declarations and full-screen intent use require
   policy review before distributing through Google Play.

## Device acceptance matrix (must be run on real hardware)

Use Android 12, 13, 14, 15 and the target kitchen phone/OEM. For each test, record
order creation time, notification arrival, first audible sound and time to silence.

| Scenario | Verify |
| --- | --- |
| Foreground / home screen / locked screen | Loop starts, order link works, acceptance stops sound |
| Task swiped away / process killed normally | FCM wake-up and service promotion; record actual latency |
| Kill process while ringing | Same-user queue restores when OS restarts service; answered order reconciles |
| Two orders plus duplicate pushes | Both IDs remain distinct; answering one leaves the other ringing |
| Answer on another kitchen phone | Correct order stops; stale callbacks never stop another order |
| Brief network loss then reconnect | Re-alert arrives within its window; answered orders reconcile |
| Notification permission revoked / channel muted | Readiness reports limitation; no application crash |
| Background service start denied | Actionable notification fallback, not a promise of looping audio |
| Sign out then sign into another account | Old queue is not restored |
| Diagnostic alert | Self-stops; does not return after process restart |
| Force stop / reboot / OEM battery saver | Record limitations and owner recovery steps |

## Checks performed in this change

Worker type checking, 14 Node/TS tests (including rendered-page tests), dry-run
Worker packaging, 31 alert-ladder wiring checks, 130 web/app rule comparisons,
and Kotlin source-shape checks. These do not replace an APK build or device tests.
Java/Android SDK and production Firebase connectivity were unavailable in the
editing environment. No production deployment was performed.

Web layout: test at 320, 375, 768, 1024 and 1440 CSS pixels, with keyboard focus,
200% zoom and reduced motion. Exercise search, filters, basket persistence,
location permission refusal, guest checkout and tracking against staging data.

## Vendor history cleanup

Store settings → **Clear finished history** (confirmation required) calls
`POST /admin/cleanup` on the Worker with a vendor token. Each request deletes at
most 5 delivered/cancelled orders with their chat metadata and live mirrors, and
up to 25 of the kitchen account's own notifications created before the request.
The app repeats bounded batches (max 100) and can be run again safely.
Active orders (placed, accepted, ready, in delivery) are never deleted.

Cleanup uses a Firestore composite index on `orders(status, updatedAt)`, which is
deployed by the manual **seed-config** workflow (`firestore:indexes`). Run that
workflow before using cleanup. Cleanup permanently removes orders from customer
history and sales reports — export records first.
