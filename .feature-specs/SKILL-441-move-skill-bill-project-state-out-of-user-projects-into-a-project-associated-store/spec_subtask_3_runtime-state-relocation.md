# SKILL-441 subtask 3: relocate config, run evidence, tracking and acceptance repair into the store

## Scope

Move the repo-local config, run evidence, feature-task tracking and acceptance repair from
`<checkout>/.skill-bill/` to the project store resolved by `ProjectStorePort`. The legacy config is
copied once; legacy evidence, tracking and repair data are not migrated. Persisted legacy evidence
references stay readable so in-flight goals finish in place.

## Acceptance Criteria

1. The repo-local config lives in the store, with a one-time copy of a legacy `./.skill-bill/config.yaml`.
   In runtime-infra/host `FileSystemRepoLocalConfig.kt`, `configPath(repoRoot)` returns `ProjectStorePort.resolve(repoRoot).configPath` instead of `repoRoot/.skill-bill/config.yaml`. `REPO_LOCAL_CONFIG_FILE_NAME` stays `"config.yaml"`.
   When the store `config.yaml` is absent and `<repoRoot>/<LegacyProjectStatePaths.RUNTIME_STATE_DIRECTORY>/config.yaml` exists, the reader copies the legacy bytes into the store through a temp file and atomic move, emits one `RuntimeDiagnostics.warning` `seam=repo_local_config value_expected=<store config path> value_used=<legacy config path> cause=legacy_config_copied`, then parses the store copy. Once the store copy exists the legacy file is ignored, even when its content differs, and no further record is emitted. With neither file present the result stays `RepoLocalConfig.defaults()`. The legacy file is never deleted or modified. A copy failure emits a warning naming the cause and falls back to parsing the legacy file for this read, so config resolution never blocks.
   runtime-infra/skills `InstallApplyRepoLocalConfig.kt` (near line 42) reads and writes the store `configPath` instead of `repoRoot/.skill-bill/config.yaml`.
   In runtime-cli `ConfigCommand.kt`, the help text `"Inspect resolved repo-local configuration (.skill-bill/config.yaml)."` and the `--repo-root` help name the project store config (for example `project store config.yaml; see skill-bill project path`) instead of `.skill-bill/config.yaml`.
   `config edit` is subtask 6. Audit confirms by reading the three files.
2. New run evidence is written to and read from the store, and legacy evidence references still resolve in the checkout.
   In runtime-domain `FeatureTaskRuntimeRunEvidenceAddress.kt`, `STORE_ROOT` becomes the store-relative `"run-evidence"`, and `workflowStoreRoot(workflowId)` returns `run-evidence/<workflowId>`. In `ReviewContextHunkModels.kt` (near line 37) the store path becomes `run-evidence/in-process/$digest`.
   In runtime-infra/workflow `FileSystemFeatureTaskRuntimeSharedEvidenceStore.kt` (near lines 74 and 191), store-relative references resolve against `ProjectStorePort.resolve(repoRoot).root`. A persisted reference that starts with `.skill-bill/run-evidence/` resolves against the checkout root, read-only, so rows written before the upgrade stay readable. No new write targets the checkout.
   Every other consumer that joins the evidence store path with the repo root resolves through the store the same way; implement finds them from the `STORE_ROOT` and store-path usages (assumption: they live in runtime-infra/workflow and runtime-engine).
   Audit confirms by reading the address constants and the store's two resolution branches.
3. Feature-task tracking and acceptance repair live in the store, and goal-state cleanup touches only the store.
   In runtime-ports `FeatureTaskImplementationChecklist.kt`, `IMPLEMENTATION_CHECKLIST_STORE_ROOT` becomes the store-relative `"feature-task-tracking"`, and its readers and writers resolve it against `ProjectStorePort.resolve(repoRoot).root`.
   In runtime-infra/workflow `FileSystemAcceptanceCriterionRepairProjection.kt` (near line 31), the repair projection resolves under `ProjectStoreLocation.acceptanceRepairRoot` instead of `request.repositoryRoot.resolve(".skill-bill/acceptance-repair...")`, keeping the same file names below that root.
   The `GoalRuntimeStateFileStore` implementation deletes `<store>/feature-task-tracking/<workflowId>/` and `<store>/run-evidence/<workflowId>/` and no longer deletes anything under the checkout's `.skill-bill`. Legacy tracking and repair data are not migrated; an in-flight goal starts fresh tracking and repair state in the store.
   Audit confirms by reading the constant, the repair projection and the cleanup implementation.

## Criterion Sizing

1. About 120 production lines, 3 files, 3 areas (`runtime-infra/host`, `runtime-infra/skills`, `runtime-cli`). Fits.
2. About 140 production lines, 4 files, 2 areas (`runtime-domain`, `runtime-infra/workflow`). Fits.
3. About 120 production lines, 4 files, 2 areas (`runtime-ports`, `runtime-infra/workflow`). Fits.

## Non-Goals

- Migrating legacy run evidence, tracking or repair data.
- Deleting anything in the checkout's `.skill-bill`.
- Spec bundle paths (subtasks 4 and 5).

