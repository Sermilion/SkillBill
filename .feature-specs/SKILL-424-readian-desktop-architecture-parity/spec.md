/c# SKILL-424 Readian architecture parity for the desktop app

## Outcome

Skill Bill's desktop app is a populated Kotlin Multiplatform multi-module project modeled on `/home/sermilion/StudioProjects/readian-android`. It has its own included `build-logic` build, shared core modules, feature-owned presentation, generated Kotlin-Inject and Anvil wiring, and shared Navigation 3. Domain, data, presentation, application composition, and desktop host code have enforced dependency and source-set boundaries.

The architecture exists in compiled production code. Moving packages inside the old JVM application, adding empty Gradle modules, retaining the old controller behind new wrappers, or documenting a future migration does not satisfy this feature.

Only the JVM desktop target is registered. Linux, macOS, and Windows use that target. A later mobile host can reuse domain, repositories, ViewModels, navigation, and Compose screens in `commonMain` without relocating their business or presentation logic.

This invocation authors a separate parent spec. Implementation, decomposition, workflow launch, validation execution, installation, commits, and publication require a later invocation.

## Problem and evidence

On 2026-10-11, the inspected implementation checkout used `desktop-tray/settings.gradle.kts` with no included application subprojects and `desktop-tray/build.gradle.kts` with the Kotlin JVM plugin. Domain, application, presentation, UI, composition, preferences, and CLI infrastructure lived under the same `desktop-tray/src/main/kotlin` tree. The Gradle/source inventory showed no desktop `build-logic`, `composeApp`, `core:*`, `feature:*`, or common source sets.

SKILL-423's parent and saved subtask plan already describe a Readian-style KMP migration. Its manifest remained `in_progress` at inspection. These observations establish an unimplemented structural requirement at that point, not a completed workflow verdict. Reinspect the eventual implementation checkout before changing code.

The operator requires a separate, detailed specification for architecture, DI, navigation, and related application structure. This feature makes those requirements independently verifiable. Claims from an earlier implementation are not evidence that a module, scope, or lifecycle boundary exists.

## Relationship to existing work

SKILL-423 supplies the implemented desktop data-layer behavior, Room 3 compatibility with the runtime-owned durable store, external committed-write observation, paging, controls, preferences migration, and removal of CLI/polling dependencies. This feature owns the final application architecture and its enforcement. It also owns the remaining data repair obligations transferred on 2026-10-12 below. It consumes and preserves existing behavior wherever it has landed.

Reconcile overlapping changes against the current tree. Keep existing working repositories, runtime repairs, native integrations, and regression coverage. If SKILL-423 has not delivered a prerequisite needed to exercise this architecture, identify the concrete missing production boundary. Implement the smallest required integration within this feature or report the exact blocking acceptance criterion. Do not replace a missing data layer with demo data, an always-empty repository, a second database, periodic CLI reads, or a new workflow engine.

Preserve SKILL-414, SKILL-417, SKILL-418, and landed SKILL-422 lifecycle/status behavior. Runtime architecture rules remain authoritative. This spec does not authorize weakened guards, changed workflow semantics, schema duplication, or an unrelated UI redesign.

Follow the active-fix-branch rule in the implementation checkout's `AGENTS.md`. The current instruction names `base/SKILL-380-phase-slot-strategies` until it merges. Recheck that condition before a later execution and use its worktree when another feature branch has work in progress. All spec references and later planning paths are repository-relative.

## Reference evidence and precedence

Reference root: `/home/sermilion/StudioProjects/readian-android`.

Observed reference HEAD: `36d1dd9f902a7ab8b7eb2117e898e911fe51120d` on 2026-10-11. This records the inspected revision. A future implementer records any reference drift before adopting changed patterns. The inspected working tree is the source for the observations below; HEAD alone does not prove it was clean.

Use actual reference code to resolve patterns. Readian documentation explains intent but does not override contradictory production code. Skill Bill runtime contracts and this spec take precedence where the products differ.

