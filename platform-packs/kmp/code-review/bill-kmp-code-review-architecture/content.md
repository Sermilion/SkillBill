---
name: bill-kmp-code-review-architecture
description: Use when reviewing Gradle module boundaries and dependency direction, DI graph and scope ownership, ViewModel lifecycle ownership of long-lived work, repository/use-case/sync-engine authority, WorkManager ownership, and offline single-source-of-truth on Android and KMP.
internal-for: skill-bill
---

# KMP Architecture Review Specialist

Review only boundary ownership, dependency direction, and the lifetime of state and long-running work across Android and Kotlin Multiplatform modules.

## Focus

- Gradle module boundaries and dependency direction between `:app`, `:feature`, `:domain`, and `:data` modules
- DI graph shape and component scope ownership for `dagger.hilt` or Koin declarations
- `ViewModel` and lifecycle ownership of long-lived work, sync engines, and background schedulers
- Authority split between repository, use case, and sync engine, including offline single-source-of-truth writes

## Ignore

- Runtime concurrency and `expect`/`actual` target semantics owned by the platform-correctness specialist
- Query shape, migration, and transaction mechanics owned by the persistence specialist
- Retry, backoff, and recovery tuning owned by the reliability specialist
- Compose rendering and navigation state owned by the ui specialist

## Applicability

Use this specialist when a diff adds or moves a Gradle module, changes `build.gradle.kts` dependency declarations, edits a DI module or component scope, introduces or relocates a `ViewModel`, repository, use case, or sync engine, changes who schedules background work, or changes which layer writes the local store. Evaluate every rule against configuration change, process death, and a second caller reaching the same boundary from another module.

## Project-Specific Rules

### Gradle Module Boundary And Dependency Direction Rules

- A `:domain` or shared `commonMain` module must never declare a dependency on `:app`, `:feature`, or an Android-only artifact; reject the inverted edge because the domain then cannot be reused from another target and every feature build drags the whole application graph.
- A feature module must reach another feature only through a shared contract module, never through a direct `implementation(project(":feature-other"))` edge; reject the sibling edge because it creates a cycle risk and makes either feature impossible to build or test alone.
- Types crossing a module boundary must live in the module that owns the boundary, not be re-declared per consumer; reject duplicated boundary models because the two copies drift and a change to one silently breaks the other consumer's mapping.
- A new module must declare an explicit `namespace` and an intentional visibility for its public surface, keeping internals `internal`; reject a module that leaks every class publicly because callers bind to details and later refactors break unrelated features.
- Build-logic changes must keep the module graph acyclic and must not add a compile-time edge purely to reach a single constant; reject the shortcut edge because the resulting cycle or fan-in inflates incremental compilation for every downstream module.

### Dependency Injection Graph And Scope Ownership Rules

- A binding holding mutable state or an open connection must be installed in the component whose lifetime matches that state, not in `SingletonComponent` by default; reject an over-scoped binding because the state outlives the screen that owns it and leaks stale data into the next session.
- A `ViewModel`-scoped dependency must never be injected into a singleton-scoped collaborator; reject the mismatched crossing because the singleton pins the destroyed screen's graph and the leak grows with every navigation.
- An injected `CoroutineScope` must have exactly one owner that cancels it; reject a scope injected into several holders because none of them can safely cancel and work continues after its owner is gone.
- Construction of a repository, use case, or sync engine must go through the DI graph rather than a manual `object` singleton or a static holder; reject the hand-rolled instance because tests cannot substitute it and two divergent instances of the same store end up serving different callers.
- A DI module must not expose the same interface under two unqualified bindings across components; reject the ambiguous binding because callers silently receive whichever component resolved first and behavior differs by injection site.

### ViewModel And Long-Lived Work Ownership Rules

