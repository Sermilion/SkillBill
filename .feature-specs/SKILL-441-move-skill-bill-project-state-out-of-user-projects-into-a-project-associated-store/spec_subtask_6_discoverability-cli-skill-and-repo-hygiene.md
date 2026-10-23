# SKILL-441 subtask 6: runtime bundle file list, CLI discoverability, skill content and repository untracking

## Scope

Give users and the desktop a way to find store files: the runtime publishes the absolute files of
each spec bundle after a plan, the CLI prints store paths and edits the project config, the
skill-bill skill scans the store, and `phase:pr` summarizes the spec in the PR body. SkillBill's own
repository stops tracking `.feature-specs/` and `.skill-bill/config.yaml`.

## Acceptance Criteria

1. The runtime publishes the absolute files of the spec bundle once a plan finishes, for standalone plan runs and for goal plan phases.
   runtime-ports `StandalonePlanReportModels.kt` adds `bundleFiles: List<String>` to `StandalonePlanReport`: absolute paths of every regular file under the bundle directory, recursive, sorted lexicographically, including the parent spec, subtask specs, `decomposition-manifest.yaml` and any other file. `parentSpecPath` and each `StandalonePlanSubtask.specPath` in newly built reports are absolute paths resolved through the store (or the legacy checkout directory for legacy goals).
   runtime-infra/sqlite `StandalonePlanReportMigration.kt` encodes the list under the wire key `bundle_files` (declared once in a `*Keys` object) and decodes a missing key as an empty list, so rows written by older runtimes and older desktops keep working. `StandalonePhaseStatusStore.kt` stores and loads it.
   runtime-engine `StandalonePlanRun.planReport` (near lines 393 to 405) fills the list after `settlePlan`. A goal run's plan phase completion attaches the same report to the goal's activity entry and publishes it through `StandalonePhaseStatusEventSink` or the goal execution history path that `WorkExecutionHistory.kt` reads. Assumption for implement to confirm: the goal plan-phase settlement site is in the feature-task run loop; implement locates it.
   Desktop rendering is subtask 7. Audit confirms by reading the model, the migration codec and both producers.
2. The CLI prints the project store path and edits the project config.
   The `project` command group from subtask 1 gains `skill-bill project path [ISSUE]`. With no argument it prints `ProjectStoreLocation.root` for the current directory. With an issue key it prints the bundle directory found through the store-first issue resolution from subtask 4, and exits non-zero with a message naming the store specs root when no bundle matches. The payload carries `store_root` and, with an issue, `bundle_path`, declared in a `*Keys` object.
   runtime-cli `ConfigCommand.kt` gains an injected `edit` subcommand: `skill-bill config edit` resolves the store `configPath`, creates it with the default config content when absent (after the legacy copy rule from subtask 3 runs), and opens it in `$VISUAL`, then `$EDITOR`, then the OS default application. It prints the path it opened. When no editor can be launched it prints the path and exits zero.
   Audit confirms by reading both commands and their wiring.
