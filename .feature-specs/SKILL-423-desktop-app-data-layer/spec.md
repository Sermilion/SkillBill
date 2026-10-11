# SKILL-423 Desktop app data layer revamp

## Outcome

The Skill Bill desktop app uses clean architecture and an in-process Room 3 or SQLDelight data layer for live work, history, details, and goal controls. Kotlin Flow observes table/query changes and drives the UI without periodic data polling. It opens the configured local durable store through supported runtime libraries. It needs no installed Skill Bill CLI, shell PATH discovery, command output parsing, or child process to fetch data or request pause and stop.

The app shows live work independently of completed history. Refresh preserves the current view and selection. A failed read shows an attributable error and retains the last successful data. A successful empty live-work query shows "All quiet" after loading completes.

This request creates the parent feature spec only. Implementation, decomposition, validation runs, installation, and publication belong to a later invocation.

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

Replace the desktop's entire work-data path, including board discovery, detail, history, pagination, freshness, controls, diagnostics, configuration, and packaged runtime dependencies. Replace the poll loop with repository observation streams backed by Room 3 or SQLDelight. Retain its UI design, tray integration, notifications, login-start behavior, and supported operating systems.

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

Apply the responsibilities, not every implementation detail. Do not copy Readian's destructive database deletion, schema ownership, broad exception catches, or unchecked casts into Skill Bill. Skill Bill's durable store and runtime architecture contracts remain authoritative.

Prefer Room 3 to match this reference. SQLDelight is an authorized alternative if a bounded compatibility proof shows it integrates better with the existing runtime-owned schema and cross-process observation. Choose one library before implementation proceeds, document the decision, and pin a verified JVM-compatible version. Do not ship parallel backends or switch libraries silently at runtime.

The compatibility proof opens a disposable database produced by current runtime migrations, observes relevant queries, receives an external committed write, and leaves schema/version/workflow state unchanged through desktop reads. Room identity metadata, generated schema requirements, triggers, connection behavior, and SQLDelight driver listener behavior must be examined rather than assumed compatible. Required durable schema additions belong to runtime migrations, with parity and compatibility tests; desktop startup does not install them.

