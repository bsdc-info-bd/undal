# Google sign-in — Android

Google sign-in is available from both **Sign in** and **Create your account**.
It uses Android Credential Manager's explicit Sign in with Google flow, then
Firebase Auth's `GoogleAuthProvider` credential exchange. No client secret or
Google access token is embedded in the app. Email/password sign-in remains.

## Firebase setup (required even after enabling the provider)

1. Firebase Console → Authentication → Sign-in method → Google: enable it and
   choose the support email. Complete Google's consent-screen requirements for
   the project; if the OAuth app remains in testing, allow the intended testers.
2. Project settings → Your apps → Android `com.undal.syl.app`: add the **SHA-1
   and SHA-256 signing-certificate fingerprints** for the actual release signing
   key. For Play distribution also add the Play App Signing certificate, not
   just the upload certificate. Do not replace or regenerate the existing key.
3. Download the updated `google-services.json` **after** enabling Google and
   registering the fingerprints. Update the repository's existing
   `GOOGLE_SERVICES_JSON` Actions secret with the complete JSON. Do not commit
   this file. The existing workflow writes it into `app/google-services.json`.
4. Rebuild/install using the existing build workflow. The Google Services plugin
   generates `default_web_client_id` from the JSON's **Web OAuth client**
   (`client_type: 3`). The Android client ID and Firebase API key cannot replace
   this value. A build without the value reports a configuration error rather
   than attempting a broken sign-in.
5. Debug builds use `com.undal.syl.app.debug`: register that separate Android
   package and its debug-key fingerprints in the same Firebase project, and use
   a compatible JSON for debug builds. A release-only JSON cannot configure a
   different debug application ID.
6. The phone/emulator needs supported Google Play services, a Google account and
   internet access. Use a Google Play system image when testing an emulator.

## Account behaviour

- Firebase verifies the Google ID token and owns identity/account matching.
- Existing accounts go through the normal server-backed role router. Google
  sign-in never writes or grants a vendor claim. Signing in with an arbitrary
  Google account does **not** make it the kitchen account.
- New/incomplete customer accounts must confirm their full name, Bangladeshi
  mobile number and delivery address. Google does not supply a delivery phone
  or a trustworthy doorstep pin. The name is editable, including when Google
  supplies no usable display name.
- The client does not create a partial `users/{uid}` document. The existing
  profile transaction writes the profile only after valid onboarding.
- Choosing Google clears local onboarding drafts before Firebase publishes a
  signed-in session, preventing another account's draft pin from appearing.
  Consequently an unsuccessful Google sign-in can require re-entering an
  unfinished local address; server-saved profiles are never removed.
- If Firebase reports an account/provider conflict, use the original sign-in
  method. This change does not silently link accounts or merge customer data.
- Dismissing the chooser leaves the form usable without an error banner.
- Sign-out clears Firebase auth and attempts to clear Credential Manager state.

## Verification

Added five JUnit tests covering cancellation and safe error messages. Native
compilation and JUnit execution still require Java 17, Android SDK and the
project's Firebase build configuration, unavailable in this workspace. Static
Kotlin import/shape checks were run; they are **not** a substitute for compiling.

Before release, verify on a signed build:

- New Google account → name/phone/address setup → saved customer profile.
- Existing complete account → customer home without repeated setup.
- Sign-in from the registration screen → same role routing as the login screen.
- Chooser cancellation, offline failure and retry; no duplicate submissions.
- Sign out and select a different Google account: no previous draft address.
- Missing client ID / incorrect signing fingerprint: actionable failure, no crash.
- Existing vendor claims remain server-controlled; email/password still works.

## Website scope

This implementation is for the **Android app**. The Worker's browser login is
unchanged. Browser Google OAuth needs a separate origin/redirect configuration
and a browser authentication flow; enabling the Firebase provider alone does not
wire it into the server-rendered storefront.