- Work that must survive the screen must not run in `viewModelScope`; reject a sync or upload launched there because a configuration change or back navigation cancels it mid-flight and the user's action is lost with no error.
- A `ViewModel` must not hold an `Activity`, `Context`, `View`, or `NavController` reference; reject the captured reference because it keeps a destroyed window alive and crashes on the next callback after rotation.
- State that must outlive process death must be written through `SavedStateHandle` or a durable store rather than kept as a plain field; reject in-memory-only state because a low-memory restart returns the user to an empty screen with their input gone.
- A `ViewModel` must consume a use case or repository and never call a network client, `Room` DAO, or scheduler directly; reject the direct call because the same operation then exists in two places and one of them skips the store update.
- Two screens needing the same live data must share one repository-owned stream rather than each holding a private `MutableStateFlow` copy; reject the duplicated holder because the screens disagree after any write and the user sees inconsistent values.

### Repository, Use Case, And Sync Engine Authority Rules

- The repository must own the local store and be the only writer to it; reject a use case or sync engine writing the store directly because the repository's cached stream misses the change and readers keep serving invalid data.
- A use case must orchestrate repositories rather than hold durable state of its own; reject a stateful use case because its state is unrecoverable after process death and disagrees with the store it was derived from.
- The sync engine must own remote reconciliation and hand results to the repository, never push UI state; reject a sync engine that updates a screen holder because the same reconciliation then bypasses the store and the change disappears on the next read.
- Mapping between transport models and domain models must happen once at the data-layer boundary; reject transport types reaching a `ViewModel` because every consumer re-implements the mapping and their interpretations of an optional field diverge.
- A cross-layer contract must be an interface owned by the consuming layer with its implementation in the data layer; reject a domain type that depends on a data-layer concrete class because the dependency direction inverts and the domain can no longer be tested without the store.

### Worker And Background-Task Ownership Rules

- Exactly one component may enqueue a given unique `WorkManager` chain, and it must be a repository or sync-engine seam rather than a screen; reject enqueue calls scattered across `ViewModel` classes because concurrent screens then schedule conflicting copies of the same operation.
- A worker must resolve its collaborators from the DI graph and read its inputs from durable storage by identity; reject a worker constructing its own repository because it operates on a second instance whose cache never matches the one the screen observes.
- Scheduling policy must live with the owner of the work, not be duplicated in a receiver, a screen, and a worker; reject the scattered policy because a change in one location leaves the other paths scheduling on the old constraints.
- A worker must write results through the repository boundary it shares with the foreground path; reject a worker writing its own store because the foreground read path never sees the result and the operation appears to have failed.

### Offline Single-Source-Of-Truth Rules

- Reads shown to the user must come from the local store, with the network used only to refresh it; reject a screen that renders a remote response directly because the rendered value and the stored value then disagree after any partial refresh.
- A local write must be committed with its pending-sync marker in one durable step before the remote call starts; reject a remote-first write because a crash between the two steps loses the user's edit with no record that it was attempted.
- Remote reconciliation must resolve against the record's stored identity and revision rather than replacing rows wholesale; reject a blanket replace because it discards local edits that have not yet synced and the loss is invisible to the user.
- Exactly one layer may decide conflict resolution for a record; reject conflict rules duplicated in the sync engine and the repository because the two rules disagree and the surviving value depends on which path ran last.

### Cross-Document Selection-Set Divergence Rules

- When two documents, fragments, or model trees describe one payload and a runtime predicate selects between them, a field added to one selection set must be added to the other; reject the one-sided addition because the same response decodes differently depending on which document the predicate selected, and the missing field reads as absent rather than as an error.
- A payload described by more than one selection set must derive both from one shared fragment or one shared model definition; reject parallel hand-maintained selection sets because nothing fails at build time when they drift and the divergence surfaces only as invalid data on one runtime branch.
- A runtime predicate choosing between two documents must be defined next to the documents it selects, and adding a variant must require updating that single decision point; reject a predicate scattered across call sites because a new variant reaches only the call sites the change touched.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