Official technical references are [Room for KMP and JVM desktop](https://developer.android.com/kotlin/multiplatform/room) and [SQLDelight coroutine query flows](https://sqldelight.github.io/sqldelight/2.0.2/multiplatform_sqlite/coroutines/). Room's documented multi-instance invalidation is Android-only. SQLDelight's documented query flows do not by themselves establish notification from the runtime's independent SQLite writers. Cross-process observation requires explicit evidence with either library.

### Ownership and dependencies

Domain owns pure models and purpose-built repository interfaces. Application use cases depend on those interfaces. Data/infrastructure implements them with typed Room DAOs or SQLDelight queries and row-to-domain mapping. Presentation owns StateFlow view state and collects application/repository streams. No database entity, generated query type, cursor implementation, or framework database object crosses into domain or presentation. Existing desktop packages may express these boundaries without adding empty modules or pass-through use cases. The data layer integrates with a narrow typed in-process runtime API for shared projections and controls. The existing runtime composition root wires database and projection collaborators. Runtime policy remains in domain, application, or engine, with storage operations behind runtime ports.

Reuse supported runtime database adapters, lifecycle projection, identity rules, ownership evidence, and control transactions. Do not build a second interpretation of workflow tables in the desktop, construct a duplicate runtime DI graph, or expose the runtime component's entire dependency graph through a wrapper. Any new shared contract must have named production consumers or a port signature that needs it, following A1-A5 and A11-A12.

Board/history/detail return typed results and domain-facing values. The desktop does not serialize a typed result into the CLI JSON shape just to feed its old decoder. CLI and MCP retain their supported wire contracts and shared semantics.

Remove executable selection and login-shell discovery from the work-data path. Delete obsolete desktop CLI source, JSON decoders, vocabulary copies, and tests once no production consumer remains. Preserve process adapters that still serve a separate OS integration responsibility. A compatible installed CLI is not a fallback.

### Database access and configuration

Resolve the default store through the same authoritative runtime database-selection policy used by other consumers. Allow an explicit database path in desktop settings. Show the selected path and a connection/problem state so an empty store and an unavailable store are distinguishable. UI and presentation do not import JDBC, SQLite, filesystem adapters, or runtime infrastructure.

Reads and observation registration must not create a missing database, run migrations, create persistent tracking tables/triggers, repair or quarantine rows, renew leases, change lifecycle, or write workflow progress. A missing, inaccessible, incompatible, busy, or corrupt store has a distinct typed outcome and a bounded diagnostic. Existing shared read-side recovery behavior must be audited before reuse; unexpected mutation is not accepted as a consequence of embedding the runtime.

Use the runtime's supported schema compatibility checks. An incompatible store shows an explicit compatibility message and the supported repair/update route. The desktop does not silently upgrade or downgrade it. Read/write readiness remains separate, and control writes use the existing runtime readiness policy only when the operator requests a supported control.

The connection owner bounds transactions and releases connections on success, failure, cancellation, path changes, and app shutdown. Reads observe committed WAL data while runtime writers are active. Database replacement at the same path invalidates cached file identity and readiness. Switching databases invalidates cursors, selection, pending results, and notification baselines. It cannot publish a previous database's rows or fire historical completion notifications.

Migrate desktop preferences deliberately. Remove `cliOverride` from the UI and active settings model. Do not interpret an old executable path as a database path. Keep retention, notifications, login-start, and popover preferences. Retire `pollSeconds` and remove the "Check every" setting. Discarded CLI and polling preferences produce attributed migration diagnostics without repeated warnings on every launch.

### Live work, history, and query cost

Live queries cover goals and standalone work across repositories, including active, paused, blocked, and failed states according to the shared lifecycle and completion policy. They preserve planning, parent finalization, owner/liveness evidence, reasons, and stale state. Finished failures remain distinguishable from work needing an operator action through the existing completion evidence.

History has its own retention filter and pagination state. Changing history retention or loading history cannot change live membership. Recent completions shown in the tray come from a separately bounded recent-work query, not by forcing all history into the live board. Apply the current recent-phase display policy without hiding active phases.

Candidate filtering and ordering happen before expensive projection where supported by authoritative durable facts. The first live page must be able to find an older running goal behind thousands of newer completed records. Do not fix that case by having the desktop scan every history page or project every workflow before returning the first live page.

Each result page has a stable observation boundary and deterministic identity-based continuation. Empty intermediate pages cannot count as a fully empty result while eligible live candidates remain. History/detail pages merge by durable identity, report continuation explicitly, and never loop on repeated cursors. Live discovery and any candidate rechecks preserve shared projection semantics when persisted status and current ownership disagree.

Keep the existing ten-record desktop page size initially. Load more bounds each request. An invalidation revisits the already requested affected live/detail pages without fetching the entire history. Explicit refresh revalidates or resubscribes to these streams. Repository/issue/branch filtering must search the eligible query set, not just the first loaded page. Define the snapshot invalidation behavior for changes between pages and database generations.

### Flow observation and view state

Represent first load, background refresh, explicit page loading, successful empty results, stale data, and failure separately. "Loading more work" appears only when extending a list. A successful empty live query stays "All quiet" during later background refreshes. Initial loading cannot show "All quiet" before a trustworthy result exists.

Repository methods expose cold, typed `Flow` observations for live work, selected detail, history, and recent completions. Share active subscriptions in the application/presentation scope so multiple UI consumers do not create duplicate database observers. Use `flatMapLatest` or equivalent cancellation when selection, database generation, filters, or requested page bounds change. Keep at most one in-flight read per query stream. Coalesce transaction invalidations and manual refresh requests. Manual refresh must not overlap or accumulate unbounded work. All database work stays off the UI thread. Deadlines and cancellation are cooperative and leave no hidden running request or open transaction.

Selection and detail results carry request/database generations. A late result for a previous selection, setting, or database cannot replace the current view. Keep prior rows and detail during refresh, mark freshness honestly, freeze elapsed time when stale, and clear stale indicators only after a successful replacement.

Observe the tables and query dependencies required for every displayed field, including workflow/standalone state, phase progress, ownership/leases, controls, planning, and completion evidence. Emit after committed transactions; rolled-back writes cannot publish a state transition. One coupled transaction produces a coherent snapshot rather than mixed revisions. Unrelated table changes must not requery every stream.

Runtime writes occur in other processes and currently use independent SQLite sessions. Room or SQLDelight in-process listener registration alone is not proof that those writes invalidate desktop queries. Establish an event-driven cross-process invalidation boundary through the shared storage/transaction owner. Every relevant writer publishes only after commit; notifications are change hints and SQLite remains authoritative. Verify subscribe/read ordering so a commit during registration cannot be missed. Database replacement, writer crash after commit but before notification, event loss, and reconnect must have an explicit recovery protocol and evidence.

A platform event mechanism or runtime commit signal may bridge external commits into the repository's query Flow. It must work on supported desktop hosts without a new service daemon, periodic database scans, or a timer loop disguised as Flow. File/WAL notifications alone need a demonstrated commit, overflow, replacement, and reconnect protocol. If the selected library cannot supply external invalidation, implement the bridge rather than falling back to polling. Observation failure shows an attributable degraded/error state and offers resubscription/manual refresh.

Time-derived state needs separate handling. Elapsed-duration display may tick without querying SQLite. Lease expiry, recent-completion expiry, and history-window boundaries can schedule their next known deadline and invalidate affected projections once; they cannot become periodic full-board reads. Test process death without a final write and expiry without a database update so event-driven observation does not leave work falsely running forever.

### Controls and notifications

Pause and stop call the shared in-process control use cases. The write authority stays in the runtime. The desktop never edits workflow status, leases, control tables, or manifest files directly.

Revalidate database generation, repository, issue, durable goal identity, freshness, and eligible state at control admission. Reject a stale or changed target with a typed result. Preserve idempotency, fencing, pause-at-boundary behavior, stop semantics, and ownership checks. A control response means the request was accepted or rejected; it does not claim that pause or stop has already taken effect. Observe that outcome through a subsequent read.

Preserve notification settings and transition deduplication. Initial discovery, history pagination, database switching, and reconnect must not replay historical completion notifications. Notifications use confirmed shared lifecycle transitions, not refresh state changes.

### Packaging and observability

The app bundles the libraries and driver needed for its in-process layer on Linux, macOS, and Windows. It runs with the Skill Bill executable absent and PATH excluding the checkout. Use declared Gradle dependencies or composite-build integration, not jars copied from an installed runtime or sibling build output. The desktop CI workflow builds the required shared artifacts from a clean checkout.

Diagnostics follow `docs/observability-policy.md`. Record storage failures, timeouts, discarded legacy configuration, observation degradation, and stale control rejection with the query/control seam and bounded cause. Do not log database rows, agent payloads, credentials, or raw SQL parameters. A failure cannot become an empty successful board.

## Acceptance Criteria

1. Desktop launch, live work, history, detail, refresh, pause, and stop function with no Skill Bill CLI installed. No production work-data operation starts a shell or CLI process. The packaged app supplies its runtime libraries and storage driver.
2. The desktop follows the referenced clean architecture with domain repository contracts, a Room 3 or SQLDelight data implementation, row-to-domain mapping, application use cases, and presentation StateFlow. Read/control contracts are typed and shared runtime services remain supported. Status/identity/control policy has one owner, dependency edges point inward, and UI/presentation remain free of database and runtime-adapter imports. Prove allowed and rejected architecture fixtures without widening runtime guards.
3. With an older active goal and standalone phase behind at least 5,000 newer terminal records, the initial live query returns eligible live work without history-page traversal. Instrumented evidence bounds candidates projected per requested page and separates index/filter work from projection work. History retention and pagination do not change live membership.
4. Goals and phases across two repositories retain exact durable identity, lifecycle, reasons, freshness, planning, parent finalization, and liveness evidence. Shared runtime and desktop projections agree for the same observation. No terminal historical record becomes running merely because the app reads it.
5. An empty compatible database reaches "All quiet" after its initial Flow emission and keeps that view through later invalidations and explicit refreshes. Initial loading, background refresh, and load more have distinct observable states. No repeated list-loading spinner appears for background refresh alone.
6. Missing, inaccessible, busy, incompatible, corrupt, and observation-unavailable stores yield distinguishable typed failures with bounded diagnostics. A failed refresh keeps prior rows and detail visibly stale. Reads neither create nor mutate durable workflow/control/schema state.
7. Committed WAL-backed updates appear while another owned connection writes. Opening, reading, cancellation, reconnect, and closing preserve workflow rows, phase records, leases, controls, schema version, and recovery files. Same-path database replacement cannot reuse stale cursors or readiness.
8. Changing the configured database cancels old observers, rejects late results from the prior generation, clears old selection and cursors, and resets notification baselines. Legacy `cliOverride` is retired without becoming a store path; unrelated desktop preferences survive.
9. Flow invalidations and manual refresh coalesce into bounded work. No periodic data polling loop or polling configuration remains. Slow reads, cancellation, path changes, and shutdown release owned resources and never block the UI thread. Deterministic concurrency tests prove no overlapping read per stream or stale detail overwrite.
10. Live, history, recent completions, and detail have independent pagination. Identity, observation boundaries, repeated/invalid cursors, empty intermediate pages, refresh, and later-page failure have tested behavior. Search can find eligible work outside the currently loaded page.
11. Pause and stop use shared runtime transactions and revalidate the exact target at admission. Tests prove wrong-repository, stale-generation, terminal-target, duplicate request, and concurrent ownership-change behavior. Accepted control requests do not fabricate completed transitions.
12. Notification toggles and transition deduplication survive refresh and reconnect. Initial discovery, history load, store switching, and failed reads cannot generate completion notifications.
13. CLI/MCP board/detail and IDE status contracts retain compatibility and correctness wherever shared code changes. SKILL-414/417/418 and landed SKILL-422 status requirements remain intact, including legacy completion and finalization ownership.
14. Retired work-data process adapters, JSON decoders, CLI settings, polling loops/settings, and their superseded tests exist in no desktop source set unless a named remaining production responsibility needs them. Architecture docs and CI describe the resulting in-process layer. A source/consumer inventory proves deletion.

15. A runtime write from an independently started process updates subscribed live/detail/history Flow results without manual refresh, periodic reads, or writes through the desktop's DAO. Tests also prove rollback silence, coherent multi-table commit emission, registration-race recovery, reconnect, notification loss, and process-crash recovery. DAO-only same-process tests cannot satisfy this criterion.
16. The implementation chooses and pins Room 3 or SQLDelight after the schema/driver/external-invalidation proof. Readian reference patterns and necessary deviations are recorded. No destructive ORM migration, second schema authority, private mirror, or parallel database backend is introduced.
17. Elapsed-time display and scheduled ownership/retention deadlines work without polling the data store. Tests cover owner death and lease expiry without further writes, recent-completion expiry, and history cutoff changes.

## Validation strategy

Use disposable SQLite databases and explicit disposable environment/home bindings. Every regression names the realistic bug it catches. Avoid production mutations, blanket status resets, deleting WAL/SHM files, or copying a live database without its supported backup mechanism.

Prioritize tests through real repository and shared service boundaries for completed-history starvation, empty-refresh flicker, mutation-free reads, WAL visibility, same-path replacement, schema refusal, selection races, and stale controls. Use controlled clocks and barriers for concurrency. Use a separate owned process with the real runtime storage path for external invalidation; writing through the same Room/SQLDelight instance is insufficient. Verify no read occurs during an idle observation interval, then commit and assert the Flow emission. Test cancellation and subscriber cleanup through the production driver. A SQL fixture alone does not prove shared lifecycle projection or control admission.

Run `cd desktop-tray && ./gradlew clean check --no-build-cache` and the affected runtime module tests and architecture suites. Shared runtime changes also require the repository's full runtime validation commands discovered from instructions and CI, including `cd runtime-kotlin && ./gradlew check`. Build a clean desktop distribution and smoke it against a disposable store with CLI/PATH discovery disabled. Verify packaged dependency loading on each supported host, recording any host that was not exercised.

Capture query work counts for the 5,000-terminal-record fixture, resource cleanup, and functional outcomes. Wall-clock timings are diagnostic evidence, not the sole pass/fail proof. Review runtime changes against architecture-guidelines section 5 with explicit A1-A12 and G1-G7 results.

## Suggested implementation boundaries

1. Room 3/SQLDelight compatibility decision, shared typed read/control access, bounded live/history queries, and event-driven cross-process invalidation. Own runtime projection/query changes, compatibility coverage, and storage/lifecycle invariants.
2. Readian-style clean architecture, Flow repositories, independent subscriptions, settings migration, view states, selection, controls, and notification behavior. Delete the retired CLI work-data path in the same change.
3. Packaged shared dependencies, CI, isolated distribution smoke tests, performance evidence, and updated architecture documentation.

These are scope boundaries for later decomposition, not an authored runtime manifest or saved implementation plan. Keep the existing runtime composition root and manifest-driven behavior. Do not add a second listed skill, desktop workflow engine, service daemon, or database authority.

## Non-goals

- Changing the window design, tray platform adapters, login-start provider, or unrelated OS process integration.
- Reimplementing workflow execution, planning, migrations, telemetry, or lifecycle policy in the desktop.
- A new remote server, sync protocol, private database copy, or desktop-maintained lifecycle mirror.
- Automatically repairing production state or treating read compatibility failure as an install/update command.
- Completing the broader SKILL-422 workflow-state audit as part of this feature.
