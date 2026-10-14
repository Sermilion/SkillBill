# SKILL-424 Subtask 6 - Deletion and delivery proof

Parent spec: [.feature-specs/SKILL-424-readian-architecture-parity-for-the-desktop-app/spec.md](./spec.md)
Issue key: SKILL-424

## Scope

Remove every remaining superseded monolithic path, reconcile tests and inventories, run the full required validation, build and smoke the distribution, and update desktop architecture/build/run documentation and CI to the compiled final structure.

Parent sections: Required final project structure; Architecture enforcement and evidence; Validation strategy; Host adapters and UI preservation.

## Acceptance Criteria

1. Settings include populated `composeApp`, all listed core modules, and the four feature modules, each owning its parent-table responsibilities, shown by Gradle project/task and source/consumer inventory (parent AC-01).
2. Old root app source sets, `DashboardController`, the combined `BoardSource`, the CLI resolver/process/decoder path, executable preferences, and polling behavior have no production consumers and are deleted, and boundary guards reject their return (parent AC-23).
3. Native tray, notifications, login-start, and packaging live in JVM owners. A clean distribution launches against a disposable store with no installed CLI and no PATH discovery, and exercises opening, live/history/detail browsing, permitted controls, settings, two windows, tray navigation, hiding, and exit with clean shutdown (parent AC-24, AC-25).
4. All architecture guards have nonempty inputs, rejection fixtures, and build wiring. Touched runtime rules and guards only keep or tighten enforcement, and no baseline, exemption, or suppression grows (parent AC-26).
5. A migration inventory maps every old production owner and test to its final module/source set or deletion. A Readian parity matrix lists reference pattern, final Skill Bill path/symbol, real consumer, lifecycle owner, validation evidence, and deviations. Docs and CI match the module task paths and name untested hosts (parent AC-27).

## Execution rule

This subtask has no manifest dependencies. It runs on the current tree. It creates any missing module, convention, or contract its criteria need, keeps a change that is already present, and applies its rule to whatever anchors exist, whether moved, renamed, or deleted. When it moves or deletes a symbol, it updates every caller wherever that caller lives. It leaves `desktop-tray` configuring, compiling, and passing `./gradlew check`; it never lands a half-migrated tree that only a later subtask can repair.

The parent spec is authoritative for module ownership, allowed dependencies, lifetimes, reference patterns, and non-goals. Read its sections named under Scope before changing code. The Readian reference root is `/Users/braian.gapur/StudioProjects/readian-android`; record its current HEAD and any drift from the recorded `36d1dd9f902a7ab8b7eb2117e898e911fe51120d` before adopting a pattern.

## Non-goals

- New features or behavior changes beyond removing superseded paths.
- Claiming native parity on hosts that were not exercised.
- Any parent non-goal.

## Validation Strategy

Run the desktop `./gradlew clean check --no-build-cache` and confirm root `check` executes every child check. If runtime code changed, also run `cd runtime-kotlin && ./gradlew check`. Build a clean distribution and smoke it on the available host, and state which hosts were not exercised.

## Implementation Details

All paths are relative to `desktop-tray/` unless they start with `runtime-kotlin/`, `.github/`, or `scripts/`. The source symbols come from the preplan census. Earlier subtasks may have moved or renamed them, so each task starts from what is in the current tree. Implement compiles nothing and runs no checks. The validate phase runs every command named below.

### Assumptions for implement to confirm

