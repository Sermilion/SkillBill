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

## Operator decision: runtime process access (2026-10-15)

This decision supersedes any parent-spec or SKILL-423 handoff text (including AC-28's "hidden process/runtime access" wording) that forbids desktop work reads from reaching runtime process adapters.

The desktop app may reach runtime services that run `git` or other processes through the in-process runtime graph. This covers status projection, goal outcome reconciliation, resume, pause, stop, run launches, and other controls. Do not restructure runtime goal-status or reconciliation code to avoid these edges, and do not add an allow-list entry for them.

- Remove the `assertNoReachableWorkDataProcessAdapter` reachability rule from `DesktopArchitectureTest`, together with the governed-edge-walk fixtures that exist only to support it. Keep the guard's other assertions, such as the run-launch ownership set and the native display query check, when they still hold.
- Keep the ban on executing an installed `skill-bill` CLI, PATH or login-shell discovery, and parsing command output from the packaged app (`DeletionRulesTest` and parent AC-24). The packaged app stays self-contained, and the CLI-unavailable distribution smoke still applies.
- Removing this rule is an operator-approved relaxation, not a grown baseline or exemption. Record it in the migration inventory and parity matrix as a deviation with this date.

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

Paths are relative to `desktop-tray/` unless they start with `runtime-kotlin/` or `.github/`. `ARCH/` stands for `composeApp/src/jvmTest/kotlin/dev/skillbill/tray/architecture/`. Implement compiles nothing and runs no checks. The validate phase runs every command named below.

### Starting state

The tree was not reset for this run. Implement starts from it and reverts none of it.

- HEAD `a37daae82` already holds most of this subtask: the residual deletion, the root aggregator, the deletion guards, the smoke tasks, `docs/architecture-parity.md`, the doc updates, and CI.
- About 70 files on top of HEAD are uncommitted. They are in `desktop-tray` (build-logic, `composeApp`, `core/*`, `feature/*`, the catalog) and in `runtime-kotlin` (runtime-domain, runtime-ports, runtime-engine `work/` including `pagination/`, runtime-infra/sqlite). Untracked files include `KotlinSerializationConventionPlugin.kt` and a runtime `WorkCandidateRepository.kt`. They are part of the work being delivered, so implement keeps them and does not rework them.
- The reachability rule this spec's operator decision removes is still in the tree. Removing it is the main remaining code change.

### Assumptions for implement to confirm

- **A1.** The walk in `GovernedEdges.walkGovernedEdges` is shared by `DesktopArchitectureTest` and `SourceRules.policyOwnershipViolations`, the AC-28 test `desktop surfaces render runtime policy verdicts and never recompute them`. No other caller exists. If another caller relies on the process assertions, keep the assertion for that caller only and record why.
- **A2.** `controlEntryPoints`, `governedFunctionRoots`, and `DATA_PACKAGE_ROOT` in the `DesktopArchitectureTest` companion are used only by `assertNoReachableWorkDataProcessAdapter`. Confirm before deleting each one. Keep any that has another use.
- **A3.** After the process assertions are gone, some helpers in `KotlinReferences.kt` or `ArchitectureSources.kt` may have no caller left (for example, a process-type matcher). Delete a helper only when it has no remaining caller. Keep any helper that `DeletionRules` still uses, because rule (c) still bans process types in desktop sources.
- **A4.** The `core/data` census hits (`RuntimeWorkGateway.kt`, `RuntimeWorkAdapter.kt`, `WorkStackTest`, `ExternalRuntimeWriter`, `ObservationLifecycleTest`) are comments, test-writer helpers, or `Runtime*` names that match the census pattern. They are not live consumers of the retired path. If one is a real production consumer of `DashboardController`, `RoomBoardSource`, `cliOverride`, `pollSeconds`, a CLI process, or a repeating timer, migrate it to its final owner, or delete it if no production code uses it.
- **A5.** The `RuntimeComponent` readers `databaseSession`, `workControlService`, `workObservationService`, and `FeatureTaskRuntimeRunEntry` still each have a desktop production reader in `composeApp` or `core:data` after the uncommitted runtime edits. If a reader moved, point the runtime inbound API inventory at the new reader and keep the export. If an export lost every reader, report it and do not delete it here.

### Ordered tasks

Everything was planned in this one session, without delegated units, because the remaining work touches one test package and two docs.

1. **Remove the reachability rule from `ARCH/DesktopArchitectureTest.kt` (AC-004; operator decision).**
   - Delete `private fun assertNoReachableWorkDataProcessAdapter(sources: List<Source>)` (about lines 106–137) and its call (about line 50).
   - Delete the companion members that only it used, after the A2 check.
   - Keep these in the test `work data avoids process adapters and explicit run actions use the runtime launch port` (about line 36):
     - `assertApprovedNativeDisplayQuery(sources)`, which checks that `TrayPopoverPlacement.kt` has exactly one `ProcessBuilder(` and that it is `ProcessBuilder("kscreen-doctor", "-j")`;
     - the non-empty scan assertion;
     - the run-launch ownership set. Under `runtime-kotlin/**/src/main`, the importers of `skillbill.ports.work.WorkRunLauncher` are exactly `engine/work/control/WorkRunActions.kt`, `engine/work/control/WorkCancellation.kt`, `infrastructure/launcher/work/DetachedWorkRunLauncher.kt`, `di/core/RuntimeContext.kt`, and `di/core/RuntimeWorkControlProvides.kt`. Leave the set unchanged.
   - Rename the test so it no longer claims "avoids process adapters". Example: `explicit run actions use the runtime launch port and the native display query stays approved`.
   - Keep `the generated output the governed sources import is scanned`.
   - Tests: none new. This task removes a rule; it adds no behavior.

2. **Strip the process-only parts of `ARCH/GovernedEdges.kt` (AC-004; operator decision).**
   - Remove:
     - the `WorkRunLauncher` `start`/`stop` special case (about lines 122–126);
     - the `forbiddenWorkDataProcessAdapters` assertion (about line 127) and its constant (about lines 34–36);
     - the `launchesCommand` assertion (about line 128), the `launchesCommand` field of `FunctionBody` (about line 68), and its computation (about lines 303–308);
     - `isApprovedNativeDisplayQuery` (about lines 99–101), once nothing calls it;
     - the KDoc sentence "and a call to a forbidden process adapter each fail the walk".
   - Keep everything else unchanged, especially the policy rule's inputs: the unresolved-edge and empty-root failures, generated-import resolution (`generated: Set<String>`, `generatedDeclarations`, `parseFiles`), local typing, alias, overload, extension and dispatch resolution, and `enclosingDeclaration`.
   - Why this has to go too: `SourceRules.policyOwnershipViolations` uses the same walk, so it would fail as soon as it reaches runtime code that runs `git`. That is the same edge the operator approved.
   - Then apply A3: delete helpers that this removal left without a caller.
   - Constraint: if removing these assertions exposes other walk failures, such as unresolved edges into runtime code, treat them as real findings. Fix them by resolving the edge in the walker, or report them. Never add an allow-list entry, a skipped root, or a suppression to hide them.

3. **Rework the walker fixtures in `DesktopArchitectureTest` (AC-004; parent AC-28 scanner proof).**
   - `SourceRulesTest` has no `walkGovernedEdges` fixtures, so all of them live in `DesktopArchitectureTest`.
   - Keep these fixtures as they are: clean walk, unresolved edge fails, generated import resolves, and empty root fails.
   - Rework the aliased-import fixture and the extension-call fixture. Today each "rejects a `ProcessRunner` call". After the change, each asserts that the walk reaches the aliased or extension target, meaning the returned `visited` set (or whatever the walk exposes as reached declarations) contains it.
   - Rename the fixture target to something neutral, such as `target.Collaborator.load`, and drop the `infra` `ProcessRunner` framing. Do not delete these fixtures: AC-28 needs real fixtures that prove alias and extension resolution, and nothing else proves it.
   - If the walk exposes no visited set, assert that the walk does not report an unresolved edge for the aliased or extension call. Keep a negative twin, an alias that points at a missing declaration, which must fail as unresolved. That makes the fixture prove resolution, not just silence.
   - Test obligations (each catches a realistic bug):
     - The aliased-import reach fixture catches an alias-resolution regression that makes the policy walk silently skip `import x as y` edges, which would let a renamed policy reimplementation pass AC-28.
     - The extension-call reach fixture catches the same failure for extension receivers.
   - Mocks, if any, use `relaxUnitFun = true` and never `relaxed = true`.

4. **Residual verification of earlier work (AC-001, AC-002, AC-003, AC-005).** This task only checks and keeps work that already landed.
   - Apply A4 to the `core/data` hits.
   - Leave the empty untracked root directories `src/main/resources/fonts` and `src/test` in place. Git ignores them, and `DeletionRulesTest.rootSourceFiles()` counts only regular files. Removing them is optional, and this plan does not remove them.
   - Leave `DeletionRules` rules (a)–(e) and `approvedProcessFiles` unchanged. The approved files are `NativeServicesImpl`, `NativeTrayHost`, `TrayPopoverPlacement`, and `macos/MacStatusItemBridge` under `composeApp/src/jvmMain/kotlin/dev/skillbill/tray/infrastructure/`. The list must not grow. Rule (c) keeps the ban on process use in desktop sources, which the operator decision preserves.
   - Read `.github/workflows/desktop-tray-snapshot-cleanup.yml`. Change it only if it names a path that moved to `composeApp/build/...`.
   - Keep the CI workflow as it is: the three OS runners, `clean check createDistributable --no-build-cache`, `:composeApp:packagedDataSmoke`, the artifact upload path, and the broader path filter.
   - Apply A5 to the runtime reader inventory.
   - Tests: none.

5. **Record the deviation, reference revision, and hosts in the docs (AC-005).**
   - `docs/architecture-parity.md`:
     - **Migration inventory.** Add a deviation entry dated 2026-10-15 and marked operator-approved. It records that `assertNoReachableWorkDataProcessAdapter` and the process-only walk assertions were removed, that the aliased-import and extension-call fixtures were reworked into reach assertions, and that this is not a baseline or exemption.
     - **Parity matrix.** Add the same deviation to the deviation cell of the guard row, or of the row nearest the runtime integration and guards. Keep the matrix at 22 rows unless a new row is needed for this deviation.
     - **"Process and native helpers".** Add that desktop work reads and controls may reach runtime services that run `git` or other processes through the in-process runtime graph, and that the packaged app still never runs an installed CLI, discovers PATH or a login shell, or parses command output.
     - **"Reference revision".** Replace the unreadable-root text with:
       - current reference HEAD `69f9161c116dde3eb011a7e41f3a9f2fdf6a6377`;
       - the recorded `36d1dd9f902a7ab8b7eb2117e898e911fe51120d` does not resolve in the clone, so drift could not be diffed;
       - no new reference patterns were adopted.
     - **"Hosts".** Keep the statement that native tray clicks, notification display, login-start, KDE and GNOME behavior, and installers are not exercised by any automated task. Name the CI runner OSes (ubuntu, macos, windows), each running `check`, `createDistributable`, and `packagedDataSmoke`. Name macOS (darwin) as the local validate-phase host. Record executed results in the validate report, not as committed claims.
     - **Runtime touched-file notes.** If the doc has no A1–A12/G1–G7 notes for the modified `runtime-kotlin` file set (runtime-domain, runtime-ports, runtime-engine `work/` and `pagination/`, runtime-infra/sqlite, and the untracked `WorkCandidateRepository.kt`), add a short section with them. Take the file list from the working tree. Every note states that no baseline, exemption, or suppression grew.
   - `ARCHITECTURE.md` (about lines 157–171):
     - Reword "The work-data path has no CLI or process adapter" so it keeps the claim "no installed CLI, no PATH or login-shell discovery, no command-output parsing" and drops the blanket "no process adapter".
     - Add one sentence that runtime services reached through the in-process graph may run `git`, per the 2026-10-15 operator decision.
     - Keep the approved native file list.
   - Leave `docs/skill-423-handoff.md` and `docs/data-layer-validation.md` unchanged; they contain none of this wording.
   - Leave `agent/history.md` and `agent/decisions.md` to write_history.
   - Tests: none. These are docs.

### Validation (validate phase only)

- `cd desktop-tray && ./gradlew clean check --no-build-cache`. This covers `:composeApp:jvmTest`, including `DesktopArchitectureTest`, `DeletionRulesTest`, `ModuleGraphRulesTest`, `SourceRulesTest`, `NavigationTypingRulesTest`, `DesktopAppSmokeTest`, and `ApplicationComponentSmokeTest`, plus `:convention:check`.
- A `--dry-run` or task-graph listing showing that root `check` reaches every child `check` and `gradle.includedBuild("build-logic")` `:convention:check`.
- `./gradlew :composeApp:createDistributable`, then `packagedDataSmoke` with no CLI and `PATH` set to an empty directory.
- `cd runtime-kotlin && ./gradlew check`, which is required because runtime files are modified.

### Constraints

- Keep behavior unchanged. This subtask only removes a guard rule and updates records.
- No allow-list, baseline, exemption, or suppression grows. `approvedProcessFiles` and the run-launch ownership set stay the same size. The removed rule is the operator-approved relaxation and is recorded as a deviation, not as an exemption.
- Do not restructure runtime goal-status or reconciliation code to avoid process edges. Do not rework the uncommitted runtime changes.
- Implement runs no build, check, or test commands, and nothing runs install, uninstall, or install-sync. In headless runs, use single grep pipelines rather than python, `$`-anchored regexes, or chained commands.
- Do not modify the parent spec, sibling sub-specs, or the decomposition manifest. Do not commit generated outputs or raw logs.

## Next Path

Complete the goal and prepare its pull request.

## Spec Path

.feature-specs/SKILL-424-readian-architecture-parity-for-the-desktop-app/spec_subtask_6_deletion-and-delivery-proof.md
