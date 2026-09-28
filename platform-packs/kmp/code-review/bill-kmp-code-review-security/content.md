---
name: bill-kmp-code-review-security
description: Use when reviewing exported components and deeplinks, PendingIntent mutability, WebView bridges, cleartext traffic and pinning, Keystore and encrypted storage, backup and provider export, and PII in logs and clipboard on Android and KMP.
internal-for: skill-bill
---

# KMP Security Review Specialist

Review only the on-device attack surface an installed Android or Kotlin Multiplatform application exposes: what another app on the device can reach, what an attacker with the device can extract, and what untrusted remote content can drive.

## Focus

- Component, deeplink, and `PendingIntent` exposure to other applications on the device
- WebView bridge, remote-content, and local-file reachability
- Transport trust: cleartext permission, pinning, and custom trust managers
- Key material, credential storage, backup, and debug-only surfaces reaching a release build
- Sensitive values leaving the process through logs, the clipboard, screenshots, or `Parcelable` extras

## Ignore

- Deployment and host hardening outside this Gradle build: image, proxy, and network-appliance configuration
- Compose rendering and navigation state correctness owned by the ui specialist
- Room and `DataStore` write atomicity and migration mechanics owned by the persistence specialist

## Applicability

Use this specialist for every source set this pack builds — `androidMain`, `commonMain`, and any `jvmMain` or server source set living in the same repository. Use it when a diff edits `AndroidManifest.xml`, adds or changes an `Activity`, `Service`, `BroadcastReceiver`, or `ContentProvider`, constructs an `Intent` or `PendingIntent`, configures a `WebView`, changes network or certificate configuration, touches `Keystore`, `EncryptedSharedPreferences`, or credential persistence, handles a deeplink or an incoming `Uri`, or logs, copies, or serializes user data. Evaluate every rule against a hostile application installed alongside this one, against a rooted or physically held device, and — for shared or JVM source sets — against an unauthenticated remote caller.

## Project-Specific Rules

### Component And Intent Exposure Rules

- Require every manifest component that declares an `intent-filter` to either set `android:exported="false"` or justify the exposure with a `signature`-level permission or an explicit caller check; an implicitly exported component lets any installed application invoke a privileged screen or action directly, bypassing the in-app entry path that enforces authentication.
- Reject forwarding an `Intent` or `Uri` extra received from another application into `startActivity`, `startService`, or `bindService` without validating its component, package, and scheme against an allowlist; an unvalidated redirect turns this application into a confused deputy that launches an attacker-chosen internal component with this application's identity and granted URI permissions.
- Require every `PendingIntent` to declare `FLAG_IMMUTABLE` unless the diff states why the receiver must mutate it, and require a mutable one to name an explicit component; a mutable `PendingIntent` with an unfilled base `Intent` lets the holder rewrite the target and have this application deliver an arbitrary action under its own permissions.
- Verify that `ContentProvider` declarations set `android:exported="false"` or enforce read and write permissions plus a `grantUriPermissions` path allowlist; an exported provider over the application's private directory hands another application a readable path into stored user data.
- Reject `android:allowBackup="true"` and an unrestricted `fullBackupContent` or `dataExtractionRules` when the application stores credentials, tokens, or user records locally; device backup extraction copies that store off the device, where it is readable without any application permission.

### Deeplink And Untrusted Input Rules

- Require every deeplink and App Link parameter to be validated and authorized before it selects a destination or mutates state, and reject treating the presence of a link as proof of the user's intent; a crafted link opened from a browser or another application otherwise performs an authenticated action the user never chose.
- Require `Uri` host, scheme, and path matching to use structured `Uri` accessors against a fixed allowlist rather than `startsWith`, `contains`, or a regular expression over the raw string; prefix and substring matching accepts an attacker-controlled host that merely embeds the expected one and routes trusted handling to a hostile origin.
- Reject decoding `Parcelable`, `Serializable`, or JSON payloads received from another application, a notification, or a deeplink directly into a domain type without range, length, and invariant validation; a syntactically valid hostile value reaches persistence and corrupts local state or exhausts device storage.
- Require any file received as a `content://` or `file://` URI to be read through `ContentResolver` with its size and type checked, and reject resolving an attacker-supplied path against the application's private directory; a traversal segment or a symlink otherwise reads or overwrites internal application files.