- **A1.** Subtasks 1 to 5 have landed, so `composeApp`, `core:*`, `feature:*`, `build-logic`, the generated DI graph, the feature ViewModels, and the Navigation 3 host exist. If Task 1 finds that an entire owner is missing (for example, no feature ViewModel replaces a `DashboardController` responsibility), stop. Report AC-002 as blocked and name that responsibility. Do not rebuild another subtask's scope, and do not wrap the old code. A small leftover, such as a single caller or helper still in the root tree, is migrated here.
- **A2.** The module-aware architecture guard written by subtask 1 is the guard to extend. If it still lives in root `src/test/kotlin/dev/skillbill/tray/architecture/DesktopArchitectureTest.kt`, move it to `composeApp/src/jvmTest/kotlin/dev/skillbill/tray/architecture/`, because root `src/test` is deleted. Its `kotlin-compiler-embeddable` dependency goes through the catalog, and its Gradle test inputs must declare every module source root plus the runtime roots it reads today.
- **A3.** `scripts/packaged-data-smoke.py` and `PackagedDataSmoke` currently launch the packaged app's data path against a disposable store. Implement reads both, confirms that contract, and changes only paths, the environment, and wiring. If they do something else, implement records the difference in the inventory and adapts the smoke described in Task 5.
- **A4.** The Readian reference root `/Users/braian.gapur/StudioProjects/readian-android` was unreadable during preplan. Implement tries to read it and record its HEAD. If it is still unreadable, the parity matrix records "reference HEAD not re-verified; recorded 36d1dd9f… used". It does not claim drift was checked.
- **A5.** The current host is macOS (darwin). Whether CI runs Linux or Windows runners is unknown, so implement reads `.github/workflows/desktop-tray-ci.yml` and states the runner OS before naming tested and untested hosts.

### Ordered tasks

1. **Residual-consumer census and deletion (AC-002, AC-001).**
   - Census the `desktop-tray` tree, `.github/`, `scripts/`, and `runtime-kotlin` for references to:
     - `DashboardController`, `DashboardModel`, `DashboardState`, `BoardProjection`, `perform(action: String`
     - `RoomBoardSource` (and any single class that still implements more than one of `WorkBoardRepository`, `WorkDetailRepository`, `WorkControlRepository`, `WorkStoreSession`)
     - `cliOverride`, `pollSeconds`, executable-path preference fields
     - CLI resolver, process, and decoder code: `ProcessBuilder` or `Runtime.exec` outside the approved native files, PATH or login-shell discovery, and CLI JSON decoding
     - timed refresh loops
     - package `dev.skillbill.tray.application`
     - files under root `src/main` and `src/test`
   - The runtime-side `RuntimeComponent` readers (`databaseSession`, `workControlService`, `workObservationService`, `FeatureTaskRuntimeRunEntry`) must keep a desktop production reader after the move. Point the runtime inbound API inventory at that new reader, and do not delete the export.
   - Migrate each live consumer to its final owner, or delete it when it has no production consumer.
   - Delete the root `src/main/kotlin`, `src/main/resources`, `src/main/native`, and `src/test/kotlin` trees once fonts, icons, OFL licences, and native sources sit in their owners. Fonts and icons go to `core:designsystem` Compose resources. Native sources go to `composeApp/src/jvmMain/native`.
   - Keep `LegacySettingsMigration`, which reads and discards `cliOverride`/`pollSeconds` with attributed diagnostics. It is the only allowed reader of those keys.
   - Touches: the root tree, `Ports.kt`/`DashboardController.kt`/`RoomBoardSource.kt` wherever they now live, and the runtime inbound API inventory if a reader path changes.
   - Tests: none new here; Task 2 guards the end state.

2. **Root build becomes an aggregator (AC-001, AC-003, AC-005).**
   - `build.gradle.kts` applies no Kotlin JVM, Compose application, or KSP plugin and compiles no sources.
   - Native tasks live in `composeApp/build.gradle.kts` or the desktop-application convention in `build-logic/convention`: `compileAppImageLauncher`, `compileLinuxTrayNative`, `compileMacTrayNative` (`libskillbilltray.dylib` staged under `macos/`), `stageAppImage` (reads `composeApp/build/compose/binaries/main/app` and `design/App/docs/assets/skill-bill-app-icon.svg`), and `packageAppImage`. Their inputs point at `composeApp/src/jvmMain/native/...`.
   - Resource processing depends on the native tasks through modeled inputs and outputs, not task-name substring matching.
   - Keep these settings unchanged: the `desktopJavaHome` Adoptium JDK 21 pin, the excluded `nucleus.decorated-window-tao`, `mainClass`, the `modules(...)` list, the Dmg/Msi/Deb/Rpm formats, the "Skill Bill" package name, bundle ID `dev.skillbill.tray`, the `RELEASE_VERSION` mapping, and the pinned operator-placed `appimagetool` 1.9.1 (Gradle configuration never downloads or runs it).
   - Add root task aliases for documented developer commands whose path changed: `run`, `createDistributable`, `packageDistributionForCurrentOS`, `packageAppImage`, and the packaged smoke task from Task 5.
   - Root `check` must reach every child `check`. Either rely on Gradle's all-project task selection or wire `check` to depend on `subprojects`' `check`, and note which one in `ARCHITECTURE.md`.
   - Tests: none. The validate phase proves this with a `--dry-run`/task-graph listing.

