---
name: bill-ios-code-review-architecture
description: Use when reviewing iOS ownership, composition, module, and package-boundary risks.
internal-for: skill-bill
---

# Architecture Review Specialist

Review only architectural issues with a concrete failure mode.

## Focus

- Dependency lifetime and composition ownership
- Swift and SPM module boundaries
- State, effect, and framework ownership

## Ignore

- Pattern or naming preferences without reachable risk
- Demands for one architecture where the repository has a coherent alternative

## Applicability

Infer the architecture in the changed area, its language mode, frameworks, and deployment target. Apply framework-specific rules only when that framework is detected. Repository-local knowledge may strengthen a finding but is not hidden required content.

## Project-Specific Rules

### Ownership And Boundary Rules

- Dependencies must be created at the established composition root such as `App.init()` or a configured container; reject ad-hoc global ownership that leaks resources across scene or test lifecycles.
- A dependency cached beyond its declared session or flow scope must be rejected; retaining it in `static let shared` can corrupt user-specific state after logout.
- Local SPM consumers must import only declared `public` or `package` APIs; reject implementation leakage that breaks the package build when internals change.
- New `Package.swift` target dependencies must preserve an acyclic product graph; reject a reverse edge that creates a compiler or build-toolchain cycle.
- Cross-module business contracts must use stable Swift protocols or value types when substitution is required; reject concrete UI framework coupling that makes core state invalid on extensions or tests.
- `@MainActor` UI ownership must remain outside background service modules unless UI delivery is the explicit contract; reject isolation leakage that serializes unrelated work and causes latency.

### State And Lifecycle Architecture Rules

- A feature must have one authoritative owner for observable state, whether `@Observable`, `ObservableObject`, or a store; reject parallel owners whose values race and render incorrect UI.
- `@StateObject`, `@ObservedObject`, and Observation `@State` placement must follow construction ownership; reject recreation in `body` that loses state across SwiftUI identity changes.
- UIKit presentation must originate from the active scene's owned `UIViewController`; reject global-window lookup that presents on a detached lifecycle and fails visibly.
- Effects started by a feature owner must expose cancellation or structured task ownership such as `.task(id:)`; reject detached work that leaks after navigation.
- Persistence and networking implementations must not become the source of truth for presentation state unless the established architecture declares that ownership; reject feedback loops that corrupt state ordering.
- Architecture changes must retain injectable seams for `URLSession`, clocks, or stores used in tests; reject hard-wired resources that make failure and degradation paths untestable.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
