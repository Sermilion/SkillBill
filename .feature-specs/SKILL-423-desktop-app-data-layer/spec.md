# SKILL-423 Desktop app data layer revamp

## Outcome

The Skill Bill desktop app uses an in-process Room 3 data layer for live work, history, details, and goal controls. Kotlin Flow observes table/query changes and drives the UI without periodic data polling. It opens the configured local durable store through supported runtime libraries. The work-data path is independent of the CLI. It needs no installed Skill Bill CLI, runtime distribution, shell PATH discovery, or command output parsing at runtime. Work reads, controls, and settings use bundled libraries or platform APIs. SKILL-424 owns final application architecture and native desktop integration.

The app shows live work independently of completed history. Refresh preserves the current view and selection. A failed read shows an attributable error and retains the last successful data. A successful empty live-work query shows "All quiet" after loading completes.

## Relationship to SKILL-424

### Operator scope transfer on 2026-10-12

The operator transferred the seven remaining reported repair obligations to [SKILL-424](../SKILL-424-readian-desktop-architecture-parity/spec.md#data-repair-handoff-from-skill-423) and requested completion of the current `audit_implement_fix` phase by handoff. SKILL-424 AC-28 through AC-34 now own the remaining portions of normalized SKILL-423 AC-002, AC-003, AC-004, AC-009, AC-010, AC-011, and AC-017, respectively. Preserve implemented data behavior and all existing repairs. The transferred obligations stay required in SKILL-424 and are excluded from further SKILL-423 repair acceptance.

This decision supersedes earlier instructions below to finish those remaining portions in SKILL-423, including the historical remaining-repairs section and saved implementation plan. It does not claim that the transferred gaps were fixed or passed a fresh audit. The source is workflow `wftr-20261012-112951-so8l`, repair attempt 8, with the latest report at `2026-10-12T16:02:56.524Z`. That report may contain stale descriptions; SKILL-424 reconciles them against current source. AC-005, AC-012, and AC-015 were reported satisfied and their behavior remains preserved.

Phase completion means the operator-approved repair handoff is complete. Review, build, validation, commit, publication, and goal completion retain their runtime-owned requirements. No manifest or workflow database status is edited by this specification.

[SKILL-424](../SKILL-424-readian-desktop-architecture-parity/spec.md) owns the final Readian-style KMP module/source-set graph, desktop build conventions, generated Kotlin-Inject/Anvil composition and scopes, feature ViewModels and typed state/action/effect structure, Navigation 3, entry/window ownership, native host integration, and final architecture guards/inventories.

SKILL-423 owns working data-layer behavior. Room 3 compatibility, typed shared runtime reads and controls, bounded queries and Paging, external committed-write observation, database generations, DataStore migration, notification correctness, and removal of the CLI work-data path and polling remain requirements here. SKILL-424 consumes and preserves those implementations while placing them in its final architecture.

Integrate this data layer into the current desktop UI with the smallest required consumer changes. Existing controller/window structure may remain if it satisfies these data behaviors. Do not require the complete KMP module graph, generated app DI, feature ViewModel rewrite, or Navigation 3 to complete SKILL-423. Keep portable repository contracts and narrow JVM runtime adapters ready for relocation, without adding placeholder modules, duplicate runtime graphs, or a temporary database backend. If SKILL-424 has already changed the structure, use its real owners rather than recreating old paths.

Preserve existing edits and completed repairs. The unfinished subtask spec carries this same reduced scope. Its former all-app implementation plan is superseded; a subsequent full-run resume must reconcile spec drift and replan through the runtime. This spec edit does not alter workflow rows, retry budgets, completion status, or the manifest.

## Problem and observed evidence

On 2026-10-11 the desktop window showed zero items under Needs you, Goals running, and Standalone phases. It repeatedly showed "Loading more work..." and "Loading..." while the detail area said "All quiet".

The running app's persisted settings selected a source-built runtime CLI, a five-second poll interval, and seven-day history retention. Its first ten-record board page contained only terminal work and a continuation cursor. A read-only traversal of this observation reached ten pages, with 93 retained records and no nonterminal work. This observation does not establish that live work was missing. It does establish that history drove the board traversal and that repeated refresh appeared as loading more work even when the live view stayed empty.

The installed launcher separately rejected `--page-size`. The running app used its configured source-built CLI instead, so the installed version mismatch was not the cause of this screenshot. It is another dependency failure that this revamp must remove.

## Current production paths

- `desktop-tray/src/main/kotlin/dev/skillbill/tray/infrastructure/cli/CliBoardSource.kt` launches board, detail, pause, and stop commands and parses their output.
- `desktop-tray/src/main/kotlin/dev/skillbill/tray/infrastructure/cli/CliResolver.kt` discovers executables through overrides, PATH, and a login shell.
- `desktop-tray/src/main/kotlin/dev/skillbill/tray/infrastructure/cli/ProcessRunner.kt` owns process slots, capture, and request deadlines.
- `desktop-tray/src/main/kotlin/dev/skillbill/tray/application/Ports.kt` combines reads, controls, executable configuration, and refresh signaling in `BoardSource`.
- `desktop-tray/src/main/kotlin/dev/skillbill/tray/application/DashboardController.kt` polls, paginates, selects details, tracks freshness, and sends controls.
- `desktop-tray/src/main/kotlin/dev/skillbill/tray/ui/MainWindow.kt` filters board records into live sidebar sections. History uses the same board pages through `TrayUi.kt`.
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/IdeStatusService.kt` owns the shared board/detail projection. Board pagination sorts candidates by start time and identity before projecting the selected page.
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/status/GoalRunnerStatusService.kt` exposes existing goal status and pause/stop behavior.
- `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/core/RuntimeComponent.kt` is the runtime composition root. `runtime-ports` and `runtime-infra/sqlite` own database session contracts and their implementation.

Recheck these owners against the implementation checkout. SKILL-422 audits workflow correctness across shared projections; retain its landed repairs and coordinate overlapping changes without making this feature depend on that audit finishing.

## Scope

Replace the desktop's work-data path, including board discovery, detail, history, pagination, freshness, controls, diagnostics, database configuration, and packaged data/runtime dependencies. Replace the poll loop with repository observation streams backed by Room 3. Retain the existing UI design and tray, notification, login-start, and supported-host behavior. Use DataStore for persistent preferences.

### Application architecture handoff

SKILL-424 owns the desktop-first KMP conversion and final populated core/feature modules. It also owns common-source presentation, generated constructor-injection wiring, shared feature ViewModels, Navigation 3, per-window/entry lifetimes, and their architecture proofs. None is a completion prerequisite for SKILL-423.

SKILL-423 still separates pure models and repository contracts from storage implementation and UI consumers. Inject time, dispatchers, storage paths, and host boundaries where required by data behavior. Keep runtime projection/control integration behind a narrow JVM adapter and retain the existing runtime composition root. Own connection and observation lifetimes explicitly even before SKILL-424 places them in generated app/session scopes. Shared runtime architecture rules remain mandatory.

Room, Paging, and DataStore setup may use the current desktop build. Pin dependencies there and expose typed contracts without JVM/storage objects in domain or presentation. SKILL-424 relocates these working implementations into its final common/JVM source sets and conventions. No Android or iOS implementation belongs to this feature.

### Reference architecture and database technology

Use `/home/sermilion/StudioProjects/readian-android` as the implementation reference. The inspected tree uses Room 3, with `room3 = "3.0.3"` in `gradle/libs.versions.toml`. Reference patterns are:

| Reference path relative to readian-android | Pattern to apply |
| --- | --- |
| `docs/architecture.md` | Pure domain models and repository contracts, concrete data implementations, and presentation state |
| `core/domain/src/commonMain/kotlin/news/readian/readiannews/core/domain/subscriptions/repository/TopicsRepository.kt` | Repository observation methods returning domain `Flow` values and separate suspend commands |
| `core/data/src/commonMain/kotlin/news/readian/readiannews/core/data/subscriptions/datasource/local/room/TopicRoomDao.kt` | Typed DAO query flows with bounded explicit queries |
| `core/data/src/commonMain/kotlin/news/readian/readiannews/core/data/subscriptions/repository/ReadianTopicsRepository.kt` | Row-to-domain mapping in repository implementations |
| `core/data/src/commonMain/kotlin/news/readian/readiannews/core/data/db/room/ReadianRoomDatabaseProvider.kt` | Injected database provider with explicit instance lifetime and closure |
| `core/data/src/jvmMain/kotlin/news/readian/readiannews/core/data/db/room/PlatformRoomDatabaseBuilderFactory.jvm.kt` | JVM-specific database construction outside domain and presentation |
| `core/datastore/src/commonMain/kotlin/news/readian/readiannews/core/datastore/settings/AppSettingsStore.kt` | Typed preference Flow and atomic DataStore updates |

Apply the responsibilities, not every implementation detail. Do not copy Readian's destructive database deletion, schema ownership, broad exception catches, or unchecked casts into Skill Bill. Skill Bill's durable store and runtime architecture contracts remain authoritative.

Room 3 is the database library. Pin a verified multiplatform/JVM-compatible Room 3 version and its compiler/driver dependencies through the desktop version catalog and applicable build configuration. SKILL-424 owns final convention-plugin placement. The library choice does not transfer ownership of Skill Bill's schema or control transactions to the desktop.

The compatibility proof opens a disposable database produced by current runtime migrations, observes relevant queries, receives an external committed write, and leaves schema/version/workflow state unchanged through desktop reads. Room identity metadata, generated schema requirements, triggers, connection behavior, and invalidation behavior must be examined rather than assumed compatible. Required durable schema additions belong to runtime migrations, with parity and compatibility tests; desktop startup does not install them.

The [official Room KMP documentation](https://developer.android.com/kotlin/multiplatform/room) describes JVM desktop construction and common query Flow APIs. Its documented multi-instance invalidation is Android-only. Cross-process observation on desktop requires explicit evidence through Skill Bill's external invalidation bridge.

### Ownership and dependencies

Domain owns pure models and purpose-built repository interfaces, following Readian's repository pattern. Application use cases depend on those interfaces. Data/infrastructure implements them with typed Room DAOs and row-to-domain mapping. Presentation owns StateFlow view state and collects application/repository streams. No Room entity, DAO, concrete cursor implementation, or database object crosses into domain or presentation. Portable Paging contracts may be exposed as in Readian; storage types stay in data. Keep these data boundaries explicit in the current project. SKILL-424 owns their final module and source-set placement. Do not add empty modules or pass-through use cases. The data layer integrates with a narrow typed in-process runtime API for shared projections and controls. The existing runtime composition root wires database and projection collaborators. Runtime policy remains in domain, application, or engine, with storage operations behind runtime ports.

Reuse supported runtime database adapters, lifecycle projection, identity rules, ownership evidence, and control transactions. Do not build a second interpretation of workflow tables in the desktop, construct a duplicate runtime DI graph, or expose the runtime component's entire dependency graph through a wrapper. Any new shared contract must have named production consumers or a port signature that needs it, following A1-A5 and A11-A12.

Board/history/detail return typed results and domain-facing values. The desktop does not serialize a typed result into the CLI JSON shape just to feed its old decoder. CLI and MCP retain their supported wire contracts and shared semantics.

Remove executable selection and login-shell discovery from the work-data path. Delete obsolete desktop CLI source, JSON decoders, vocabulary copies, and tests once no production consumer remains. The work-data path never falls back to an installed CLI or external command-line utility. SKILL-424 owns replacing command-based OS helpers for monitor discovery, notifications, tray integration, and login-start; preserve their current behavior during this data change. Build-time compilers and packaging tools are outside this runtime constraint.

### Database access and configuration

Resolve the default store through the same authoritative runtime database-selection policy used by other consumers. Allow an explicit database path in desktop settings. Show the selected path and a connection/problem state so an empty store and an unavailable store are distinguishable. UI and presentation do not import JDBC, SQLite, filesystem adapters, or runtime infrastructure.

Reads and observation registration must not create a missing database, run migrations, create persistent tracking tables/triggers, repair or quarantine rows, renew leases, change lifecycle, or write workflow progress. A missing, inaccessible, incompatible, busy, or corrupt store has a distinct typed outcome and a bounded diagnostic. Existing shared read-side recovery behavior must be audited before reuse; unexpected mutation is not accepted as a consequence of embedding the runtime.

Use the runtime's supported schema compatibility checks. An incompatible store shows an explicit compatibility message and the supported repair/update route. The desktop does not silently upgrade or downgrade it. Read/write readiness remains separate, and control writes use the existing runtime readiness policy only when the operator requests a supported control.

The connection owner bounds transactions and releases connections on success, failure, cancellation, path changes, and app shutdown. Reads observe committed WAL data while runtime writers are active. Database replacement at the same path invalidates cached file identity and readiness. Switching databases invalidates cursors, selection, pending results, and notification baselines. It cannot publish a previous database's rows or fire historical completion notifications.

Migrate desktop preferences deliberately. Remove `cliOverride` from the UI and active settings model. Do not interpret an old executable path as a database path. Keep retention, notifications, login-start, and popover preferences. Migrate preferences from `java.util.prefs.Preferences` into DataStore. Retire `pollSeconds` and remove the "Check every" setting. Discarded CLI and polling preferences produce attributed migration diagnostics without repeated warnings on every launch.

### Live work, history, and query cost

Live queries cover goals and standalone work across repositories, including active, paused, blocked, and failed states according to the shared lifecycle and completion policy. They preserve planning, parent finalization, owner/liveness evidence, reasons, and stale state. Finished failures remain distinguishable from work needing an operator action through the existing completion evidence.

History has its own retention filter and pagination state. Changing history retention or loading history cannot change live membership. Recent completions shown in the tray come from a separately bounded recent-work query, not by forcing all history into the live board. Apply the current recent-phase display policy without hiding active phases.

Candidate filtering and ordering happen before expensive projection where supported by authoritative durable facts. The first live page must be able to find an older running goal behind thousands of newer completed records. Do not fix that case by having the desktop scan every history page or project every workflow before returning the first live page.

Each result page has a stable observation boundary and deterministic identity-based continuation. Empty intermediate pages cannot count as a fully empty result while eligible live candidates remain. History/detail pages merge by durable identity, report continuation explicitly, and never loop on repeated cursors. Live discovery and any candidate rechecks preserve shared projection semantics when persisted status and current ownership disagree.

Keep the existing ten-record desktop page size initially. Load more bounds each request. An invalidation revisits the already requested affected live/detail pages without fetching the entire history. Explicit refresh revalidates or resubscribes to these streams. Repository/issue/branch filtering must search the eligible query set, not just the first loaded page. Define the snapshot invalidation behavior for changes between pages and database generations.

### List pagination

Every potentially growing list uses database-backed pagination. This includes Needs you, Goals running, Standalone phases, History, and detail lists for subtasks, phase records, execution history, and validation evidence. Sidebar sections have independent query and paging state so one section cannot consume another section's first page. Fixed pipeline metadata and deliberately capped tray previews may remain bounded lists with an explicit way to open the full paginated list.

Use Readian's repository paging pattern as the reference. Use its `Pager` and `Flow<PagingData<...>>` approach through multiplatform-compatible AndroidX Paging with Room 3. Domain repository contracts may expose portable PagingData of domain values, matching Readian, while PagingSource/DAO/entity implementations remain in data. Verify common/JVM dependency support and pagination invalidation in the compatibility proof. Any necessary departure needs a recorded reason and equivalent acceptance tests.

Apply filtering and ordering in the database before the page boundary. Use deterministic ordering with a unique durable-identity tie-breaker and indexed queries. Prefer keyset continuation for changing work lists; any offset-based implementation must demonstrate correct invalidation under concurrent inserts and deletes. Never load the entire result into memory and slice it for display. Counts, when displayed, come from separate scoped queries rather than materializing every row.

Load the first page on subscription and append only when the user approaches the end or selects Load more. Coalesce duplicate append requests, stop at end of data, and support retry for the failed page without discarding previous rows. Bound retained pages, observers, and database work. Preserve selection and a usable scroll anchor across Flow invalidation. A changed filter, sort, database, or retention query starts a new paging generation and rejects results from the old generation.

Concurrent inserts, updates, and deletes invalidate affected page generations coherently. A refreshed sequence cannot mix incompatible snapshots, duplicate records, omit records permanently, or retain removed rows indefinitely. Page failures and end-of-data have separate UI states. Repeated invalidation must not reset scroll to the top or issue unbounded append requests.

### Flow observation and view state

Represent first load, background refresh, explicit page loading, successful empty results, stale data, and failure separately. "Loading more work" appears only when extending a list. A successful empty live query stays "All quiet" during later background refreshes. Initial loading cannot show "All quiet" before a trustworthy result exists.

Repository methods expose cold, typed `Flow` observations for live work, selected detail, history, and recent completions. Share active subscriptions in the application/presentation scope so multiple UI consumers do not create duplicate database observers. Use `flatMapLatest` or equivalent cancellation when selection, database generation, filters, or requested page bounds change. Keep at most one in-flight read per query stream. Coalesce transaction invalidations and manual refresh requests. Manual refresh must not overlap or accumulate unbounded work. All database work stays off the UI thread. Deadlines and cancellation are cooperative and leave no hidden running request or open transaction.

Selection and detail results carry request/database generations. A late result for a previous selection, setting, or database cannot replace the current view. Keep prior rows and detail during refresh, mark freshness honestly, freeze elapsed time when stale, and clear stale indicators only after a successful replacement.

Observe the tables and query dependencies required for every displayed field, including workflow/standalone state, phase progress, ownership/leases, controls, planning, and completion evidence. Emit after committed transactions; rolled-back writes cannot publish a state transition. One coupled transaction produces a coherent snapshot rather than mixed revisions. Unrelated table changes must not requery every stream.

Runtime writes occur in other processes and currently use independent SQLite sessions. Room 3 in-process listener registration alone is not proof that those writes invalidate desktop queries. Establish an event-driven cross-process invalidation boundary through the shared storage/transaction owner. Every relevant writer publishes only after commit; notifications are change hints and SQLite remains authoritative. Verify subscribe/read ordering so a commit during registration cannot be missed. Database replacement, writer crash after commit but before notification, event loss, and reconnect must have an explicit recovery protocol and evidence.

A platform event mechanism or runtime commit signal may bridge external commits into the repository's query Flow. It must work on supported desktop hosts without a new service daemon, periodic database scans, or a timer loop disguised as Flow. File/WAL notifications alone need a demonstrated commit, overflow, replacement, and reconnect protocol. If the selected library cannot supply external invalidation, implement the bridge rather than falling back to polling. Observation failure shows an attributable degraded/error state and offers resubscription/manual refresh.

Time-derived state needs separate handling. Elapsed-duration display may tick without querying SQLite. Lease expiry, recent-completion expiry, and history-window boundaries can schedule their next known deadline and invalidate affected projections once; they cannot become periodic full-board reads. Test process death without a final write and expiry without a database update so event-driven observation does not leave work falsely running forever.

### Controls and notifications

Pause and stop call the shared in-process control use cases. The write authority stays in the runtime. The desktop never edits workflow status, leases, control tables, or manifest files directly.

Revalidate database generation, repository, issue, durable goal identity, freshness, and eligible state at control admission. Reject a stale or changed target with a typed result. Preserve idempotency, fencing, pause-at-boundary behavior, stop semantics, and ownership checks. A control response means the request was accepted or rejected; it does not claim that pause or stop has already taken effect. Observe that outcome through a subsequent read.

Preserve notification settings and transition deduplication. Initial discovery, history pagination, database switching, and reconnect must not replay historical completion notifications. Notifications use confirmed shared lifecycle transitions, not refresh state changes.

### DataStore preferences

Use DataStore for durable app settings, following Readian's `core:datastore` and `AppSettingsStore`. Expose typed settings through Flow and use atomic updates. Keep database path, retention/filter preferences where persisted, notification toggles, login-start choice, and last-view preferences separate from workflow storage. Persist neither live workflow state nor a database mirror in DataStore. Own one DataStore instance per file and inject platform storage paths.

Migrate legacy JVM preferences once, preserving supported values. A migration/restart cannot overwrite newer DataStore choices or repeatedly reset settings. Corrupt/unreadable settings have explicit recovery/error behavior and diagnostics; do not silently discard an existing file. Separate preference serialization from platform construction. SKILL-424 owns final common/JVM placement and generated settings composition. Verify concurrent updates, cancellation, and restart persistence.

### Selection and navigation handoff

SKILL-423 preserves selection and a usable scroll anchor during refresh and Paging invalidation. Database generations reject old detail/results and clear obsolete selection. Those behaviors must work through current UI consumers.

SKILL-424 owns typed Navigation 3 routes, saved back stacks, entry/ViewModelStore ownership, restart route restoration, tray/notification routing, and independent window navigation. SKILL-423 does not rewrite the navigation host or window structure to prove data-layer completion.

### Packaging and observability

Package the runtime libraries and driver needed for the in-process data layer on Linux, macOS, and Windows. SKILL-424 owns final application-build conventions, native host resource placement, and host packaging integration. The app runs with the Skill Bill executable absent and PATH excluding the checkout. Use declared Gradle dependencies or composite-build integration, not jars copied from an installed runtime or sibling build output. The desktop CI workflow builds the required shared artifacts from a clean checkout.

Diagnostics follow `docs/observability-policy.md`. Record storage failures, timeouts, discarded legacy configuration, observation degradation, and stale control rejection with the query/control seam and bounded cause. Do not log database rows, agent payloads, credentials, or raw SQL parameters. A failure cannot become an empty successful board.

## Acceptance Criteria


Only the eleven criteria below are active SKILL-423 acceptance requirements. Source IDs remain traceable; a normalized runtime projection must include only these retained requirements. The transferred criteria are not active acceptance items and must not be reconstructed from historical scope or planning prose. Preserve existing implementation and regression coverage while SKILL-424 owns further acceptance of the transferred behavior.

1. AC-001. Desktop work reads, history, detail, controls, and preference access work with no Skill Bill CLI or installed runtime distribution. The work-data path starts no shell, Skill Bill command, or command-line utility. The packaged app supplies its data/runtime libraries and storage driver. Native OS-helper replacement and final host architecture belong to SKILL-424.
5. AC-005. An empty compatible database reaches "All quiet" after its initial Flow emission and keeps that view through later invalidations and explicit refreshes. Initial loading, background refresh, and load more have distinct observable states. No repeated list-loading spinner appears for background refresh alone.
6. AC-006. Missing, inaccessible, busy, incompatible, corrupt, and observation-unavailable stores yield distinguishable typed failures with bounded diagnostics. A failed refresh keeps prior rows and detail visibly stale. Reads neither create nor mutate durable workflow/control/schema state.
7. AC-007. Committed WAL-backed updates appear while another owned connection writes. Opening, reading, cancellation, reconnect, and closing preserve workflow rows, phase records, leases, controls, schema version, and recovery files. Same-path database replacement cannot reuse stale cursors or readiness.
8. AC-008. Changing the configured database cancels old observers, rejects late results from the prior generation, clears old selection and cursors, and resets notification baselines. Legacy `cliOverride` is retired without becoming a store path; unrelated desktop preferences survive.
12. AC-012. Notification toggles and transition deduplication survive refresh and reconnect. Initial discovery, history load, store switching, and failed reads cannot generate completion notifications.
13. AC-013. CLI/MCP board/detail and IDE status contracts retain compatibility and correctness wherever shared code changes. SKILL-414/417/418 and landed SKILL-422 status requirements remain intact, including legacy completion and finalization ownership.
14. AC-014. Retired work-data process adapters, JSON decoders, CLI settings, polling loops/settings, and their superseded tests have no desktop consumers and are deleted. Data-layer docs and CI describe the in-process dependencies. A source/consumer inventory proves deletion. Global-controller removal, native OS-command helpers, and final module/navigation inventories belong to SKILL-424.
15. AC-015. A runtime write from an independently started process updates subscribed live/detail/history Flow results without manual refresh, periodic reads, or writes through the desktop's DAO. Tests also prove rollback silence, coherent multi-table commit emission, registration-race recovery, reconnect, notification loss, and process-crash recovery. DAO-only same-process tests cannot satisfy this criterion.
16. AC-016. The implementation uses pinned Room 3 dependencies after the schema/driver/external-invalidation proof. Readian reference patterns and necessary deviations are recorded. No destructive ORM migration, second schema authority, private mirror, or parallel database backend is introduced.
20. AC-020. DataStore owns persistent app preferences through typed Flow and atomic updates. Disposable tests prove one-time legacy migration, concurrent-update preservation, restart persistence, corrupt-file behavior, and no duplication of workflow state. SKILL-424 owns final module/source-set placement, generated instance wiring, and feature settings presentation.

## Transferred criteria

Original AC-018, AC-019, and AC-021 belong to SKILL-424 for the KMP graph, application architecture/DI/feature ownership, and Navigation 3. On 2026-10-12 the operator also transferred source criteria AC-002, AC-003, AC-004, AC-009, AC-010, AC-011, and AC-017 to SKILL-424 AC-28 through AC-34. These seven source criteria are removed from active SKILL-423 acceptance. Their remaining obligations stay required in the receiving spec; implemented behavior and regression coverage are preserved. This is a scope transfer, not a passing audit verdict. The eleven remaining source criteria keep their original IDs, including AC-020 for DataStore.

## Validation strategy

Use disposable SQLite databases and explicit disposable environment/home bindings. Every regression names the realistic bug it catches. Avoid production mutations, blanket status resets, deleting WAL/SHM files, or copying a live database without its supported backup mechanism.

Prioritize tests through real repository and shared service boundaries for completed-history starvation, empty-refresh flicker, mutation-free reads, WAL visibility, same-path replacement, schema refusal, selection races, and stale controls. Use controlled clocks and barriers for concurrency. Use a separate owned process with the real runtime storage path for external invalidation; writing through the same Room 3 instance is insufficient. Verify no read occurs during an idle observation interval, then commit and assert the Flow emission. Test cancellation and subscriber cleanup through the production driver. A SQL fixture alone does not prove shared lifecycle projection or control admission.

Run `cd desktop-tray && ./gradlew clean check --no-build-cache` and the affected runtime module tests and architecture suites. Shared runtime changes also require the repository's full runtime validation commands discovered from instructions and CI, including `cd runtime-kotlin && ./gradlew check`. Run affected desktop data/repository/UI-consumer tests and DataStore migration tests. Discover exact task paths from the current build. If SKILL-424 has landed modules, run affected common/JVM checks through those owners. Complete KMP graph, convention-generation, feature ViewModel, Navigation 3, and window/entry architecture proofs belong to SKILL-424. Build a clean desktop distribution and smoke it against a disposable store with CLI/PATH discovery disabled. Verify packaged dependency loading on each supported host, recording any host that was not exercised.

Capture query work counts for the 5,000-terminal-record fixture, resource cleanup, and functional outcomes. Wall-clock timings are diagnostic evidence, not the sole pass/fail proof. Review runtime changes against architecture-guidelines section 5 with explicit A1-A12 and G1-G7 results.

## Suggested implementation boundaries

1. Room 3 schema/driver compatibility proof, shared typed read/control access, bounded live/history/detail queries, external invalidation, and storage/lifecycle invariants.
2. Working Room/Paging repositories, database generations, typed failures, minimal Flow-driven UI integration, DataStore migration, selection, controls, and notification correctness. Delete the retired CLI work-data path and polling in the same change.
3. Packaged data/runtime dependencies, CI inputs, isolated distribution smoke, query/resource evidence, and updated data-layer documentation.

These boundaries guide the runtime's next scoped plan. They do not authorize manifest or workflow-state edits. SKILL-424 owns final module relocation, generated DI, feature presentation architecture, Navigation 3, native OS integration, and architecture delivery proof. Keep the existing runtime composition root and manifest-driven behavior.

## Repair handoff status

The operator-approved transfer is recorded in SKILL-424 AC-28 through AC-34. The former remaining-repair checklist is superseded and no longer authorizes SKILL-423 to implement the deferred obligations. The current tree and prior repairs remain preserved. The acceptance projection must derive from the eleven active criteria above before any further audit or repair. Phase completion records the handoff; goal completion still requires its runtime-owned delivery phases.

On 2026-10-12, workflow `wftr-20261012-172346-o3of` still carried an eighteen-criterion briefing after the spec changed. The operator stopped the run. The handoff settlement was recorded, then supported abandon and scoped replan commands retired that stale child and cleared its planning artifacts. The goal is paused at `pending_launch` with no active agent. A future launch must derive eleven criteria from the current numbered acceptance lists. Retiring this obsolete repair does not create a passing audit verdict.

Build verification after retirement passed on Linux. `cd runtime-kotlin && ./gradlew check --no-build-cache --no-configuration-cache` passed, including runtime architecture and agent-config validation. `cd desktop-tray && ./gradlew clean check createDistributable --no-build-cache --no-configuration-cache` passed with 41 tests and zero failures. `python3 scripts/packaged-data-smoke.py` passed from `desktop-tray` against the final distribution with CLI discovery disabled. These checks do not replace acceptance auditing or complete the goal.

## Non-goals

- Final Readian-style KMP module/source-set conversion, desktop convention build, generated app/session/entry DI, feature ViewModel architecture, Navigation 3, and their architecture guards. SKILL-424 owns them.
- Replacement of native OS command helpers and final host integration/package structure. SKILL-424 owns them; in-process data dependencies remain in scope here.
- Redesigning the window or changing tray/notification/login-start product behavior. Removing work-data command dependencies remains in scope; host relocation belongs to SKILL-424.
- Reimplementing workflow execution, planning, migrations, telemetry, or lifecycle policy in the desktop.
- A new remote server, sync protocol, private database copy, or desktop-maintained lifecycle mirror.
- Automatically repairing production state or treating read compatibility failure as an install/update command.
- Completing the broader SKILL-422 workflow-state audit as part of this feature.
- Android/iOS targets, mobile packaging, mobile runtime transport, or a separate mobile project in this feature. Mobile will be added later in the same KMP project.
