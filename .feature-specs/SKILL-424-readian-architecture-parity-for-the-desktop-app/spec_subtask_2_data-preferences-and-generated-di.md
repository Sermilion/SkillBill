# SKILL-424 Subtask 2 - Data, preferences, and generated DI

Parent spec: [.feature-specs/SKILL-424-readian-architecture-parity-for-the-desktop-app/spec.md](./spec.md)
Issue key: SKILL-424

## Scope

Place data and preference ownership in `core:data` and `core:datastore`, and wire app and database-session composition through generated Kotlin-Inject/Anvil components. Move Room declarations, repository implementations, SQL paging, mapping, session lifecycle, and the JVM runtime adapter into `core:data`. Move preference serialization, the typed DataStore, legacy migration, and the JVM preference-path builder into `core:datastore`. Preserve SKILL-423's implemented data behavior.

Parent sections: Module responsibilities and dependencies; Dependency injection and resource ownership; Data and preference integration; Reference evidence and precedence (data, datastore, and component rows).

## Acceptance Criteria

1. `core:data` owns repository/Room/Paging mapping. Each growing list keeps independent database-backed paging with ten-record initial pages, bounded retained pages, deterministic continuation, page retry, and query-level filtering. Real repository tests catch full-table in-memory slicing, history starvation, and one list consuming another's first page (parent AC-19).
2. `core:datastore` owns one typed DataStore instance per preference file with atomic `updateData`, a one-time legacy migration that discards `cliOverride` and `pollSeconds` with attributed diagnostics, and readiness published separately from defaults. Corruption and update failures are typed, observable outcomes. Restart, concurrent-update, and migration tests prove it (parent AC-20).
3. The desktop runtime projection/control integration lives in JVM source sets and uses the supported shared runtime graph. Reads stay read-only and never create, migrate, or repair the store. Tests on disposable runtime-produced databases snapshot schema and workflow state before and after (parent AC-21).
4. Cross-process committed updates reach live, detail, and history subscriptions without periodic data polling. Cancelling a subscriber releases its observers. An independently started writer proves it (parent AC-22).
5. Generated Kotlin-Inject/Anvil app and database-session components create the repository and preference collaborators. No service locator, reflection registry, constructor-default production collaborator, or hand-built duplicate runtime graph remains. Clean generation from empty build outputs plus a composition smoke prove it (parent AC-07 app/session part).
6. The `core:data` and `core:datastore` dependency edges match the parent's allowed table, and the architecture guards cover them with rejection fixtures.

## Execution rule

This subtask has no manifest dependencies. It runs on the current tree. It creates any missing module, convention, or contract its criteria need, keeps a change that is already present, and applies its rule to whatever anchors exist, whether moved, renamed, or deleted. When it moves or deletes a symbol, it updates every caller wherever that caller lives. It leaves `desktop-tray` configuring, compiling, and passing `./gradlew check`; it never lands a half-migrated tree that only a later subtask can repair.

The parent spec is authoritative for module ownership, allowed dependencies, lifetimes, reference patterns, and non-goals. Read its sections named under Scope before changing code. The Readian reference root is `/Users/braian.gapur/StudioProjects/readian-android`; record its current HEAD and any drift from the recorded `36d1dd9f902a7ab8b7eb2117e898e911fe51120d` before adopting a pattern.

## Non-goals

- SKILL-423 handoff obligations AC-28 to AC-34 beyond preserving behavior already present.
- Feature presentation, Navigation 3, or window ownership.
- Any parent non-goal.

## Validation Strategy

Run the desktop `./gradlew clean check --no-build-cache`, KSP generation from empty outputs, and storage tests against disposable runtime-produced databases and disposable preference files. Never touch production databases or WAL/SHM files.

## Implementation Details

All facts below come from the preplan digest. Statements marked **Assumption** were not in the digest. Implement must confirm each one against the tree and record any correction in its phase output. Paths are relative to `desktop-tray/`, and the package prefix is `dev.skillbill.tray`.

### Settled decisions

