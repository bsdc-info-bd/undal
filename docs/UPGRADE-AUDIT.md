# Upgrade audit — 26 October 2026

## Shipped in this change

- Web map keyboard movement now clears confirmation, address metadata and the saved-pin label.
- Checkout also clears its separate confirmation flag whenever coordinates change.
- Reverse-geocode responses are tied to a coordinate revision, preventing stale addresses from labelling a newer pin.
- Dragging tracks one pointer, ignores secondary/right-button input, and recovers from lost capture.
- Keyboard events on map controls no longer bubble into map navigation or confirmation.
- Programmatic coordinates are validated. Map teardown cancels scheduled work, removes the resize listener and ignores outstanding geocodes.
- SVG marker baseline spacing is removed so its tip aligns with the chosen coordinate.
- New precision arrows move the web pin approximately six metres; coordinates remain visible when tiles are unavailable.
- Android picker arrow controls wrap for narrow screens and larger text. Light-theme orange actions use dark text for better contrast.
- Shared website theme: warm editorial hero, refined surfaces, card motion, numeric alignment, responsive chips, clearer focus states and forced-colour borders.
- Override the vulnerable transitive development-tool dependency `undici` to patched 7.x. Keep this override until the upstream toolchain lifts its dependency.

## Verification

- 184 Worker tests pass, including eight new map interaction regression tests.
- TypeScript no-emit check passes.
- Wrangler deployment dry-run bundles successfully (no deployment performed).
- npm audit reports zero vulnerabilities with the updated lockfile.
- Browser compatibility, shared web/app rules, coupon parity, alert ladder, Kotlin import/shape and foreground-service contract checks pass.
- Local preview runs the real Worker against the repository's sample Firebase backend, not production data.

## Still requires production/device verification

This is not a certification that every production error has been eliminated.

- Java and the Android SDK are not installed in this workspace; Android compilation, lint, instrumentation and real-device map/touch tests were not run.
- Real OpenStreetMap/Nominatim requests cannot be verified under this workspace's outbound-network restrictions. Confirm tiles, GPS permissions and address lookups on a phone.
- No production Firebase, Cloudinary, FCM or Cloudflare resources were changed or exercised.
- Wrangler configuration contains deployment placeholders; use the project's secret-injection/preflight workflow before deploying.
- Test real kitchen alerts with the phone locked, battery restrictions enabled, and an interrupted network before release.
- This theme refinement has not been screenshot-tested across browsers; check narrow screens, large text and checkout on actual phones.

## Vendor alert resilience follow-up

- Enforced HIGH priority, data-only and non-collapsible order messages at the final FCM serializer, not just at its callers.
- Protected reserved message fields from extra-metadata overrides, including the self-stopping test marker.
- Topic sending begins independently of vendor/token lookup. Each FCM fetch has an eight-second header deadline and up to three attempts for transport/5xx failures. Quota failures wait for the normal server sweep instead of hot-looping. Credential minting remains a separate prerequisite.
- Generic NOT_FOUND no longer purges device registrations: only an explicit UNREGISTERED or invalid-token response does.
- Customer order-created relay skips orders already answered. Full-route send failures are surfaced in sweep diagnostics.
- Retry sweeps rotate ascending document-name pages (50 records per sweep; five-minute KV cursor lifetime), preventing an abandoned first page from permanently hiding later orders. This is bounded recovery, not a guarantee of per-minute retries for every order in a very large backlog. KV is eventually consistent; duplicate pages/pushes remain safe through the device's idempotent queue.
- Android now keeps a bounded 24-event local delivery journal without customer data or tokens. Readiness shows the latest events and can copy the journal for support. It distinguishes receipt priority, service startup, playback request/state, queue updates and background-start fallback.
- Empty/failed readiness checks are no longer shown as armed. Test-send success is labelled as FCM acceptance, not device delivery or proof of audible sound. Platform delivery limits are explained in the UI.
- MediaPlayer status reads in the sound watchdog are guarded so a released/error-state player cannot crash the recovery loop.
- Added twelve Worker regression tests; the full suite now passes 196 tests. TypeScript, Worker dry-run, Kotlin static gates pass. Android compilation and real-device tests remain outstanding.

### Real-phone release acceptance checklist

1. Install a signed build configured for the correct Firebase project; sign in as the vendor.
2. Grant notifications, high-importance alarm channel, full-screen permission where eligible, battery exemption and manufacturer autostart. Complete readiness checks.
3. Run Ring my phone. Confirm timeline entries for FCM data receipt and foreground alarm service, then personally listen for the loop. A green server response alone is insufficient.
4. Lock the phone and place an actual customer order. Do not open the app; verify looping sound and order actions.
5. Swipe the app from Recents, place another order and repeat. Test normal process death separately from Force stop.
6. Place two orders; answer only one. The other must continue ringing. Answer the last on another kitchen device and verify this phone stops.
7. Disconnect/reconnect the network during the retry window and verify eventual recovery. A powered-off phone cannot sound an alert.
8. Force stop in Android Settings: delivery is expected to be blocked. Reopen Undal, re-run readiness, and test again.
9. Repeat on the actual vendor's manufacturer/Android version, in Doze, battery saver and after reboot. Verify the store auto-offline safety path if the device cannot stay reachable.


## Android Google authentication

- Credential Manager explicit Google account chooser on login and registration.
- Google ID token exchanged with Firebase Auth; the existing role router remains authoritative.
- Complete profiles are not overwritten. New/incomplete accounts confirm name, phone and delivery pin before a profile is written.
- Clears local onboarding drafts before Firebase publishes the new session and attempts Credential Manager cleanup on sign-out.
- Duplicate-submit guards, quiet chooser cancellation and actionable OAuth configuration errors.
- Added five native JUnit error/cancellation tests (not executed here). Kotlin static checks pass; the existing 196 Worker regression tests and TypeScript checks pass. Native compilation and real Google sign-in still require a configured Android build/device.
- Android-only scope; browser login is unchanged. See `docs/GOOGLE-SIGN-IN.md` for setup and acceptance checks.