## Dependency Notes

Depends on subtask 1 (`ProjectStorePort`) and subtask 2 (`LegacyProjectStatePaths`). Independent of subtasks 4 and 5.

## Test Obligations

- Config: legacy file present and store absent copies once and parses the copied values; afterwards an edited legacy file does not change the result. This catches the store copy losing precedence.
- Evidence: a legacy `.skill-bill/run-evidence/...` reference written before the upgrade still reads back, and a new reference lands under the temp store, not the checkout. This is the in-flight goal compatibility path.
- Cleanup: deleting goal state removes the store directories and leaves a checkout `.skill-bill/feature-task-tracking/<id>/` untouched.
- Test fixtures that hardcode `.skill-bill/...` (for example in application testFixtures and `SqliteTestDatabaseFixtures.kt`) are updated to inject a temp store root.

## Validation Strategy

The validate phase runs `./gradlew check` in `runtime-kotlin`. This phase does not run it.

## Next Path

```bash
skill-bill goal SKILL-441
```

## Implementation Details

Assumptions for implement to confirm before editing:

- Subtask 1 delivers `ProjectStorePort` in runtime-ports with `resolve(repoRoot: Path): ProjectStoreLocation`, where `ProjectStoreLocation` carries `root`, `configPath`, `runEvidenceRoot`, `trackingRoot` and `acceptanceRepairRoot` as absolute paths, and an `@Inject` implementation in runtime-infra/host rooted at `resolveTelemetryStateDir(env, home)/projects`. If a field name differs, use the delivered name. Do not add a parallel resolver.
- Subtask 2 delivers `LegacyProjectStatePaths` with `RUNTIME_STATE_DIRECTORY = ".skill-bill"`. If it lacks a legacy run-evidence prefix, add `LEGACY_RUN_EVIDENCE_PREFIX = ".skill-bill/run-evidence/"` to that object instead of declaring a new local constant.
- `resolve` never throws (subtask 1 contract). Callers here do not add their own fallback around it.

Ordered tasks:

1. Store-backed repo-local config (AC 1). Touches runtime-infra/host `FileSystemRepoLocalConfig.kt`.
   - Add `ProjectStorePort` to the `@Inject` constructor next to `RuntimeDiagnostics`. `configPath(repoRoot)` returns `projectStore.resolve(repoRoot).configPath`. Keep `REPO_LOCAL_CONFIG_FILE_NAME = "config.yaml"`.
   - In `readRepoLocalConfig`, before parsing: if the store config is absent and `repoRoot/LegacyProjectStatePaths.RUNTIME_STATE_DIRECTORY/config.yaml` exists, create the parent directories, copy the legacy bytes into a temp file in the store config directory, then `Files.move(..., ATOMIC_MOVE)`. Emit one `diagnostics.warning("seam=repo_local_config value_expected=<store path> value_used=<legacy path> cause=legacy_config_copied")`, then parse the store copy.
   - A concurrent first resolve can find the target already present when it moves. Treat `FileAlreadyExistsException` as success: parse the store copy and emit no second record.
   - On any other `IOException` during the copy, emit `warning` with `cause=legacy_config_copy_failed` and the exception, then parse the legacy file for this read only. Never delete or write the legacy file.
   - If the store copy exists, parse it and never look at the legacy file. If neither exists, return `RepoLocalConfig.defaults()` as today.
   - The file is 308 lines. If the copy logic pushes it past the production line ceiling, move it into an `internal` helper file in the same package, for example `LegacyRepoLocalConfigCopy.kt`.
   - Update the kotlin-inject component or graph that constructs `FileSystemRepoLocalConfig` if it does not resolve the new parameter automatically.
2. Install apply config path (AC 1). Touches runtime-infra/skills `InstallApplyRepoLocalConfig.kt` near line 42. Replace `repoRoot/.skill-bill/config.yaml` with `ProjectStorePort.resolve(repoRoot).configPath` for both reads and writes. Inject the port through the owning class's constructor. The install apply flow edits config (register/unregister platform packs). This task changes only the config path it resolves. It does not plan or run any install command.
3. CLI help text (AC 1). Touches runtime-cli `ConfigCommand.kt`. Change the command help to name the project store config, for example `Inspect resolved project configuration (project store config.yaml; see skill-bill project path).`, and the `--repo-root` help to say the repo root selects the project whose store config is read. Do not add `config edit`. Subtask 6 owns it.
4. Run evidence addresses (AC 2). Touches runtime-domain `FeatureTaskRuntimeRunEvidenceAddress.kt` and `ReviewContextHunkModels.kt` near line 37.
   - `STORE_ROOT = "run-evidence"`. `workflowStoreRoot(workflowId)` returns `run-evidence/<workflowId>`. The hunk store path becomes `run-evidence/in-process/$digest`.
   - The domain stays free of filesystem resolution. It only defines the store-relative strings.