| Inspected Readian path | Observed pattern | Required Skill Bill adaptation |
| --- | --- | --- |
| `settings.gradle.kts` | `pluginManagement.includeBuild("build-logic")`, typed project accessors, populated core and feature subprojects | Independent desktop included build and explicit populated module graph |
| `build-logic/settings.gradle.kts` | Convention build imports the root version catalog and includes `:convention` | One desktop catalog supplies convention and application dependency versions |
| `build-logic/convention/src/main/kotlin/KmpLibraryConventionPlugin.kt` | Shared KMP, quality, DI, and test configuration | Desktop-only convention registers JVM and does not apply an Android plugin |
| `build-logic/convention/src/main/kotlin/KmpComposeConventionPlugin.kt` | Compose compiler, resources, common dependencies, and JVM UI-test setup | Shared Compose convention with explicit generated-resource task dependencies |
| `build-logic/convention/src/main/kotlin/AndroidKotlinInjectConventionPlugin.kt` | Kotlin-Inject/Anvil runtimes and KSP compiler configuration | Common/JVM generation only, with no Android/iOS task registration |
| `core/domain/build.gradle.kts` | Domain repository contracts expose portable Paging; common dependencies | No Compose, lifecycle, storage, runtime, or host dependencies in domain |
| `feature/detail/build.gradle.kts` | Feature consumes common, domain, design system, and shared UI | Feature modules do not import data, datastore, another feature, or runtime |
| `composeApp/src/commonMain/kotlin/news/readian/readiannews/ApplicationComponent.kt` | `@MergeComponent`, app scope, generated providers and factories | App component exposes only dependencies read by the app shell and host |
| `core/common/src/commonMain/kotlin/news/readian/readiannews/common/di/Scopes.kt` | Explicit app and feature scope annotations | App, database-session, window, and entry lifetimes with named owners |
| `core/domain/src/commonMain/kotlin/news/readian/readiannews/core/domain/di/AppFeatureScreenComponent.kt` | Contributed screen subcomponent and generated factory | Presentation factories live in shared UI, keeping domain free of ViewModel types |
| `core/ui/src/commonMain/kotlin/news/readian/readiannews/core/ui/navigation/InjectViewModel.kt` | Screen component retained by entry ViewModelStore; route arguments enter creation extras | Typed assisted arguments and entry-owned component lifecycle |
| `core/common/src/commonMain/kotlin/news/readian/readiannews/common/di/ViewModelEntry.kt` | Custom ViewModel factory entries and assisted-argument bridge | No new string-keyed `Any` argument bag or unchecked cast in app-authored wiring |
| `feature/detail/src/commonMain/kotlin/news/readian/readiannews/detail/feature/postdetail/PostDetailContract.kt` | Immutable screen state, actions, and feedback types | One typed contract per Skill Bill screen |
| `feature/detail/src/commonMain/kotlin/news/readian/readiannews/detail/feature/postdetail/PostDetailViewModel.kt` | Constructor injection, assisted arguments, StateFlow, effects, viewModelScope | Typed Flow combination and bounded lifecycle work without unchecked array casts |
| `composeApp/src/commonMain/kotlin/news/readian/readiannews/core/ui/navigation/nav3/ReadianNavHost.kt` | `NavDisplay`, central registration, saveable-state and ViewModelStore entry decorators | Desktop shell retains its design and gives each window its own navigation owner |
| `composeApp/src/commonMain/kotlin/news/readian/readiannews/core/ui/navigation/nav3/RouteBackStack.kt` | Serializable typed stack with saved-state configuration | Versioned restoration behavior and database-generation validation |
| `composeApp/src/commonMain/kotlin/news/readian/readiannews/core/ui/navigation/nav3/entries/DetailEntries.kt` | Host-side entry adapter combines feature routes, factory arguments, and callbacks | Cross-feature routing stays in composeApp so feature modules stay independent |
| `core/data/src/commonMain/kotlin/news/readian/readiannews/core/data/subscriptions/repository/ReadianTopicsRepository.kt` | Injected scoped binding, domain mapping, Pager and PagingData | Local runtime-backed queries, ten-record pages, no remote mediator without a real remote source |
| `core/data/src/commonMain/kotlin/news/readian/readiannews/core/data/subscriptions/datasource/local/room/TopicRoomDao.kt` | Typed shared DAO declarations and bounded SQL queries | Runtime-owned schema and candidate semantics remain authoritative |
| `core/data/src/commonMain/kotlin/news/readian/readiannews/core/data/db/room/ReadianRoomDatabaseProvider.kt` | Injected provider owns cached database instances and closure | Typed single-session owner, no destructive deletion or broad failure swallowing |
| `core/datastore/src/commonMain/kotlin/news/readian/readiannews/core/datastore/settings/AppSettingsStore.kt` | Typed DataStore, preference Flow, atomic `updateData` | One settings instance per file with explicit readiness, corruption, and migration outcomes |
| `composeApp/src/jvmMain/kotlin/news/readian/readiannews/JvmComponentFactory.kt` | JVM construction of generated application component | Explicit host-owned startup/shutdown, no unowned global singleton |

This is architecture parity, not a source copy. Do not import Readian's authentication, content feeds, playback, remote networking, mobile targets, Firebase, destructive database deletion, unchecked factory maps, or blanket catches.

## Required final project structure

Keep `desktop-tray/` as the Gradle root and preserve its wrapper entry point. The shipped executable and app branding remain Skill Bill.

```text
desktop-tray/
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml
  build-logic/
    settings.gradle.kts
    convention/
      build.gradle.kts
      src/main/kotlin/
      src/test/kotlin/
  composeApp/
    build.gradle.kts
    src/commonMain/kotlin/
    src/commonTest/kotlin/
    src/jvmMain/kotlin/
    src/jvmMain/resources/
    src/jvmMain/native/
    src/jvmTest/kotlin/
  core/
    common/
    domain/
    data/
    datastore/
    designsystem/
    ui/
    testing/
  feature/
    work/
    detail/
    history/
    settings/
```

Every core and feature directory above is a real included Gradle project with its own build file and a current production or test responsibility. Shared modules use `commonMain` and `commonTest`. Add populated `jvmMain` or `jvmTest` only where platform integrations or tests require them. Empty source directories do not prove portability.

The root build aggregates configuration and checks. It does not compile the old monolithic application or contain its production classes. `composeApp` owns application composition and the desktop executable. The old root `src/main` and `src/test` application trees are deleted after their responsibilities and tests move to the owners below.

Presentation is a layer distributed across populated `feature:*` modules and reusable `core:ui`. Readian does not have a generic `:presentation` module. Do not add one as a container for the old global controller. Each feature owns its screen contracts, ViewModels, UI models, mappers, routes, and screens. `composeApp` owns shell composition and entry adapters that need several features.

Optional `codegen:viewmodel-inject-processor` is allowed only if Readian-style generated entry registration needs app-specific processing beyond Kotlin-Inject/Anvil. Its input, generated typed factory contract, production consumers, and rejection tests must be identified. It is a build-time tool and adds no runtime reflection registry. Do not create it merely to match a directory name.

## Module responsibilities and dependencies