3. **Deletion guards with rejection fixtures (AC-002, AC-004).**
   - Extend the module-aware guard from A2 with rules that fail when any of these appear:
     - (a) a production Kotlin source under root `src/`, or a root project that applies a Kotlin compilation plugin
     - (b) a declaration named `DashboardController`/`DashboardModel`, or a production class implementing two or more of the four repository/session ports
     - (c) `ProcessBuilder`, `Runtime.exec`, or `ProcessHandle` launching, including aliased imports and fully qualified calls, outside the unchanged approved set (`NativeServicesImpl`, `NativeTrayHost`, `TrayPopoverPlacement`, `MacStatusItemBridge`, at their new paths) and the runtime-admitted `WorkRunLauncher` consumer set
     - (d) `cliOverride`/`pollSeconds` symbols outside the `core:datastore` legacy-migration file
     - (e) periodic polling primitives (`ticker`, `fixedRateTimer`, `scheduleAtFixedRate`, `scheduleWithFixedDelay`, `java.util.Timer`) in `core:data`, `core:datastore`, `core:domain`, and `feature:*` production sources
   - Deadline scheduling (parent AC-34) and the one-shot 4-second Linux tray probe must stay legal. Rule (e) bans repeating primitives only and must not flag a single `delay` used for a deadline or timeout.
   - Each rule runs through the real PSI scanner over the real module source sets and asserts a nonempty scan set.
   - Each rule gets one synthetic rejection fixture plus the existing accepted production tree. Use a single parameterized test across the rules where the fixture harness allows it.
   - The approved-process list and every other existing allow-list keep or shrink their size.
   - If subtask 1 has no module-population check, add one. Every `settings.gradle.kts` project except the root has populated production source in its declared source sets, and every core and feature module has at least one production consumer project.
   - Tests:
     - The rejection fixtures catch the realistic bug where a reintroduced monolith controller, combined BoardSource, CLI fallback, or polling loop passes `check`.
     - The empty-root fixture catches a guard made vacuous by a moved path.

4. **Runtime guard parity (AC-004).**
   - Change runtime code only if Task 1 changed a reader path. Then update only the runtime inbound API inventory's consumer path, keep its rejection fixtures, and add no baseline, exemption, or suppression.
   - If runtime-kotlin was touched, record A1–A12/G1–G7 notes for that file set in the inventory doc.
   - Tests: none new. The validate phase runs `cd runtime-kotlin && ./gradlew check` only if runtime files changed.

5. **Distribution and composition smoke (AC-003).**
   - **(a) In-process app smoke.** Add a single in-process app smoke test, `composeApp/src/jvmTest/.../DesktopAppSmokeTest`, unless subtask 5 tests already cover an equivalent end-to-end path. In that case cite them and add nothing.
     - It starts the real JVM startup owner and generated app component.
     - It uses a disposable runtime-migrated store from `testFixtures(runtime-infra-sqlite)`, a disposable home and preferences file, and an explicit non-empty environment map without `PATH`. An empty map falls back to the host environment.
     - Through the Compose desktop UI test API and typed host intents, it opens the store, browses live, history, and detail, pauses or stops a permitted disposable target, edits a setting, opens a second window, dispatches a tray open-detail intent, hides the main window, and exits.
     - It asserts that owned connection, observer, and job counters return to zero, and that no process was spawned.
     - Launch (Resume/Run again) needs an installed harness and is excluded and recorded.
     - Mocks use `relaxUnitFun = true`, never `relaxed = true`.
     - Bug caught: a generated-graph or teardown regression that leaks a connection or watcher, or reaches the CLI, while unit tests pass.
   - **(b) Packaged smoke.** Register `:composeApp:packagedDataSmoke` (root alias `packagedDataSmoke`), which depends on `createDistributable` and runs the existing packaged smoke against `composeApp/build/compose/binaries/main/app`. Point `scripts/packaged-data-smoke.py` at the new path.
     - Launch with a disposable HOME and store and `PATH` set to an empty directory.
     - Assert startup, store open, and clean exit.
     - Keep it out of `check`. Validate and CI invoke it explicitly.
   - Native tray clicks, OS notification display, and login-start registration on a real desktop cannot run headless. Record them as manual or unexercised per host.

