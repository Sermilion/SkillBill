---
name: bill-ios-code-review-code-quality
description: Swift value modelling, optional handling, enums, focused extensions, SwiftUI state, and task ownership.
internal-for: skill-bill
---

## Focus

Review changed Swift code for avoidable maintenance work. Follow the project's Swift version, module layout, and UIKit or SwiftUI conventions. Report a concrete location, the maintenance cost, and a behavior-preserving change; no reachable failure is required.

## Ignore

- Behavioral defects owned by failure specialists; route correctness, security, persistence, reliability, and accessibility findings to those lanes.
- Issues already enforced by the repository's configured formatter, linter, compiler, or static-analysis rules.
- Style preferences without a concrete maintenance cost or a behavior-preserving rewrite.

## Applicability

Use this lane for every routed iOS pack review, regardless of whether changed code has a reachable failure. Inspect changed code only and follow repository conventions before applying general language preferences. Zero findings is valid when the code is clear.

## Project-Specific Rules

### Idioms and helper clarity rules

- Require immutable domain values to use a Swift `struct` and closed alternatives to use an `enum`; parallel booleans allow invalid combinations and add state tracing. Preserve every valid transition when replacing flags.
- Require `Optional` values to be unwrapped with `guard let` where absence becomes meaningful; repeated force unwraps hide nil behavior and make fallback maintenance harder. Keep the same nil outcome.
- Require `extension` blocks to group one domain responsibility; scattering one type across unrelated files obscures ownership and access. Move behavior only when access control and public API remain unchanged.
- Require conditional `SwiftUI` or `UIKit` advice to follow the detected project framework; mixing view-state patterns adds needless indirection and obscures update ownership. Keep framework-specific state behavior intact.

### Cohesive state and duplication rules

- Require SwiftUI state to live with its owning view or model, using `@State` for owned values and `@Binding` for borrowed values; duplicated copies make rendered state ambiguous. Preserve the same source of truth.
- Require every `Task` to have a visible owner and cancellation path; hidden task creation obscures resource lifetime and makes maintainers trace cancellation and result delivery across the view/model boundary. Extract only when cancellation and result handling stay with the caller.
- Require `compactMap` only when dropping nil results is the intended contract; replacing a loop can hide absent values and change collection contents. Preserve order and all retained values.
- Require repeated model-to-wire mapping to share a transformation only when callers already duplicate it; divergent `Codable` fields cause serialized-value drift. Keep encoded keys and nil handling unchanged.

### Dead code, responsibility, and naming rules

- Require equivalent branches to collapse only when `throws`, `nil`, and user-facing errors stay distinct; duplicated branch tracing costs maintenance. Retain each existing outcome.
- Require dead conformances and private helpers to be removed only after checking module callers; unused declarations misstate ownership and increase review effort. Preserve public `protocol` behavior.
- Require methods mixing `Decodable` operations, state mutation, and view effects to split along named responsibilities; mixed control flow is harder to trace. Keep validation and effect ordering unchanged.
- Require names to describe domain values rather than UI position or `data`; vague names obscure `@State` updates and add tracing work. Preserve encoded values and API labels.

- For every finding, identify the changed-code location, the concrete maintenance cost, and a behavior-preserving rewrite. Preserve validation, errors, resource closing, and serialized values.
- Report every code-quality finding as Minor; never Blocker or Major.