| Module | Owns | Allowed application project dependencies | Forbidden responsibilities |
| --- | --- | --- | --- |
| `core:common` | Portable dispatchers, clock utilities, DI scope annotations, coroutine qualifiers | None | Business policy, database connection, UI, catch-all helpers |
| `core:domain` | Pure work/settings models, query and control results, repository interfaces, behavior-bearing use cases | `core:common` | Compose, ViewModel, Navigation 3, Room, DataStore, JVM/runtime APIs |
| `core:datastore` | Preference serialization, typed store, migration boundary, JVM preference-path builder | `core:common`, `core:domain` | Workflow persistence, feature state, navigation |
| `core:data` | Repository implementations, Room DAOs/entities, SQL paging, mapping, session lifecycle, JVM runtime adapter | `core:common`, `core:domain`, `core:datastore` | Compose screens, navigation, second runtime composition graph |
| `core:designsystem` | Theme, typography, colors, generic controls, Compose resources | `core:common` only if a real consumer needs it | Work queries, settings persistence, feature routing |
| `core:ui` | Shared state rendering, work UI primitives, portable route marker, entry DI/factory helpers | `core:common`, `core:domain`, `core:designsystem` | Concrete repositories, database/runtime objects, cross-feature orchestration |
| `feature:work` | Needs you, Goals running, Standalone phases, filtering, selection intent | `core:common`, `core:domain`, `core:designsystem`, `core:ui` | History ownership, concrete data, global controller |
| `feature:detail` | Work detail, paged sublists, pause/stop state and effects | Same as `feature:work` | Storage transactions or direct host calls |
| `feature:history` | Retention-filtered history, pagination, filter and position state | Same as `feature:work` | Live membership or notification baseline ownership |
| `feature:settings` | Database path/preferences editor, connection diagnostics view | Same as `feature:work` | Filesystem discovery, DataStore construction, schema repair |
| `composeApp` | Generated app composition, navigation host/entry adapters, app-wide transition coordinator, JVM windows/tray/native integrations, packaging | Populated core and feature modules needed by composition | Repository policy, SQL, DAO construction outside providers, workflow execution |
| `core:testing` | Reusable deterministic clocks, dispatcher fixtures, repository substitutes, test data | `core:common`, `core:domain` | Production app use, data/schema implementations, upward feature dependencies |

Portable Coroutines, serialization, Kotlin-Inject annotations, and Paging common contracts are permitted where their public signatures require them. Domain's framework-independent rule excludes UI, lifecycle, storage, and host frameworks; it does not ban Flow or portable Paging contracts explicitly required by SKILL-423.

Use `implementation` unless a public signature exposes a dependency. Each `api` dependency has a named exposed type and consumer. Keep the graph acyclic. Feature modules never depend on one another. Shared routes use a minimal common marker; each feature declares its own serializable routes. The app shell connects feature navigation through typed callbacks.

Only JVM source sets may consume supported runtime libraries. Use the build's supported composite/module integration without importing CLI/MCP artifacts or pointing at an installed runtime distribution. `runtime-core` remains the runtime composition root. Common data code depends on portable contracts and injected adapters, not JVM-only runtime classes.

## Build conventions

`pluginManagement` includes the desktop's own `build-logic`. The convention build imports `desktop-tray/gradle/libs.versions.toml`. Runtime build logic and Readian build logic are references, not dependencies on developer-specific absolute paths.

Provide project-owned convention IDs for KMP library, Compose KMP, Kotlin-Inject/Anvil, Room 3, feature modules, tests/quality, and desktop application packaging where responsibilities justify distinct plugins. Use one consistent Skill Bill prefix. Implement common behavior once. A plugin may compose smaller conventions; it must not silently register mobile targets.

The KMP convention registers one `jvm` target, the project's chosen JVM toolchain, common/JVM test wiring, and shared compiler options. The Compose convention applies Compose Multiplatform and its compiler and owns resources/UI-test setup. The DI convention owns runtime/compiler dependencies, contribution annotation configuration when used, and source-generation ordering. The Room convention owns Room/compiler/driver setup for data only. Feature builds declare their production dependencies and feature-specific configuration rather than repeating source-set setup.

Pin Kotlin, Compose, KSP, Kotlin-Inject, Anvil, lifecycle/ViewModel, Navigation 3, saved state, Paging, Room 3, SQLite driver, DataStore, Coroutines, and serialization in the catalog. Resolve compatible common/JVM artifacts and validate them in a clean build. Readian's recorded Room version is evidence of its setup, not proof that every copied version works with Skill Bill.

KSP runs for the source sets that require generation. Do not run processors twice over the same declarations, rely on stale generated sources, or add dependsOn edges to every task by name substring. Model actual generation/resource dependencies. Generated output stays under build directories and is not committed.

Native tray compilation, resource staging, Compose distribution, and AppImage tasks belong to the desktop host/application build convention or `composeApp`. Preserve verified native loading, package identifiers, icons, and supported distributions. Root task aliases preserve documented developer commands where module task paths change.

A clean desktop configuration/build works without Android SDK variables or Apple toolchains. No Android/iOS plugin, target, source compilation, KSP configuration, or download is required. CI checks the actual target inventory and compilation graph, not just absence of an `androidMain` directory.

## Dependency injection and resource ownership

Use Kotlin-Inject constructor injection with Anvil scoped providers/contributed bindings and generated components. This matches Readian's production DI pattern. Do not substitute Koin, Hilt, a service locator, reflection-based constructors, manual collaborator trees, or a global mutable component cache.

Inject behavior-bearing classes through private constructor parameters. Nonconstructible/platform resources enter through narrow provider methods. An app component exposes only entry points with named app-shell or JVM-host consumers, not every internal dependency.

