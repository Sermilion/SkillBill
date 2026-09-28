---
name: bill-go-code-review-architecture
description: Use when reviewing Go package and module boundaries, dependency direction, interface ownership, composition roots, component lifecycles, and goroutine scope ownership.
internal-for: skill-bill
---

# Go Architecture Review Specialist

Own structural placement and ownership decisions. Leave statement-level concurrency semantics to platform correctness and runtime draining or recovery policy to reliability.

## Focus

- Package and module direction, interface ownership, composition roots, and component lifecycles
- Scope ownership for components that launch goroutines or retain process-wide resources

## Ignore

- Statement-level channel, synchronization, and error semantics owned by platform correctness
- Runtime drain, retry, and shutdown policy owned by reliability

## Applicability

Apply these rules to changed Go packages, modules, entry points, constructors, interfaces, and long-lived components. Infer the repository's established shape before proposing a different one.

## Project-Specific Rules

### Go Architecture Rules

- Verify imports across `internal/` and public packages follow the intended dependency direction; an inverted edge must be rejected when it creates a cycle or lets transport code own business state.
- Require each `go.mod` boundary to have a deliberate owner and dependency surface; an accidental module split risks incompatible versions and broken local builds.
- Ensure a consumer package owns a narrow Go `interface` when substitution is needed; provider-owned catch-all interfaces risk dependency inversion failure and unusable test doubles.
- Reject interfaces accepted as `interface{}` or `any` when a concrete contract is known; lost method constraints permit invalid runtime values and delayed panics.
- Require executable wiring in `cmd/<name>/main.go` or an equivalent composition root to expose dependency construction; hidden global initialization risks order-dependent startup failures.
- Verify constructors such as `NewServer` return components whose ownership and required dependencies are explicit; partially initialized values risk nil dereferences after startup.
- Ensure long-lived components pair `Start` with an owner-visible `Close`, `Stop`, or `Wait`; an orphaned lifecycle leaks goroutines and resources during reload or shutdown.
- Require every goroutine-producing component to expose how its `go` statements stop and join within the component's documented scope; detached work risks state mutation after its owner has stopped, but goroutines do not need invented names.
- Reject package globals guarded only by `init()` when initialization order affects correctness; import-order coupling can break tests and alternate binaries.
- Verify shared state has one component owner and an explicit access contract using confinement, `sync.Mutex`, `sync/atomic`, or channel ownership as the invariant requires; duplicate or mismatched ownership risks races and divergent state.
- Ensure `context.Context` is passed through operations rather than stored permanently in service structs; retained request context risks stale authorization data and resource leaks.
- Require transport structs, domain values, and storage records to remain distinct when their validation or serialization contracts differ; shape reuse risks corrupt data and client regressions.
- Verify cross-package callbacks or `chan T` values declare send, receive, and termination semantics, including whether closure is part of the protocol and who may perform it; ambiguous ownership risks deadlock and shutdown crashes.
- Reject business workflows embedded in `http.Handler`, Cobra `RunE`, or template functions when the local architecture has an application layer; duplicated orchestration risks inconsistent authorization and transaction behavior.
- Require generated clients or models to remain behind an adapter when `// Code generated` surfaces volatile external contracts; direct propagation risks build-wide churn and compatibility failures.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