- **Portable time.** Common code carries time as an epoch-millis value type owned by `core:common`, for example `EpochMillis(value: Long)`. Conversion to and from `java.time.Instant` happens only in `jvmMain`. No `kotlinx-datetime` dependency is added. If subtask 1 has already landed a portable time type, use that one and add no second type.
- **DI flavour.** Use Kotlin-Inject constructor injection with kotlin-inject-anvil: `@MergeComponent(AppScope::class)`, `@SingleIn`, `@ContributesBinding`, and `@ContributesSubcomponent` plus its factory for the database session.
  - **Assumption:** the "Anvil" in the parent is kotlin-inject-anvil. Subtask 1 or this subtask pins its coordinates in the catalog.
  - An Anvil/KSP pair that is incompatible with Kotlin 2.4.21 and KSP 2.3.12 blocks this subtask. Do not defer it or swap in another DI library.
- **No `codegen:viewmodel-inject-processor`.** This subtask creates no entry/ViewModel generation.
- **Scopes added here.** `AppScope` and `DatabaseSessionScope` go in `core:common` under `.../common/di`. Window and entry scopes belong to subtasks 4 and 5. Add them here only if subtask 1 already declared them; never create them unused.
- **Runtime graph.** Desktop reaches the runtime only through the existing composite `RuntimeComponent`, which exposes `databaseSession`, `workControlService`, `FeatureTaskRuntimeRunEntry` and `workObservationService`.
  - Its construction call moves verbatim from `RoomBoardSource` into one `@SingleIn(DatabaseSessionScope::class)` provider in `core:data` `jvmMain`. The database-session owner closes it.
  - The desktop component never merges, re-provides, or duplicates runtime collaborators.
  - **Assumption:** `RoomBoardSource` builds the runtime component once per selected database today. Implement confirms this and keeps the same lifetime.
- **Common versus JVM data split** (parent: "Common data implements Room declarations/mapping where supported. Its JVM adapter consumes the supported runtime projection/control API"):
  - `core:data/commonMain` holds the Room entities, DAOs and database declaration, `BoardPagingSource`, the repository implementations, the session owner, and two internal ports:
    - `RuntimeWorkGateway` covers projection reads, control, and launch.
    - `CommitSignalSource` covers external committed-write signals.
  - `core:data/jvmMain` holds:
    - `JdbcRoomConnection` and `ReadOnlyRoomDriver`, which build the database
    - `DesktopCommitSignalObserver`, which implements `CommitSignalSource`
    - `RuntimeWorkMapper` and a `RuntimeWorkAdapter`, which implements `RuntimeWorkGateway` over `RuntimeComponent`
    - the host environment provider
  - **Assumption:** Room 3.0.3 (`androidx.room3`) supports commonMain declarations with `@ConstructedBy` and an expect `RoomDatabaseConstructor` on a JVM-only KMP target. If it does not, keep the Room declarations in `jvmMain` and record the deviation for the parity matrix. Do not move repository policy into `jvmMain` to work around it.
- **DataStore split.**
  - `core:datastore/commonMain` holds `StoredSettings` (serializable), its mapping to domain `AppSettings`, `LegacySettingsMigration` as a `DataMigration<StoredSettings>` working on an injected legacy-source interface, and `DataStoreSettingsRepository`, which implements the domain settings repository over `DataStore<StoredSettings>`.
  - `core:datastore/jvmMain` holds:
    - `JvmSettingsFileLocation`, which resolves `~/.skill-bill/desktop-settings.json` from the injected host home
    - the serializer, if it needs JVM streams
    - the `java.util.prefs` legacy-source implementation
    - the `@SingleIn(AppScope::class)` provider that calls `DataStoreFactory.create` once per file
  - **Assumption:** `datastore-core` 1.2.1 exposes `DataStore`, `DataMigration` and `CorruptionException` in common code. If any of them is JVM-only, the affected piece moves to `jvmMain` behind the same domain interface.
- **Readiness and failure model.** The domain settings repository publishes a sealed `SettingsState` with `Loading`, `Ready(settings: AppSettings)` and `Failed(problem: Problem)`.
  - It never emits defaults before the first persisted read completes.
  - `CorruptionException` maps to `Failed(ProblemCode.SettingsCorrupt)`. Do not install a replace-with-defaults corruption handler.
  - `update(transform)` wraps `updateData` and returns `Outcome.Failure(ProblemCode.SettingsUpdateFailed)` on an IO or serialization error.
  - Database selection waits for `Ready`.
  - Add the new `ProblemCode` values to the existing enum, keeping the existing names.