| Lifetime | Owner and contents | Destruction condition |
| --- | --- | --- |
| App | JVM startup owner, generated app component, one preferences instance, host event router, transition/notification coordinator | Explicit app exit after child owners close |
| Database session | Selected database identity/generation, shared connection owner, repositories, external invalidation registration, supported runtime integration | Database switch, same-path replacement, failed initialization, or app shutdown |
| Window | Window owner, navigation stack, selection/scroll restoration state | Actual window disposal; hiding follows the app's retained-window policy |
| Navigation entry | Entry component, ViewModelStore, assisted route arguments, screen collectors/paging state | Entry removal, window disposal, or database-generation invalidation |

Scopes correspond to these real lifetimes. Do not copy Readian's authenticated `UserScope` into an app with no user session. A contributed database-session child or another typed generated scope may adapt that pattern. Shared database/notification resources are not window scoped. Per-window selection and entry ViewModels are not app singletons.

The JVM host constructs one app owner. It does not construct a new runtime component per screen, recomposition, query, or control. Database-dependent runtime wiring follows the supported connection/session lifetime and closes on a generation change. App composition does not duplicate runtime collaborators.

Create screen components through generated factories and retain them using the current entry's ViewModelStore, following Readian's component-holder pattern. Pass runtime route arguments with typed assisted factories or typed creation-extra keys. Incorrect/missing arguments produce an attributable boundary outcome. Do not add a raw map of string names to `Any`, unchecked casts, or a typealias hiding one.

Use injected dispatchers and clocks. App scopes own app background work; ViewModel scopes own screen work. Every long-lived observer has a named owner and cancellation path. Constructor defaults do not manufacture production collaborators. Providers do not silently return dummy implementations after initialization fails.

Closing a window cancels its entry jobs without closing a database used by another window. Switching stores cancels old-session and old-entry work, waits for owned cleanup, and rejects late results before exposing the new session. Repeated open/close/switch operations leave no retained connection, native listener, stale ViewModel, or notification job.

## Feature presentation

Each screen has a colocated contract, ViewModel, route/arguments, UI models/mappers, and Compose route/content functions in `commonMain`. Use noun-based feature/screen packages matching Readian's grouping. Do not create a global folder containing all ViewModels or all contracts.

Contracts expose immutable typed `UiState`, `Action`, and one-time `Effect` types. ViewModels publish read-only StateFlow and an explicit effect stream. Internal mutable state stays private. Screens render state and send actions; the route adapter owns injection, lifecycle collection, and navigation/host callbacks.

Require separate ViewModels for work, detail, history, and settings. Split the old `DashboardController` responsibilities among these owners and app-level transition coordination. Delete it and superseded `BoardSource`/presentation wrappers when their last consumer migrates. Merely renaming the controller or forwarding all feature actions to it fails acceptance.

Work has independent page/load state for Needs you, Goals running, and Standalone phases. History owns its retention and paging generation. Detail owns selection-specific queries and paged sublists. Settings owns draft inputs, persistence actions, and typed validation outcomes. No screen queries another screen's ViewModel to get application data.

Derive screen state from typed repository streams. Use typed intermediate combinations instead of untyped Flow arrays and indexed casts. Show initial loading, loaded empty, retained content refreshing, retained content with a read error, append error, and control pending/refused outcomes distinctly. A refresh does not turn usable content into an initial-loading screen.

Define effect delivery per effect family. User feedback and navigation effects must not replay after recomposition or back-stack restoration. Required pending feedback cannot disappear because a collector temporarily stopped. Keep durable/pending operation state in UiState and use bounded event delivery with tested acknowledgement or ownership where needed. Do not claim exactly-once behavior from an unbuffered SharedFlow alone.

Entry-owned collectors stop or suspend according to the Navigation 3 lifecycle. Restored entries retain their state under the same valid generation. New routes or store generations cannot reuse a stale detail result. Control admission still revalidates runtime target identity; UI optimistic state never fabricates a terminal workflow transition.

Notification transition tracking is app/session owned, independent of the currently displayed screen. Opening history, restoring navigation, discovering initial data, or switching stores cannot generate completion notifications. Elapsed-time display uses UI time and scheduled deadlines without periodically querying storage.

## Navigation 3 and desktop windows

The common app shell uses Navigation 3 `NavDisplay` with a typed back stack, explicit saved-state serialization, and both saveable-state-holder and ViewModelStore entry decorators. Merely adding Navigation 3 dependencies while routing through a global `selectedWork` field fails acceptance.

Feature modules own route declarations. `composeApp` owns the host, root sections, cross-feature entry adapters, and callbacks. Shared UI owns only reusable navigation/entry injection utilities. This follows the inspected reference implementation, whose entry adapters live in composeApp.

Routes carry portable durable identity and small serializable arguments, including repository/work identity and a database-generation reference where needed. They never carry Room entities, DAO objects, runtime components, connection handles, filesystem objects, callback closures, or decoded CLI payloads. Route tokens do not authorize controls.

Required destinations are work, work detail, history, history detail, and settings. Preserve the existing sidebar and master/detail design. Navigation 3 can host detail panes or adapt display scenes; do not force the desktop into a mobile full-screen layout to simplify routing. The tray popover remains a deliberately bounded preview with typed open-full-view intents.

Back returns to the originating list's filter, selection, and usable scroll anchor. Root-section switching preserves each section's valid position according to an explicit restoration policy. Opening the same identity through a tray click while its detail entry is active focuses/reuses the intended entry rather than creating unlimited duplicates.

