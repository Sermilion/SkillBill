---
name: bill-kmp-code-review-platform-correctness
description: Use when reviewing Kotlin Multiplatform source-set, expect/actual, target-runtime, serialization, dispatcher, ObjC export, Skie bridge, or native cancellation correctness.
internal-for: skill-bill
---

# KMP Platform Correctness Review Specialist

Review only multiplatform contract and runtime failures that can diverge by target.

## Focus

- Source-set dependency boundaries, `expect`/`actual` parity, target-specific runtime behavior, and Kotlin/Native export boundaries

## Ignore

- Shared Kotlin correctness already owned by the manifest-declared Kotlin baseline
- Compose rendering, accessibility semantics, and visual design concerns

## Applicability

Use this specialist when a diff touches `commonMain`, target source sets, `expect`/`actual`, native exports, or behavior that must remain equivalent across KMP targets.

## Project-Specific Rules

### Source-Set Contract Failure Checks

- Require every `actual` declaration to preserve its `expect` declaration's signature, nullability, visibility, exceptions, and behavioral contract; reject target implementations whose drift creates a target-only failure.
- Reject JVM- or Android-only artifacts in `commonMain` dependencies; verify each dependency is available to every declared target or is isolated in the correct target source set, otherwise the unsupported target has a compile or link failure.
- Verify source-set `dependsOn` edges form the intended hierarchy and do not leak a target capability into an intermediate shared set; reject an invalid hierarchy that changes declaration visibility by target.
- Require explicit adapters around filesystem, networking, cryptography, threading, and platform services used from `commonMain`; reject an unsupported capability whose target implementation causes a runtime failure.

### Serialization and Time Correctness Rules

- Require `kotlinx.serialization` polymorphic registrations and discriminator behavior to match on every target; reject missing target registration that turns a valid common payload into a decode error.
- Verify `kotlinx-datetime` timezone, daylight-saving, and locale-boundary behavior per target; reject an incorrect instant or local date produced by inheriting JVM or Darwin defaults.
- Require `Json` serialized names, defaults, numeric ranges, and unknown-field behavior to remain wire-compatible across target encoders and versions; reject target-only codecs that corrupt the common data contract.
- Verify locale-sensitive `lowercase`, collation, number formatting, calendar selection, and clock precision against the product invariant; reject an incorrect identifier, ordering, or displayed value inherited from a host default.

### Dispatcher and Native-Boundary Failure Checks

- Verify `Dispatchers.Main` is installed and valid on iOS before selecting it; require dispatcher ownership that preserves native thread and lifecycle constraints.
- Require structured `CoroutineScope` ownership and cancellation at every target lifecycle boundary; reject detached scopes or callbacks that resume a continuation more than once because they leak work or cause an invalid-state failure.
- Require `@ObjCName` exports to preserve intended names, nullability, generics, and callable shape; reject export drift that breaks the Swift compile contract.
- Verify Skie `Flow` bridging preserves collection lifetime, completion, failure, and cancellation semantics rather than exposing a bridge with a target-only ordering failure.
- Require `suspend` cancellation crossing the ObjC boundary to propagate in both directions; reject wrappers that swallow cancellation or continue target work after the Swift caller cancels because they leak resources and violate lifecycle ordering.

### Resource, Build, and Distribution Failure Checks

- Require Compose resources to use `Res` from the owning source set and be generated for every consumer target; reject case-sensitive collisions or packages that omit assets and cause a missing-resource failure.
- Verify the `libs.versions.toml` Kotlin, Compose, Gradle, Android, serialization, and Native compiler coordinates are compatible with every declared target; reject a toolchain combination that breaks generated-source compilation.
- Require every configured target to compile and link through its discovered `compileKotlin*` task; distinguish an unavailable host toolchain from a source failure and never erase a target to make the build matrix pass.
- Verify frameworks, XCFrameworks, desktop distributions, browser bundles, Maven publications, and Android variants expose the intended API and resources with stable coordinates and metadata.
- For Android release variants, verify consumer rules, R8 keep behavior, serialization metadata, and reflective entry points survive shrinking; keep generic shrinker mechanics in the conditional `android-r8` add-on.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