3. The skill-bill skill finds specs and allocates local keys through the store, and its PR phase summarizes the spec.
   In `skills/skill-bill/content.md`, "Issue resolution" (near lines 155 to 165) gets the store with `skill-bill project path` and searches `<store>/specs/` (excluding `done/`), then the legacy `./.feature-specs/` (excluding `done/`) for in-flight legacy goals. "Local key allocation" (near lines 237 to 263) scans `<store>/specs/` including `done/` and the legacy `./.feature-specs/` including `done/`, so a key used in either place is never reissued.
   "Phase PR" (near lines 382 to 390) tells the PR phase to put a short spec summary (the intended outcome and the acceptance criteria titles from the bundle's parent spec) in the PR description, because specs are no longer committed. Assumption for implement to confirm: if a runtime-owned PR prompt builder in runtime-kotlin also composes the PR body instructions, it gets the same instruction.
   No other skill section changes. Audit confirms by reading the three sections.
4. SkillBill's own repository no longer tracks `.feature-specs/` or `.skill-bill/config.yaml`.
   Implement removes both from the git index with `git rm -r --cached .feature-specs .skill-bill/config.yaml`, leaving every working-tree file in place, including this run's own legacy bundle and the local config that subtask 3 copies into the store.
   `.gitignore` drops the lines that re-include `.skill-bill/config.yaml` and `.skill-bill/phases/**` (lines 8 to 12) and the stale `.feature-specs` entry (line 18), and adds ignore rules for `/.feature-specs/` and `/.skill-bill/`.
   Assumption for implement to confirm: the scoped staging from subtask 2 excludes `.feature-specs/` paths, so implement must make sure these index removals reach the subtask commit (they are already staged by `git rm --cached`, and the commit must not unstage them). Audit confirms by reading `.gitignore` and the subtask's diff, which deletes the tracked `.feature-specs/` files and `.skill-bill/config.yaml` from the index.

## Criterion Sizing

1. About 220 production lines, 7 files, 3 areas (`runtime-ports`, `runtime-infra/sqlite`, `runtime-engine`). Fits.
2. About 160 production lines, 4 files, 2 areas (`runtime-cli`, `runtime-infra/host`). Fits.
3. About 40 changed content lines, 1 file, 1 area (`skills/skill-bill`). Fits.
4. About 10 production lines in `.gitignore` plus index-only removal of about 1514 tracked files, 1 file, 1 area (repository root). Fits; the index removal is mechanical and not authored code.

## Non-Goals

- Desktop rendering and opening files (subtask 7).
- Deleting any working-tree spec or config file.

## Dependency Notes

Depends on subtask 5 (store bundle locations and writers) and on subtask 1 (`project` command group) and subtask 3 (config path and legacy copy). Subtask 7 renders `bundleFiles`. The branch diff for this subtask includes the index-only removal of the tracked spec files, so review should expect a large deletion list.

## Test Obligations

- Plan report codec: a stored report without `bundle_files` decodes with an empty list, and a new report round-trips its absolute list. This is the version-skew risk.
- `project path ISSUE` prints the store bundle directory when the bundle exists in the store.
- No tests for the skill content or `.gitignore`; the skill validator covers content structure.

## Validation Strategy

The validate phase runs `./gradlew check` in `runtime-kotlin` and the repository's skill validator for `skills/skill-bill/content.md`. This phase does not run them.

## Implementation Details

Tasks run in this order. Tasks 1 to 3 build on each other; tasks 4 to 6 are independent of them and of each other.

### Task 1. Bundle file list on the plan report model and codec (AC 1)

- runtime-ports `skillbill/ports/idestatus/model/StandalonePlanReportModels.kt`: add `bundleFiles: List<String> = emptyList()` to `StandalonePlanReport`. The default keeps existing constructors compiling.
- runtime-infra/sqlite `migration/area/idestatus/StandalonePlanReportMigration.kt`: encode the list under `bundle_files`. Decode a missing key, or a non-array value, as an empty list without failing, because older runtimes and desktops write rows without it. Declare `bundle_files` once in the existing plan-report `*Keys` object, or a new `StandalonePlanReportKeys` if none exists (WireVocabularyArchitectureTest). Assumption for implement to confirm: the codec already uses a keys object for `parent_spec_path` and `subtasks`; reuse it.
- `StandalonePhaseStatusStore.kt` (near lines 398 and 739): if the store writes the report through the codec, nothing changes. If it maps fields by hand, add the new field to both the write and read paths.
- Check that `StandalonePhaseStatusModels.kt:131/176`, `WorkDetailModels.kt:118` and runtime-engine `work/WorkExecutionHistory.kt:111` pass the whole `StandalonePlanReport` through. Any site that copies fields one by one must carry `bundleFiles`.
- Test obligation (version skew, the realistic bug is a desktop or runtime crash or silently dropped links on old rows): one codec test in the sqlite module's existing migration test home. It decodes a stored JSON report without `bundle_files` to an empty list, and round-trips a report with two absolute paths unchanged and in the same order.

### Task 2. Fill the list in standalone plan runs (AC 1)

- Add one shared helper next to the plan report builder in runtime-engine, for example `planBundleFiles(bundleDirectory: Path): List<String>`. It walks every regular file under the bundle directory recursively and returns `toAbsolutePath().normalize().toString()` sorted lexicographically. An unreadable or missing directory returns an empty list and emits one `RuntimeDiagnostics.warning` record (`seam=plan_bundle_files value_expected=<dir readable> value_used=empty cause=<exception class>`). It never blocks the run.
- runtime-engine `goalrunner/plan/StandalonePlanRun.kt` `planReport(issueKey, repoRoot)` (near lines 393 to 405): resolve the bundle directory through the store location from subtask 5 (the store bundle for new runs, the legacy `<checkout>/.feature-specs/<bundle>` directory for legacy goals). Then make `parentSpecPath` and every `StandalonePlanSubtask.specPath` absolute against that directory instead of `repoRoot`, and set `bundleFiles` from the helper. The report is built after `settlePlan` (called at lines 223 and 349), so the manifest file is already on disk and appears in the list.
- Replace the `root.resolve(specPath)` comparisons near lines 380 to 391 only where subtask 5 has not already moved them to the store resolver. Do not change them a second time.

### Task 3. Attach the report to the goal plan phase (AC 1)

- Find where the feature-task run loop settles a goal's plan phase (assumption from the spec: in runtime-engine `featuretask/runloop`, next to the plan-bundle authorization in `runloop/planning/PlanBundleAuthorization.kt`). After a successful plan settlement, build the same `StandalonePlanReport` through the code path task 2 uses. Extract `planReport` into a small shared builder if `StandalonePlanRun` keeps it private, so both producers share one implementation.
- Publish it on the goal's activity entry through the existing `StandalonePhaseStatusEventSink` (`:47/125/258`) plan-report path, or through the execution history record that `WorkExecutionHistory.kt:111` reads, whichever already carries `planReport` for the goal's work instance. Do not add a parallel model (preplan decision D5).
- If the goal plan phase replans a legacy `./.feature-specs/` intake, the report points at the new store bundle. A legacy in-flight goal that already has a governed legacy bundle reports the legacy checkout paths as absolute paths.
- Test obligations: none beyond task 1. Wiring a report through an existing sink is glue that audit verifies by reading both producers.

### Task 4. `skill-bill project path [ISSUE]` (AC 2)

- runtime-cli: add a `path` subcommand to the `project` command group that subtask 1 registers in `cli/core/CliCommandGroups.kt`, wired through kotlin-inject like the other subcommands. Inject `ProjectStorePort` and the store-first `FeatureSpecPathResolverPort` from subtask 4.
- No argument: resolve `ProjectStoreLocation` for the current working directory (or `--repo-root` if the group already exposes it) and finish with `state.completeText(root, mapOf(STORE_ROOT to root))`.
- With an issue key: run the store-first resolution. On `SingleMatch` or `Explicit`, print the bundle directory (the parent of the resolved spec) with payload `store_root` and `bundle_path`. On `NoMatch`, exit non-zero with a message naming `specsRoot` from the result, for example `No spec bundle for <KEY> under <store>/specs`. On `Ambiguous`, exit non-zero and list the candidates.
- Declare `store_root` and `bundle_path` once in a `*Keys` object (a new `ProjectCommandKeys`, or the keys object subtask 1 adds for `project link`).
- Test obligation (the realistic bug is a path that resolves against the repo root or the legacy directory instead of the store): one CLI test with a temp state dir that writes `<store>/specs/<KEY>-x/spec.md`, runs `project path <KEY>`, and asserts that the printed path equals the store bundle directory. Use the state-dir env override from `TelemetryConfigPaths.resolveTelemetryStateDir` so the test never touches the real home.

### Task 5. `skill-bill config edit` (AC 2)

- runtime-cli `cli/config/ConfigCommand.kt`: add an injected `edit` Clikt subcommand next to `resolve-spec-type` and the others. Update the `ConfigCommand` help text and the `--repo-root` help so they name the project store config instead of `.skill-bill/config.yaml`.
- Behavior: resolve the store `configPath` through `ProjectStorePort`. Read the config once through `RepoLocalConfigPort` so subtask 3's legacy copy runs first. If the file is still absent, create it with the default config content. Then pick an editor: `$VISUAL`, then `$EDITOR`, then the OS default opener (`xdg-open` on Linux, `open` on macOS, `cmd /c start ""` on Windows). Print the opened path. If nothing can launch, print the path and exit zero.
- Put process launching behind a small port in runtime-ports (for example `FileEditorLauncherPort.open(path): Boolean`) with an `@Inject` impl in runtime-infra/host, so runtime-cli does not spawn processes directly. KDoc goes on the interface only. Assumption for implement to confirm: if runtime-infra/host already has a process-launch or open-file helper, reuse it instead of adding a port.
- Default config content: write whatever `RepoLocalConfig.defaults()` serializes to. If the codec has no writer, use a minimal YAML that `FileSystemRepoLocalConfig` parses back to defaults (an empty mapping `{}`). Implement confirms which.
- Test obligations: none. Editor selection is interactive glue on a non-critical path, and the config read and legacy copy are covered by subtask 3.

### Task 6. Skill content (AC 3)

- `skills/skill-bill/content.md` "Issue resolution" (near lines 155 to 165): get the store root with `skill-bill project path`, search `<store>/specs/` excluding `done/`, then fall back to the legacy `./.feature-specs/` excluding `done/` for in-flight legacy goals.
- "Local key allocation" (near lines 237 to 263): scan `<store>/specs/` including `done/` and the legacy `./.feature-specs/` including `done/`, and take the highest key across both, so a key used in either place is never reissued.
- "Phase PR" (near lines 382 to 390): tell the PR phase to put a short spec summary in the PR description (the intended outcome and the acceptance criterion titles from the bundle's parent spec), because specs are no longer committed.
- Change no other section. If a runtime-kotlin PR prompt builder also composes PR body instructions, add the same one-sentence summary instruction there. Implement locates it, and if none exists it changes nothing.
- Do not run install or install-sync commands after editing the skill. Rendering and the skill validator belong to the validate phase.

### Task 7. Untrack spec bundles and config in this repository (AC 4)

- Run `git rm -r --cached .feature-specs .skill-bill/config.yaml`. This removes about 1514 entries from the index and leaves every working-tree file in place, including this run's own legacy SKILL-441 bundle and the local `.skill-bill/config.yaml`.
- `.gitignore`: delete lines 8 to 12 (the `.skill-bill/config.yaml` and `.skill-bill/phases/**` re-includes) and the stale `.feature-specs` entry at line 18. Add `/.feature-specs/` and `/.skill-bill/`.
- Do this task last in the subtask so the staged deletions sit next to the `.gitignore` change. The commit for this subtask must keep the index deletions. Scoped staging (subtask 2's runtime-private exclusions) skips `.feature-specs/` paths when it adds files, but it must not reset or unstage entries that are already staged. Implement confirms this against `GitScopedStagingOperations.kt` and records the result in its receipt. If scoped staging would drop the deletions, implement reports that as a blocker for the commit phase and does not rewrite staging.

### Constraints

- Follow the runtime-kotlin rules: no `//` or non-KDoc block comments, KDoc only on interfaces, file line ceilings, no inline FQNs, and wire keys declared once in `*Keys` objects.
- Ports go in runtime-ports, `@Inject` impls in runtime-infra, and engine, application and CLI depend on ports.
- Every new path in a report or CLI output is absolute and resolved by the runtime. The desktop never joins the repo root.
- Never delete a working-tree file in the user's project or in this repository.
- Desktop rendering and file opening stay in subtask 7.
- This phase and implement do not run `./gradlew check`, builds or install commands. Validation owns them.

## Next Path

```bash
skill-bill goal SKILL-441
```
