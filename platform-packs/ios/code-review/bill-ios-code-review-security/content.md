---
name: bill-ios-code-review-security
description: Use when reviewing iOS secrets, privacy, entitlements, links, sharing, and sensitive output.
internal-for: skill-bill
---

# Security Review Specialist

Review only concrete authorization, privacy, or data-exposure risks.

## Focus

- Keychain accessibility and access groups
- Privacy manifests, entitlements, ATS exceptions, and usage descriptions
- Links, sharing, pasteboard, logs, and generated output

## Ignore

- Generic hardening advice without an applicable API or threat
- Platform capabilities absent from the changed targets

## Applicability

Gate every rule on detected APIs, capabilities, extensions, deployment targets, and data sensitivity. Repository policy may add stricter requirements but is not needed to apply these rules.

## Project-Specific Rules

### Secret And Capability Rules

- Keychain writes must choose a deliberate `kSecAttrAccessible` value matching background and device-lock needs; reject weaker accessibility that creates secret exposure.
- Keychain queries must constrain `kSecAttrAccessGroup` to the intended access group; reject broad or mismatched sharing that leaks data or breaks extension authorization.
- Authentication tokens must never persist in `UserDefaults` or plaintext files; require Keychain storage because backups or logs otherwise expose credentials.
- Entitlements such as `com.apple.security.application-groups` must use the least shared scope and match provisioning; reject drift that causes authorization or build-signing failure.
- `NSAppTransportSecurity` exceptions must be host-scoped and justified; reject arbitrary loads because plaintext transport creates data exposure.
- Sensitive local files must select appropriate `FileProtectionType` and lifecycle access; reject unprotected data that remains readable while the device is locked.

### Privacy And Boundary Rules

- Required-reason API use must be declared in `PrivacyInfo.xcprivacy`, and merged manifests must remain valid; reject omissions that create App Store validation failure.
- Camera, location, contacts, and similar access must have accurate `Info.plist` usage descriptions before invocation; reject missing purpose strings that crash or mislead users.
- Universal links must validate `NSUserActivity.webpageURL` host and path before routing; reject untrusted parameters that bypass authorization state.
- Custom URL schemes must validate source, route, and payload rather than trusting `application(_:open:options:)`; reject malformed deep links that corrupt navigation state.
- `UIActivityViewController` exports must minimize payload and exclude secrets; reject sharing flows that expose private files through unintended extensions.
- Sensitive `UIPasteboard` content must use local-only or expiring options and must carry an expiration date; reject indefinite pasteboard lifetime that leaks data across apps.
- `Logger` fields must use `.private` for sensitive values, and generated diagnostics must redact tokens; reject a public privacy specifier that exposes credentials in operational output.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