5. Evidence store resolution (AC 2). Touches runtime-infra/workflow `FileSystemFeatureTaskRuntimeSharedEvidenceStore.kt` near lines 74 and 191.
   - Inject `ProjectStorePort`. Add one private resolver `resolveEvidencePath(repoRoot, reference)`. A reference starting with `LegacyProjectStatePaths.LEGACY_RUN_EVIDENCE_PREFIX` resolves against `repoRoot` and is used only for reads. Every other reference resolves against `projectStore.resolve(repoRoot).root`.
   - All writes go through the store root. If a write path gets a legacy-prefixed reference, re-root it as `run-evidence/...` under the store. Never write into the checkout.
   - Reject a resolved path that escapes its base after `normalize()`. Keep whatever path-containment check the file already has and apply it to both bases.
6. Other evidence path consumers (AC 2). Find the remaining usages of `FeatureTaskRuntimeRunEvidenceAddress.STORE_ROOT`, `workflowStoreRoot` and the hunk `storePath` that join with a repo root, plus literal `"run-evidence"` joins after `.skill-bill`. Route each through the same store-root resolution. Assumption from preplan: these live in runtime-infra/workflow and runtime-engine. If more than two extra sites need the legacy branch, extract the resolver from task 5 into a small `internal` shared helper in runtime-infra/workflow and use it everywhere. Engine sites must take the resolved absolute path through a port, not import the infra helper.
7. Feature-task tracking (AC 3). Touches runtime-ports `FeatureTaskImplementationChecklist.kt` line 5: `IMPLEMENTATION_CHECKLIST_STORE_ROOT = "feature-task-tracking"`. Update its readers and writers (found from the constant's usages) to resolve against `ProjectStorePort.resolve(repoRoot).root`. Legacy tracking is not read. An in-flight goal starts fresh tracking in the store.
8. Acceptance repair (AC 3). Touches runtime-infra/workflow `FileSystemAcceptanceCriterionRepairProjection.kt` near line 31. Inject `ProjectStorePort` and resolve under `projectStore.resolve(request.repositoryRoot).acceptanceRepairRoot`, keeping the file names below the root unchanged. No legacy read.
9. Goal-state cleanup (AC 3). Touches the `GoalRuntimeStateFileStore` implementation. Implement locates it from the port in runtime-ports `ports/workflow/goalstate`; assumed to be in runtime-infra/workflow. Delete `<store>/feature-task-tracking/<workflowId>/` and `<store>/run-evidence/<workflowId>/` only. Remove the deletes under the checkout's `.skill-bill`. Keep the existing missing-directory tolerance.
10. Test fixtures. Update fixtures that hardcode `.skill-bill/...` for config, evidence, tracking or repair, for example application testFixtures and runtime-infra/sqlite `SqliteTestDatabaseFixtures.kt` near line 236. They should inject a `ProjectStorePort` rooted in a temp directory, using subtask 1's test double or the state-dir environment override. Fixture strings that model persisted legacy rows stay as `.skill-bill/run-evidence/...` on purpose. Leave `.feature-specs` fixture strings alone; subtasks 4 and 5 own them.

Constraints:

- runtime-kotlin rules apply: no `//` or non-KDoc block comments, KDoc only on interfaces, production file line ceilings, no inline FQNs, and any new wire key goes in a `*Keys` object. Ports stay in runtime-ports, implementations are `@Inject` classes, and engine and application depend only on ports.
- Diagnostic records follow docs/observability-policy.md: `seam=`, `value_expected=`, `value_used=` and `cause=`, bounded, with no file contents.
- Never delete or modify anything in the checkout's `.skill-bill`.
- This subtask does not touch spec bundle paths, the runtime-private exclusion constants (subtask 2), agent launch directories or desktop code.
- No compile, test or check runs here. The validate phase runs `./gradlew check` in runtime-kotlin.

Tests to add (each tied to a realistic bug):

- runtime-infra/host, in the existing `FileSystemRepoLocalConfig` test file or a new `FileSystemRepoLocalConfigStoreTest`. Use a temp checkout with a legacy `config.yaml` and an empty temp store. The first read returns the legacy values and creates the store file. Then edit the legacy file. The second read still returns the original values and emits no second warning. Catches the store copy losing precedence or a repeat copy overwriting user edits (AC 1).
- runtime-infra/workflow evidence store test. Pre-seed `<checkout>/.skill-bill/run-evidence/<wf>/...` and read it back through a legacy-prefixed reference. Then write a new reference and assert the file exists under the temp store root and nothing new appears under `<checkout>/.skill-bill`. Catches broken in-flight goal reads and stray checkout writes (AC 2).
- runtime-infra/workflow goal-state cleanup test. Seed both store directories and `<checkout>/.skill-bill/feature-task-tracking/<id>/`. After cleanup the store directories are gone and the checkout directory remains. Catches cleanup deleting user project files or leaving store state behind (AC 3).
- No dedicated tests for the constant changes, help text, acceptance-repair re-rooting or install-apply path. Audit reads them, and the tests above cover the resolution path they share.
- Existing tests that assert `.skill-bill/run-evidence` or `.skill-bill/feature-task-tracking` locations for new writes are updated to the store location. Their assertions are not weakened.