- **Migration diagnostics.** When the one-time legacy migration discards `cliOverride` and `pollSeconds`, it emits a typed `SettingsMigrationReport` (discarded key, legacy source, reason) through an injected diagnostics sink. Per the observability policy, every discarded value produces a record.
  - Once-only behaviour comes from the existing `shouldMigrate` and `cleanUp` logic, kept as is.
  - An executable path is never converted into a database path.

### Ordered tasks

1. **Reconcile the tree and the reference** (serves all ACs).
   - Record the Readian HEAD and any drift from `36d1dd9f…` for these files: `ReadianTopicsRepository.kt`, `TopicRoomDao.kt`, `ReadianRoomDatabaseProvider.kt`, `AppSettingsStore.kt`, `ApplicationComponent.kt`, `Scopes.kt` and `JvmComponentFactory.kt`.
   - The preplan could not read the reference root, so this step reads it and records what it finds. If the root is still unreadable, record that and follow the parent's adaptation column.
   - Inventory what subtask 1 has already landed: `build-logic` conventions, `core:common`, `core:domain`, `composeApp`, the module-aware `DesktopArchitectureTest`, and catalog entries. Reuse every piece that exists. Create only what is missing, following the parent's structure and conventions.

2. **Catalog and conventions** (AC-005, AC-006).
   - Make sure `gradle/libs.versions.toml` pins kotlin-inject (runtime and KSP compiler), kotlin-inject-anvil (runtime, annotations and KSP compiler), the Room 3 Gradle plugin if one exists, and `kotlin-compiler-embeddable`. The last one replaces the hardcoded `2.4.21` string.
   - Make sure the DI convention exists. It adds runtimes to `commonMain` and adds compilers through `kspJvm` only, so no metadata or common KSP run processes the same declarations twice.
   - Make sure the Room convention exists. It adds Room, the Room compiler through `kspJvm`, `sqlite-api` and `sqlite-jdbc`, and is applied only to `core:data`.
   - Do not add a `dependsOn` keyed on task-name substrings. Generated output stays under `build/`.

3. **`core:common` and `core:domain` prerequisites** (AC-002, AC-005).
   - `core:common` gets `AppScope`, `DatabaseSessionScope`, dispatcher qualifiers or a dispatcher-provider interface, a `Clock` interface, and the epoch-millis type.
   - `core:domain` gets the repository contracts that `core:data` and `core:datastore` implement:
     - the existing `WorkBoardRepository`, `WorkDetailRepository`, `WorkControlRepository` and `WorkStoreSession` signatures
     - a settings repository replacing `SettingsStore`, with `settings: Flow<SettingsState>`, `suspend update(transform): Outcome<AppSettings>`, and no `close`. App-scope teardown owns closing.
     - the domain types listed in the digest: `Outcome`, `Problem`, `ProblemCode`, `AppSettings`, `Retention`, `HistoryFilter`, `WorkSection`, `DetailList`, `Board`, `Detail`, `WorkItem` and `WorkIdentity`, all free of `java.time`.
   - Move these only if subtask 1 has not. Update every caller, including `DashboardController`, its tests, and `ui/*`, which still live in the root project.

4. **`core:datastore`** (AC-002, AC-006). Move `Preferences.kt` into the common and JVM split described above.
   - Allowed project dependencies: `core:common` and `core:domain`, through `implementation` unless a public signature exposes a type.
   - Contribute the repository binding with `@ContributesBinding(AppScope::class)`.
   - Delete `DataStoreSettingsStore` once its last caller moves.

