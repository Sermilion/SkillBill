---
name: bill-kotlin-code-review-security
description: Review Kotlin input, authorization, injection, secrets, authentication, deserialization, and dependency security failures.
internal-for: skill-bill
---

# Security Review Specialist

## Focus

- Attacker-controlled data, identity boundaries, dangerous sinks, credentials, verification, and dependencies

## Ignore

- Hypothetical concerns without a reachable attacker-controlled path or exposure

## Applicability

Use for Kotlin code processing untrusted payloads, identities, URLs, files, commands, templates, or serialized objects.

## Project-Specific Rules

### Security Review Rules

- Require `kotlinx.serialization` DTO validation before domain use; syntactically valid hostile values can break state invariants or exhaust resources.
- Require kotlinx.serialization polymorphism in `SerializersModule` to use an explicit registered subtype set plus authorization and validation for any attacker-controlled discriminator; it does not instantiate arbitrary JVM classes, but an allowed subtype can still create an authorization bypass or data exposure.
- Reject attacker-controlled data reaching JVM `ObjectInputStream`, unsafe YAML constructors, Jackson default or polymorphic typing with an unrestricted subtype resolver, or equivalent gadget-capable deserialization; type allowlists alone are insufficient when reachable gadget classes can execute behavior or expose data.
- Verify object authorization at the service boundary using the trusted `Principal`; route-only checks risk direct-call authorization bypass.
- Require tenant predicates to derive from `SecurityContext`, never request fields; caller-controlled ownership risks cross-tenant data exposure.
- Reject concatenated SQL in `exec` or JDBC statements; untrusted fragments create injection and data-corruption risk.
- Require escaped output in `kotlinx.html`, Thymeleaf, or FreeMarker templates; raw interpolation can cause script injection and session exposure.
- Verify file access with `toRealPath` or equivalent canonical resolution under the canonical allowed root, with an explicit symlink policy and safe handling for not-yet-created targets; lexical `Path.normalize` containment alone risks traversal and data exposure through a symlink.
- Reject unvalidated destinations passed to `HttpClient`; permissive URL handling creates SSRF exposure to metadata or internal services.
- Require executable and argument values passed to `ProcessBuilder` to satisfy the invoked program's contract; direct argument-list execution does not perform shell expansion, while `sh -c`, `cmd /c`, or another interpreter makes composed input an unsafe command-injection boundary.
- Reject secrets, `Authorization` headers, cookies, and private payloads in `logger` calls; production logs can become credential exposure.
- Verify JWT signature against trusted keys, constrain accepted algorithms, and validate issuer, audience, expiration according to policy, `nbf` when present, and key rotation through the detected library or framework; a `decode`-only or non-expiring acceptance path creates authentication failure and bypass risk.
- Require both applicable Gradle dependency integrity or provenance verification and the repository's discovered advisory or vulnerability check for exposed libraries; verification cannot identify a trusted but vulnerable version, and stale `libs.versions.toml` entries can retain exploitable runtime risk.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