Each desktop window has its own stack, entry keys, ViewModelStore, saved-state owner, and selection. Windows share scoped repositories/preferences only. A notification or tray activation sends a typed host intent to the selected target window and activates it before navigation. Define focus/reuse/create behavior for no window, hidden window, and multiple windows.

Window hiding and disposal are different operations. Closing into the tray follows existing product behavior; actual disposal tears down navigation ownership. Quitting the app closes all windows/entries and shared resources. Native callbacks dispatch onto the appropriate host/UI dispatcher before changing navigation state.

Store switching is a scope transition, comparable to Readian's state-driven graph replacement. Invalidate old database-bound routes, clear old selection, reset the notification baseline, and render a new-session root after its dependencies are ready. A same-path replacement is also a generation change. Prevent rendering an old entry with a new session component.

Missing work yields an explicit unavailable detail state with a path back to the list. Restoration of unknown, invalid, or obsolete route data yields bounded diagnostics and a safe root, not an untyped crash or silent use of the wrong database. Do not put navigation restoration state in workflow tables. Session-local saved state is sufficient unless existing product behavior requires persistent app-only restoration.

## Data repair handoff from SKILL-423

On 2026-10-12 the operator transferred the seven remaining reported repair obligations from SKILL-423 to this feature and requested completion of its `audit_implement_fix` phase by scope transfer. The source workflow is `wftr-20261012-112951-so8l`, repair attempt 8. Its latest repair report finished at `2026-10-12T16:02:56.524Z`; the last acceptance audit finished at `2026-10-12T11:48:22.957Z`. No fresh audit confirmed the report's remaining-gap count. Phase completion records this handoff, not a passing verdict for these obligations or completion of the SKILL-423 goal.

The source IDs below use SKILL-423's normalized audit numbering. Preserve that provenance and use this feature's AC-28 through AC-34 for acceptance. Reconcile every reported gap against the current production tree before changing it. Some report descriptions predate repairs to bounded shared reads, store/revision cursors, exact accepted execution output, owner observation, and detail deadlines. Keep a source-backed satisfied verdict for an obligation already met and preserve its coverage. Implement and validate the actual remaining gap; do not repeat an obsolete repair or treat a report as proof of current behavior.

| Source obligation | SKILL-424 criterion | Required completion |
| --- | --- | --- |
| SKILL-423 AC-002, policy and enforcement | AC-28 | Shared aggregate status, membership, attention, phase grouping, sort, and control policy has one owning runtime domain implementation. Engine/adapters coordinate or narrow candidates conservatively; desktop renders authoritative typed facts. Changed-boundary guards resolve declaration edges through real consumers, adapters, and manifest-declared runtime roots, including typed/expression bodies, aliases, overloads, extensions, and dispatch. Unresolved governed edges and empty scans fail; rejection cases prove the scanner. |
| SKILL-423 AC-003, bounded selection and metrics | AC-29 | Shared and desktop live/history selection bounds persistence reads and expensive projections, including legacy identity and all coherence retries. Attributable persistence-boundary evidence distinguishes SQLite filter/index work from returned rows, projection work, and timings. Request-wide accounting includes failed, retried, budget-exhausted, and successful attempts without logging rows or raw parameters. |
| SKILL-423 AC-004, full durable reasons | AC-30 | Board, selected detail, history, copy actions, and visible reason text preserve the exact durable pause/block reason for the selected identity. Typed facts separate bounded labels from full text and expose legacy restoration outcomes. Preserve exact accepted execution output, bounded legacy restoration, and latest-execution ordering already implemented. |
| SKILL-423 AC-009, session lifetime | AC-31 | One database-session owner binds identity/generation, connections, observers, callbacks, owner registrations, timers, and in-flight reads. Replacement, reconnect, initialization failure, cancellation, and exit cancel and join owned work before a replacement becomes usable, without holding a mutex required by cleanup callbacks. Preserve bounded manual-refresh coalescing and healthy-session reuse. |
| SKILL-423 AC-010, detail and retained paging | AC-32 | Every growing displayed detail collection, including nested commands, checks, gate evidence, dependencies, validation, and execution history, has an independent bounded persistence selector and continuation. Existing bounded status summaries remain supported. Cursors fence store identity, relevant revision, query, parent, and selected list. Retained board/detail pages publish one coherent observation, reject stale results, and preserve a usable identity/scroll anchor through inserts, updates, deletes, and refresh. Each list owns append/retry; no generic action chooses another list's cursor. |
| SKILL-423 AC-011, control admission and feedback | AC-33 | Shared admission validates the exact selected database/store/file identity through the owning write connection, alongside repository, execution, revision, and lease fences. Same-path replacement cannot write to the prior file after a desktop path check. Durable intent, accepted request, attempted termination, confirmed exit, and incomplete termination remain distinct typed outcomes through desktop feedback. Host waits remain outside the transaction and acceptance cannot fabricate lifecycle completion. |
| SKILL-423 AC-017, deadline ownership | AC-34 | Owner death, lease expiry, shared freshness, recent-completion expiry, and history cutoffs reproject subscribed data without writes or periodic store polling. Board and independently subscribed detail deadlines belong to the selected store/generation/query/identity and retained ranges. Append, eviction, refreshed ranges, selection changes, and replacement recompute and wake the earliest deadline; obsolete callbacks cannot invalidate a new owner. Preserve implemented owner-exit, freshness, lease, and retained-page scheduling repairs. |