5. **`core:data` common** (AC-001, AC-004, AC-006). Split the 881-line `RoomBoardSource` by owner and keep its behaviour unchanged:
   - `WorkStoreSessionManager` (app-scoped, implements `WorkStoreSession`).
     - It owns the database-session subcomponent factory, the current generation, and `selectDatabase`, `close` and `closeAndJoin`.
     - On a switch it cancels and joins the old session before the new one becomes usable. Cleanup callbacks never run while holding the existing mutex.
   - `DatabaseSession` (database-session scope) binds the identity and generation, the Room database, the runtime gateway, the commit-signal registration, and in-flight reads.
   - `RoomWorkBoardRepository` (`observe`, `board`, `retainPages`, `refresh` and `refreshEvents`).
     - It keeps one independent page state per `WorkSection` and history, ten-record initial pages, the existing `BoardRetention` bound, the existing deterministic cursor continuation, page retry, and query-level filter and `HistoryFilter` pushed into `WorkCandidateQueries` SQL.
   - `RoomWorkDetailRepository` (`observeDetail` and `detail` with a per-`DetailList` cursor).
   - `RuntimeWorkControlRepository` (`pause`, `stop`, `cancel` and `launch` through the gateway).
     - Runtime admission is unchanged.
     - `WorkRunLauncher` consumers stay the fixed set pinned by the guard. If the launcher-consuming file moves, update that set to the moved path without growing it.
   - Preserve the sidecar-only observation, a fresh baseline per observation session, reread on overflow, a stale view after observer failure until an explicit refresh, and store-epoch fencing. Add no timed reconnect or polling.
   - Repositories are `@SingleIn(DatabaseSessionScope::class)` with `@ContributesBinding`. No constructor parameter has a default value.

6. **`core:data` JVM** (AC-003, AC-004).
   - Move `JdbcRoomConnection`, `ReadOnlyRoomDriver`, `WorkRoomDatabase` (or only its builder if the declaration moved to common), `DesktopCommitSignalObserver`, `RuntimeWorkMapper` and `RuntimeDiagnostics` into `core:data/src/jvmMain`, if data is their only consumer. Otherwise leave `RuntimeDiagnostics` with its consumer.
   - Add a JVM `@ContributesTo(AppScope::class)` provider interface supplying a `HostEnvironment` value (environment map, user home, working directory) from `System.getenv()` and system properties. This replaces the three `RoomBoardSource` constructor arguments built in `Main.kt`.
   - Add the session-scoped `RuntimeComponent` provider, which keeps the verbatim construction.
   - Reads keep the read-only driver and never create, migrate, or repair the store. A missing or incompatible store stays an explicit typed failure.

7. **Generated components and composition** (AC-005).
   - Put `ApplicationComponent` (`@MergeComponent(AppScope::class)`, `@SingleIn(AppScope::class)`) in `composeApp/src/commonMain`, and a JVM factory in `composeApp/src/jvmMain` that creates it once and closes it on app exit.
   - Put the `DatabaseSessionComponent` `@ContributesSubcomponent(DatabaseSessionScope::class)` with its factory in `core:data`.
   - The app component exposes only what `Main.kt` reads: `workStoreSession`, the board, detail and control repositories (session-delegating), the settings repository, and the native services if they are already injectable.
   - Rewrite `Main.kt` so it gets these from the component inside `remember` and passes them to `DashboardController`. The controller stays until subtask 4 and is not made injectable here.
   - Delete the hand-built `RoomBoardSource(...)` and `DataStoreSettingsStore(...)` construction. Then delete `RoomBoardSource` itself: after the split it has no consumers.
   - **Assumption:** `composeApp` exists after subtask 1. If it does not, create `composeApp` holding `Main.kt` and its windows as they are, move the native tasks with it, and keep root task aliases working. Never put the app component in the root project.