### Shared And JVM Source-Set Rules

- Require every request-handling entry point declared in a shared or JVM source set to authorize the caller against the specific object it reads or mutates, not merely to prove authentication, and reject deriving the tenant or owner from a request-supplied identifier; an authenticated caller otherwise reads or edits another account's records by changing one path or body value.
- Reject building a database statement, a filter expression, or a shell invocation by concatenating or interpolating request-derived values, and require bound parameters or an equivalent structured builder; a value carrying a quote or a statement separator otherwise executes as code with the process's own privileges.
- Require every bearer token or session assertion accepted by a shared or JVM entry point to have its signature, issuer, audience, and expiry verified against a trusted key before any claim inside it is read; decoding a token without verifying it accepts an attacker-minted claim set as identity.
- Reject sending a request-supplied URL, host, or file path to an outbound client or a file read without validating it against a fixed allowlist; an unvalidated destination reaches internal services, cloud metadata endpoints, or files outside the intended root.

### WebView And Remote Content Rules

- Reject enabling `javaScriptEnabled` together with `addJavascriptInterface` on a `WebView` whose loaded origin is not a fixed application-controlled allowlist; a compromised or redirected page then calls the exposed Kotlin methods with attacker-chosen arguments from inside the application process.
- Require `setAllowFileAccess`, `setAllowFileAccessFromFileURLs`, `setAllowUniversalAccessFromFileURLs`, and `setAllowContentAccess` to stay disabled unless the diff names the local asset contract they serve; leaving them enabled lets remote page content read the application's private files and exfiltrate them.
- Require a `WebViewClient` to constrain navigation and to surface rather than swallow `onReceivedSslError`; calling `proceed()` on a certificate error accepts an interception proxy and exposes every credential and payload the page carries.
- Verify that a `WebView` handling authenticated content clears its cookies, cache, and form data on sign-out, and that its `WebSettings` do not persist credentials; retained session material lets the next device user resume the previous session.

### Transport And Key Material Rules

- Reject `cleartextTrafficPermitted="true"` and a missing `usesCleartextTraffic="false"` for any host carrying credentials, tokens, or user data; an attacker on the same network reads and rewrites those requests without any device access.
- Reject a `TrustManager` or `HostnameVerifier` implementation that accepts all certificates or all hostnames, including one gated on a build flag that a release variant can still reach; a permanently disabled verification path converts every request into an interceptable channel.
- Require key material to be generated and held in the Android `Keystore` with the intended `setUserAuthenticationRequired` and hardware-backing expectations, and reject deriving a key from a hardcoded constant, an application resource, or a device identifier; an extractable key makes the encrypted store readable by anyone who copies it.
- Require tokens, refresh credentials, and personal records to go through `EncryptedSharedPreferences`, `DataStore` over an encrypted file, or an equivalent authenticated store rather than plain preferences, a file in the private directory, or a local database column; on a rooted or backed-up device the plain store is directly readable.
- Reject secrets, API keys, and signing material committed into source, `build.gradle.kts`, `BuildConfig`, string resources, or `local.properties` that is packaged; every one of these ships inside the APK and is recoverable by decompiling it.

### Data Egress And Release-Surface Rules

- Reject tokens, authorization headers, personal data, and full request or response payloads reaching `Log`, `println`, or a crash or analytics reporter; device logs and third-party crash payloads leave the application's trust boundary and are readable outside it.
- Require values copied to `ClipboardManager` to set the sensitive-content flag and to avoid credentials entirely, and require screens showing credentials or payment data to set `FLAG_SECURE`; the clipboard is readable by other applications and unflagged screens land in the recents thumbnail and in screenshots.
- Verify that debug-only affordances — developer menus, network logging interceptors, mock authentication paths, and exported debug receivers — are excluded from the release variant by source-set placement rather than a runtime flag; a runtime-flag guard leaves the code and its entry point in the shipped binary.
- Require a dependency added or bumped in `libs.versions.toml` or a Gradle build script to be checked against the repository's advisory source, and reject dropping or loosening a `gradle/verification-metadata.xml` entry; transitive code ships inside the APK or the published KMP artifact and runs with the application's own permissions.
- Require permissions requested in the manifest to be justified by a use in the diff and reduced to the narrowest available variant; an unused or over-broad permission expands what a compromise of this application can reach on the device.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