6. **Migration inventory and parity matrix (AC-005, AC-001).**
   - Write `docs/architecture-parity.md`, linked from `ARCHITECTURE.md`, with three tables.
   - **(1) Module inventory.** One row per settings project: owned responsibility from the parent table, main production symbols, production consumer projects, and Gradle task paths.
   - **(2) Migration inventory.** Every old production file in the preplan census goes to its final module/source set/symbol or to an explicit deletion with a reason. Every old test maps to its new location or a deletion reason. Old tests:
     - `DashboardControllerTest`, `ProjectionTest`, `TransitionTrackerTest`
     - `DesktopArchitectureTest`, `DesktopUiScaleTest`, `TrayPopoverPlacementTest`
     - `RoomBoardSourceTest`, `RoomBoardObservationFailureTest`, `PackagedDataSmoke`
     - `DataStoreSettingsTest`, `DesktopCommitSignalObserverTest`, `RoomRuntimeCompatibilityTest`
     - `TrayHoverPresentationTest`, `MainWindowTest`, `ReasonTooltipTest`
   - A deletion is allowed only when its subject was deleted. Regression coverage of a real past bug is ported, never dropped.
   - **(3) Readian parity matrix.** One row per parent reference-table row: reference pattern, final Skill Bill path/symbol, real consumer, lifecycle owner, validation evidence (test class or Gradle task path), and deviation.
     - Name these deviations: JVM-only conventions; database-session scope instead of `UserScope`; presentation factories in `core:ui`, not domain; typed assisted arguments, not a string map; runtime-owned schema with no destructive delete; host-owned component shutdown; separate desktop windows; whether `codegen:viewmodel-inject-processor` was omitted (expected); and the guard-approved native process helpers plus runtime-admitted harness launch.
     - Record reference HEAD per A4.
   - Evidence cells name tests and tasks only. Executed results, counts, and hosts go into the validate-phase report, not committed logs.
   - The untested-hosts statement lists the host the smoke ran on, CI runner OSes per A5, and every remaining host as "not exercised".

7. **Docs and CI (AC-005).**
   - Rewrite `ARCHITECTURE.md` to the compiled module graph, lifetimes (app, database session, window, entry), guard list, and remaining data gaps after subtask 3. Remove monolithic `src/main` instructions.
   - Update `docs/data-layer-validation.md` (test locations and module task paths), `APPIMAGE.md` (`:composeApp:packageAppImage` or its root alias, and the staging path), and `scripts/packaged-data-smoke.py`.
   - Update `.github/workflows/desktop-tray-ci.yml`:
     - path filters: `desktop-tray/build-logic/**`, `composeApp/**`, `core/**`, `feature/**`, `gradle/libs.versions.toml`, `runtime-kotlin/**`
     - task paths: `check`, `:composeApp:createDistributable`, `packagedDataSmoke` where the runner supports packaging
     - composite runtime build from source
   - Update `.github/workflows/desktop-tray-snapshot-cleanup.yml` only if it names artifact paths that moved.
   - Leave `agent/history.md` and `agent/decisions.md` to write_history.

### Constraints

- Do not change behavior beyond removing superseded paths. Keep visuals, tray and popover behavior, keyboard and focus behavior, the Linux tray probe, and native loading.
- No baseline, exemption, suppression, or allow-list grows, and runtime guards only keep or tighten enforcement.
- Do not run install, uninstall, or install-sync commands. Implement runs no build, check, or test commands. The validate phase owns `./gradlew clean check --no-build-cache` in `desktop-tray`, the dry-run proof that root `check` reaches every child check, `:composeApp:createDistributable` plus `packagedDataSmoke`, and `runtime-kotlin` check if runtime changed.
- Do not modify the parent spec, sibling sub-specs, or the decomposition manifest. Do not commit generated outputs or raw logs.
- The manifest/current-branch name mismatch and the active-fix-branch rule are owned by the parent runtime, not this subtask.

## Next Path

Complete the goal and prepare its pull request.

## Spec Path

.feature-specs/SKILL-424-readian-architecture-parity-for-the-desktop-app/spec_subtask_6_deletion-and-delivery-proof.md