8. **Tests** (each obligation below names the bug it catches; follow the test-value bar).
   - Move `RoomBoardSourceTest`, `RoomBoardObservationFailureTest`, `PackagedDataSmoke`, `RoomRuntimeCompatibilityTest` and `DesktopCommitSignalObserverTest` to `core:data/src/jvmTest`, split by repository where the subject changed. Move `DataStoreSettingsTest` to `core:datastore` (`jvmTest`, or `commonTest` where no file IO is needed).
   - `testFixtures(runtime-infra-sqlite)`, `runtime-infra-host` and `runtime-infra-contracts` move to `core:data` `jvmTest`.
   - Add a test only where the reconciled suite lacks coverage:
     - **AC-001.** The existing 5,000-record fixture proves three things:
       - each live section and history return exactly ten first-page rows, with the SQL row count bounded per page. This catches full-table slicing.
       - older live work stays visible behind 5,000 newer terminal rows. This catches history starvation.
       - appending one section leaves another section's first page and cursor unchanged. This catches cross-list consumption.
     - **AC-002.**
       - Restart: write, close the scope, then a new instance over the same file reads the value. This catches lost persistence.
       - Concurrency: N concurrent `update` transforms all apply. This catches clobbering.
       - Migration: a legacy source with `cliOverride` and `pollSeconds` gives one report per discarded key, and running again migrates nothing. This catches repeated or silent migration.
       - Readiness: the first emission is `Loading`, not defaults. This catches connecting to the default store before load.
       - Failures: a corrupt file gives `Failed(SettingsCorrupt)`, and a failing write gives an update `Outcome.Failure`.
     - **AC-003.** Open, read, observe and close against a disposable runtime-migrated database. Before and after, snapshot `sqlite_master`, `PRAGMA user_version`, and the workflow, lease and control rows, and assert they are identical. This catches a migration or write on read.
     - **AC-004.**
       - Start an independent writer as a child JVM running a `jvmTest` writer main through the runtime fixtures, using `java.home` and the test classpath.
       - Its committed update must reach live, detail and history subscriptions.
       - Without a signal, no re-query happens (DAO-call counter). This catches a polling regression.
       - Cancelling the collectors drops the commit-signal registration count to zero. This catches leaked observers.
     - **AC-005.** A composition smoke in `composeApp/src/jvmTest` creates the generated component with a disposable home and environment and waits for settings `Ready`. It selects a disposable runtime-produced database, reads one board page, calls `closeAndJoin`, and asserts no open connections or registrations remain. This catches stale generated graphs and duplicate session owners.
   - Constraints:
     - Use `relaxUnitFun = true`, never `relaxed = true`.
     - Pass explicit non-empty environment maps.
     - Resolve temp directories with `toRealPath()` because of the macOS `/var` symlink.
     - Never touch production databases or WAL/SHM files.
     - Use deterministic dispatchers and clocks.

9. **Architecture guards** (AC-005, AC-006). Extend the module-aware `DesktopArchitectureTest` from subtask 1, or rewrite the existing one to scan module source sets if subtask 1 has not landed. Keep the non-empty scan asserts and the existing call-graph, `IdeStatusService` and `WorkControlService` edge pins, with paths updated to the new owners. Add:
   - **Dependency edges.**
     - Allowed project dependencies for `core:data` are `core:common`, `core:domain` and `core:datastore`.
     - Allowed project dependencies for `core:datastore` are `core:common` and `core:domain`.
     - The check reads the declared project dependencies per configuration from each module's build file and reports any `api` edge lacking an exposed type.
     - It has rejection fixtures for a `feature:*` edge and a `core:testing` production edge.
   - **Source sets.**
     - Runtime (`skillbill.*`), JDBC, `java.nio`, `java.util.prefs` and AWT imports are allowed only in the `jvmMain` of these modules.
     - Both modules forbid Compose, lifecycle and navigation imports.
     - Rejection fixtures cover an aliased runtime import in `commonMain` and a qualified-name use.
   - **Composition.**
     - Production sources contain no `ServiceLoader`, `Class.forName`, or reflective `createInstance`.
     - No `@Inject` constructor parameter has a default value.
     - Composition sources do not directly construct a `*Repository`, a `DataStore`, `RuntimeComponent`, or the removed `RoomBoardSource`.
     - Each rule has a rejection fixture run through the real scanner.
   - Extend no baseline or exemption.

### Constraints

- Every subtask must leave `desktop-tray` configuring, compiling, and passing check. Validation commands (`./gradlew clean check --no-build-cache`, and a clean `kspKotlinJvm` from empty outputs) belong to the validate phase. Build proof belongs to the build phase.
- Add no install, uninstall, or install-sync steps.
- Leave SKILL-423 obligations AC-28 to AC-34 to subtask 3, preserving present behaviour only.
- Leave ViewModels, navigation, window scopes and documentation updates to subtasks 4 to 6.
- Do not change runtime-kotlin.

## Next Path

Later subtasks consume the domain repositories through feature ViewModels and the navigation host.

## Spec Path

.feature-specs/SKILL-424-readian-architecture-parity-for-the-desktop-app/spec_subtask_2_data-preferences-and-generated-di.md