Current source owners include `desktop-tray/src/main/kotlin/dev/skillbill/tray/application/Ports.kt`, `DashboardController.kt`, `domain/WorkModels.kt`, `infrastructure/data/RoomBoardSource.kt`, `RuntimeWorkMapper.kt`, `infrastructure/storage/WorkCandidateQueries.kt`, and `ui/DetailPane.kt`. Runtime owners include `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/`, its `control/` and `pagination/` families, goal/runtime status services, runtime ports, and SQLite persistence implementations. The existing desktop guard is `desktop-tray/src/test/kotlin/dev/skillbill/tray/architecture/DesktopArchitectureTest.kt`. These paths identify handoff entry points; final module placement follows this feature's ownership rules.

AC-005, AC-012, and AC-015 were reported satisfied in the latest SKILL-423 repair result. Preserve refresh/initial-empty behavior, notification deduplication, and independent detail observation. They are not additional open handoff items. This transfer does not weaken runtime architecture rules, mutate a manifest, reset retry history, or authorize fabricated audit/validation evidence.

## Data and preference integration

Common domain contracts expose typed observation/paging results and separate suspend commands. Common data implements Room declarations/mapping where supported. Its JVM adapter consumes the supported runtime projection/control API. UI receives domain values and typed failures, never CLI JSON reserialized from typed results.

Preserve database-backed pagination, query-level filtering, deterministic continuation, independent list generations, ten-record initial pages, bounded retained pages, page retry, and external invalidation. ViewModel placement is not permission to collect every row then slice in memory.

DataStore has one owner per preference file. Common store code maps serialized settings to typed models and uses atomic `updateData`; the JVM provider owns storage location and legacy preferences access. Settings ViewModels use the domain repository and cannot access DataStore or `java.util.prefs` directly.

Preserve retained preferences and one-time migration of legacy values. Discard `cliOverride` and `pollSeconds` with attributed migration diagnostics; never convert an executable path into a database path. Publish preference readiness separately from defaults so a default emission cannot silently connect the wrong store before persisted settings load. Corruption and update failures have typed observable outcomes.

Room is an adapter to the runtime-owned store. Desktop reads do not create a missing database, migrate it, add persistent tracking schema, repair rows, or mutate leases/workflows. Control writes use shared admission and transactions. A missing prerequisite stays an explicit failure. Do not import Readian's destructive database-cache/delete behavior or app-private schema ownership.

## Host adapters and UI preservation

All AWT/Swing, native tray, platform notifications, login-start, filesystem/path selection, JDBC/runtime access, and window lifecycle code belongs in JVM adapters. Common screens express typed host effects or receive callbacks from the app route adapter. Narrow injected interfaces or expect/actual construction boundaries are allowed when the platform requires them.

Move native sources/resources with their production owner and update build inputs and packaging paths. Preserve native library extraction/loading and notification/tray behavior. The packaged app uses bundled libraries and native/platform APIs. It does not execute an installed Skill Bill CLI, discover PATH/login shells, parse command output, or use command-line OS helpers at runtime.

Keep current visual design, typography, icons, window sizing, tray hover/popover behavior, tooltip reasons, keyboard shortcuts, and accessibility semantics. Content functions take immutable state/actions and can render without a real database. Screen boundaries preserve accessible names, focus restoration, keyboard back behavior, error feedback, and scrolling.

## Architecture enforcement and evidence

Add or strengthen desktop architecture guards that read the actual Gradle modules and common/JVM production source sets. Required guards check module population, inward dependency edges, feature isolation, test-only fixture dependencies, common-source portability, domain purity, feature/UI exclusion of storage/runtime APIs, and absence of the old monolith/controller/CLI/polling path.

Guards fail on an empty/missing scan set and prove rejection with synthetic fixtures through their real scanner. Account for aliased imports, qualified names, source-set dependencies, and production `api` leakage. Do not satisfy a boundary by hiding a violation behind typealiases or wrappers.

Check target inventory, build-logic inclusion, convention application, generation inputs, and packaged dependencies through Gradle/build evidence. A source-text search alone cannot prove the resolved runtime classpath or generated graph.

For touched runtime code apply architecture-guidelines section 5, report A1-A12 and G1-G7, and preserve comment/KDoc, wire vocabulary, file-size, dependency, and guard contracts. No baseline, exemption, or suppression grows to let this migration pass. App guards follow the same non-vacuous fixture/input discipline.

Record a migration inventory mapping each old production owner and test to its final module/source set or explicit deletion. Record a Readian parity matrix with reference pattern, final Skill Bill path/symbol, real consumer, lifecycle owner, validation evidence, and any deviation. Important deviations include the JVM-only conventions, database session instead of authentication scope, pure-domain factory placement, typed assisted arguments, runtime schema ownership, host-owned component shutdown, and separate desktop windows.

Update desktop architecture/build/run documentation and CI to describe the compiled final structure. Do not use doc claims as acceptance evidence. Test names, commands, fixture size, measured observer/query counts, and untested hosts are recorded after execution, not invented in this spec.

## Acceptance criteria

Every criterion describes a required final state. The later audit cites compiled production paths and observable evidence. A claim of parity needs the reference-to-result mapping, not just matching directory names.

