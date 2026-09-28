---
name: bill-rust-code-review-api-contracts
description: Use when reviewing Rust public traits and types, serde and wire formats, HTTP or RPC contracts, FFI ABI, and compatibility.
internal-for: skill-bill
---

# API Contracts Review Specialist

## Focus

- Public functions, traits, associated types, generics, lifetimes, errors, and semver surface
- Serde names, defaults, optionality, enum representations, unknown fields, and wire compatibility
- HTTP/RPC validation, status mapping, pagination, idempotency, and error payloads
- `extern` ABI, layout, ownership, unwinding, and cross-language compatibility

## Ignore

- Private refactors with no observable contract change
- Naming preference that does not reduce clarity or compatibility
- Demands to expose implementation types for caller convenience

## Applicability

Apply to library APIs, service boundaries, serialized data, messages, plugins, and FFI. Compare the diff with documented compatibility and MSRV policy.

## Project-Specific Rules

### Rust API Contract Rules

- Require new public bounds such as `T: Send + Sync + 'static` to reflect existing caller needs; reject a semver regression that excludes valid implementations or targets.
- Verify public `trait` changes preserve object safety and default behavior for downstream implementors; flag a required method or associated type that breaks compilation without a major-version contract.
- Ensure exposed lifetimes in types such as `Response<'a>` describe a stable owner and do not shorten previously usable borrows; reject caller-invalidating lifetime changes.
- Design a public enum with `#[non_exhaustive]` before its first stable release when downstream matching must remain extensible; reject adding the attribute or a variant to an already-published exhaustive enum without a major-version change or replacement-and-deprecation path because either change breaks exhaustive consumers.
- Verify `#[serde(rename)]`, `rename_all`, `default`, `skip_serializing_if`, and `deny_unknown_fields` match the wire policy; reject payload incompatibility between absent, null, empty, and default values.
- Ensure tagged enums using `#[serde(tag = "type")]` preserve tag names and unknown-variant behavior; flag serialization drift that loses data or rejects forward-compatible messages.
- Require HTTP or RPC errors to retain stable status, code, and machine-readable fields; reject mapping through `IntoResponse` or `Status` that turns validation failure into an indistinguishable server error.
- Verify mutation endpoints carry an idempotency key or conditional contract when clients may retry; reject replay that duplicates externally visible state.
- Ensure pagination cursors encoded by `base64::Engine` bind ordering and filters and fail closed on invalid data; reject skipped, repeated, or cross-tenant results.
- Require feature-gated public items under `#[cfg(feature = "...")]` to compile in documented combinations; flag default-feature leakage or missing symbols that break consumers.
- Verify FFI layouts use an intentional `#[repr(C)]`, fixed-width fields, explicit string encoding, and documented pointer nullability; reject ABI corruption or a null dereference from compiler-dependent layout, enum representation, or an unchecked pointer contract.
- Require every `extern "C"` boundary to define borrowed-buffer lifetimes and aliasing, pair ownership transfers with constructors and destructors, prevent unwinding, and constrain callbacks to their documented thread; reject dangling aliases, leaks, double frees, cross-thread UI access, or cross-language crashes.
- For Blocker or Major findings, describe the concrete compatibility or validation failure scenario.