| ID | Required final state | Evidence and realistic regression |
| --- | --- | --- |
| AC-01 | Desktop settings include populated `composeApp`, all listed core modules, and work/detail/history/settings feature modules; each owns the responsibilities above | Gradle projects/tasks and source/consumer inventory catch empty modules or a monolithic app under new folders |
| AC-02 | Desktop has its own included `build-logic:convention`, imports one catalog, and applies shared KMP/Compose/DI/Room/quality conventions | Convention tests and clean configuration catch duplicated configuration or reliance on a developer's Readian/runtime build path |
| AC-03 | Only the JVM desktop target compiles; common production code and tests are populated and compile | Clean SDK-free build and target graph catch JVM-only modules presented as portable or accidental mobile requirements |
| AC-04 | The allowed module graph is acyclic; features cannot import data/datastore/runtime or one another; production cannot import `core:testing` | Accepted/rejected architecture fixtures catch outward edges, aliased imports, and unused scan roots |
| AC-05 | Domain owns pure portable models/repository contracts and has no Compose/lifecycle/Room/DataStore/navigation/host/runtime dependency | Source and resolved dependency checks catch presentation factories or database models leaking into domain |
| AC-06 | Shared ViewModels, contracts, routes, mappers, and Compose screens live in feature `commonMain`; desktop host/runtime adapters live in JVM source sets | Common compilation and rejection fixtures catch `java.time`, JDBC, filesystem, AWT, and runtime imports in shared code |
| AC-07 | Generated Kotlin-Inject/Anvil bindings create app/repository/entry collaborators; no service locator, reflection registry, or manual duplicate runtime graph remains | Clean generation plus real composition smoke catches stale generated sources or hand-built repository trees |
| AC-08 | App, database-session, window, and entry owners have explicit lifetimes; app-wide resources are shared and entry state is isolated | Two-window test counts instances/jobs and catches singleton selection or a second database owner per window |
| AC-09 | Entry DI and typed assisted arguments create the correct ViewModel once per live entry and retain it across recomposition | Entry remove/recreate and distinct-route tests catch wrong arguments, uncontrolled recreation, or shared detail ViewModels |
| AC-10 | Work/detail/history/settings have separate typed StateFlow/action/effect contracts; mutable state is private and no global controller owns their work | Production consumer inventory and contract behavior tests catch forwarding shells over `DashboardController` |
| AC-11 | Initial loading, loaded empty, refresh, retained read failure, append failure, and pending/refused controls remain distinguishable | ViewModel tests with barriers catch loading flicker, lost rows, and stale-result overwrite |
| AC-12 | Effects follow an explicit bounded delivery policy, avoid replay, and retain required pending feedback through collector interruption | Collector detach/reattach and recomposition tests catch repeated navigation or silently lost required feedback |
| AC-13 | Common Navigation 3 hosts typed routes with saved-state and ViewModelStore entry ownership and central app entry adapters | Actual host tests catch a cosmetic dependency addition with routing still driven by global selection |
| AC-14 | Work/history/detail/back and root switching restore valid filter, selection, and scroll anchor while preserving the desktop master/detail layout | Navigation and UI tests catch returning to page one or replacing the design with a full-screen mobile flow |
| AC-15 | Window stacks/ViewModels are independent; hiding, disposing, and app exit follow distinct ownership rules | Two-window/hide/dispose tests catch cross-window selection and leaked entry collectors |
| AC-16 | Tray/notification intents focus or create the intended window and navigate with typed identity; repeat activation does not grow duplicate entries | Host routing tests catch UI-thread violations, wrong window focus, and duplicate detail stacks |
| AC-17 | Database switch and same-path replacement invalidate old routes, results, cursors, selection, and notification baseline before new-session rendering | Barrier-driven generation tests catch old data on a new connection or historical completion notifications |
| AC-18 | Missing work and invalid restored routes yield explicit bounded outcomes and usable recovery navigation | Restoration tests catch wrong-store reopening, crashes, and unknown-route silent reuse |
| AC-19 | Repository/Room/Paging mapping is owned by data; each growing list has independent database-backed paging and retains bounded pages | Real repository tests catch full-table in-memory slicing, history starvation, and one sidebar consuming another's first page |
| AC-20 | Typed DataStore owns preferences with atomic updates, one instance per file, deliberate legacy migration, and explicit readiness/corruption outcomes | Restart/concurrent-update/migration tests catch clobbered preferences, repeated migrations, or connection to defaults before readiness |
| AC-21 | Desktop runtime projection/control integration uses the supported shared graph and preserves read-only access, schema ownership, and admission semantics | Disposable runtime-produced database tests catch migrations on read, duplicate graphs, and unauthorized/stale control writes |
| AC-22 | Cross-process committed updates reach live/detail/history subscriptions without periodic data polling, and entry cancellation releases their owned subscriptions | Production observation bridge tests catch a migrated UI backed by old polling or leaking observers |
| AC-23 | Old root app source sets, `DashboardController`, superseded combined `BoardSource`, CLI resolver/process/decoder path, executable preferences, and polling behavior have no desktop production consumers and are deleted | Source/consumer inventory and boundary guards catch retained alternate paths or renamed wrappers |
| AC-24 | Native tray/notifications/login-start and packaging live in JVM owners, with no runtime CLI/OS-command fallback | Clean distribution smoke with CLI unavailable and host coverage catches installed-tool dependence and missing native resources |
| AC-25 | Existing visuals, accessibility, keyboard/back/focus behavior, tray preview bounds, tooltips, and notifications survive the migration | Representative UI/host regressions and disposable app smoke catch behavior lost during source relocation |
| AC-26 | Architecture guards have nonempty inputs, rejection fixtures, and build wiring; touched runtime rules/guards only retain or tighten enforcement | Full applicable architecture suites and A1-A12/G1-G7 review catch vacuous guards or expanded exemptions |
| AC-27 | The final parity and migration inventories name real owners/consumers, deviations, executed evidence, and untested hosts; docs/CI match module task paths | Inventory reconciliation against production and CI catches unsupported completion claims or stale monolithic build instructions |
| AC-28 | Shared runtime policy owns authoritative typed work facts and desktop consumers render them; declaration-aware guards enforce the changed data boundary | Source ownership trace and real accepted/rejected scanner fixtures catch duplicate policy or hidden process/runtime access |
| AC-29 | Shared/desktop selection is bounded at persistence and projection boundaries with accurate request-wide query-work evidence | At least 5,000 newer terminal records, older live work, rejected candidates, and forced retries catch history traversal, unbounded fallback, and undercounted work |
| AC-30 | Exact durable pause/block reasons reach board/detail/history/copy consumers for the selected identity | Long distinct reasons across repositories/executions and attributable legacy restoration catch truncated labels or wrong-execution text |
| AC-31 | One database-session owner closes and joins all resources before replacement/reconnect publishes readiness | Barrier-driven replacement, cancellation, reconnect, and failed initialization catch leaked connections, callbacks under cleanup locks, or stale reads |
| AC-32 | Every growing detail list has independent bounded selectors/cursors; retained pages are coherent and preserve a usable identity/scroll anchor | Large nested evidence and concurrent insert/update/delete tests catch full materialization, wrong-list append, mixed revisions, and displaced anchors |
| AC-33 | Control admission binds the exact opened store on the write connection and exposes truthful intent/termination outcomes | Same-path replacement between desktop check and admission, ownership races, and incomplete termination catch wrong-store writes and fabricated stopped feedback |
| AC-34 | Store/query/identity-owned deadline scheduling updates live and independent detail subscriptions without periodic reads | Controlled time, owner death, append/eviction, earlier deadlines, selection change, and replacement catch missed expiry or obsolete timer delivery |

## Validation strategy

This spec does not run validation. A later implementation discovers exact task names from its final Gradle configuration and repository instructions.

Require a clean desktop `./gradlew clean check --no-build-cache`, common-source metadata compilation where the build provides it, common tests through the registered JVM target, affected module/JVM integration tests, convention-build tests, DI generation from empty build outputs, Navigation 3 host/entry tests, and architecture accepted/rejected fixtures. Verify root `check` actually depends on all required child checks; root success with no executed child tests is not completion.

Use deterministic clocks, test dispatchers, owned connection/observer counters, and barriers for scope/navigation/generation races. Test real repository/host boundaries where ownership is the requirement. Prefer a few tests that expose realistic failures to repetitive checks of annotations or directory names. Explain the regression each new behavioral test catches before authoring it.

Storage tests use disposable databases produced by runtime migrations and disposable preference files/home/environment bindings. Include an independently started writer for external invalidation evidence. Snapshot schema/version/workflow/lease/control state before and after read/open/close tests. Do not touch production databases, delete WAL/SHM files, or rely on a live database copy without a supported backup.

Build a clean distribution and launch against a disposable store without an installed CLI or PATH discovery. Exercise opening, browsing live/history/detail, controls against permitted disposable targets, settings, two windows, tray navigation, hiding, and exit. Record clean resource shutdown. Smoke Linux, macOS, and Windows on available hosts and state which hosts were not exercised. A Linux smoke cannot claim native parity on all hosts.

Runtime changes require affected runtime tests, existing architecture guards, and the repository's full runtime validation commands, including `cd runtime-kotlin && ./gradlew check`. Apply the project unit-test-value review gate in its authorized workflow. Do not weaken checks or reinterpret compilation alone as validation.

Completion evidence includes final resolved module/target/dependency graphs, generated-component consumer paths, source/deletion inventory, reference parity/deviation matrix, executed commands/results, lifecycle observer/connection counts, and packaging/host evidence. For AC-28 through AC-34, record the source SKILL-423 ID, current production trace, actual remaining gap or already-satisfied evidence, final owner, and executed regression evidence. The scope transfer is not passing evidence. Keep raw transient logs in runtime-private evidence, not as committed generated artifacts.

## Suggested implementation boundaries

1. Build graph and conventions. Establish the populated desktop KMP modules, desktop-only target configuration, catalog, owned generation/quality wiring, and non-vacuous module/source-set guards.
2. Core and data ownership. Place domain contracts, Room/repository mapping, preferences, runtime JVM integration, and app/session generated DI in their owners while preserving current data behavior. Reconcile and complete transferred policy, bounded reads/metrics, exact reasons, session lifetime, detail paging, control admission, and deadline obligations under AC-28 through AC-34.
3. Feature presentation. Replace global controller ownership with work/detail/history/settings contracts and shared ViewModels, typed mappers, content functions, paging generations, and tested state/effect behavior.
4. Navigation and host lifetimes. Wire the shared Navigation 3 host, entry injection, restoration, per-window ownership, typed tray/notification routing, generation transitions, and teardown.
5. Deletion and delivery proof. Remove superseded monolithic code and adapters, reconcile tests/inventories, run required validation, build/smoke distributions, and update architecture/CI documentation.

These are scope boundaries for later runtime decomposition, not a hand-authored manifest or persisted implementation plan. Keep each boundary independently reviewable and avoid a single opaque task that can report parity without module-level evidence.

## Non-goals

- Launching SKILL-424, changing an existing workflow's durable state, or implementing it during spec authoring.
- Recreating Readian's product features, authentication, network services, playback, or mobile targets.
- Adding Android/iOS artifacts or requiring mobile toolchains in this feature.
- Redesigning the desktop UI, replacing tray/native behavior, or changing Skill Bill lifecycle policy.
- A generic presentation super-module, empty architecture modules, a service locator, or copied unchecked DI registries.
- A second runtime graph, workflow engine, schema authority, private database mirror, or CLI fallback.
- Weakening runtime guards, adding baselines/exemptions, or accepting documentation as proof of implementation.
