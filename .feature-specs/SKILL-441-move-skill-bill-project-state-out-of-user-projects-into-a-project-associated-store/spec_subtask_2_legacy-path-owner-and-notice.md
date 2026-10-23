# SKILL-441 subtask 2: single legacy path owner and the legacy state notice

## Scope

Collapse the duplicated `.skill-bill` and `.feature-specs` literals that mark runtime-private paths
into one legacy-path owner, so in-flight legacy goals keep excluding those directories from
checkpoints, staging, progress and the worktree edit journal. Add the one-time warning that the
old `./.skill-bill/` directory is no longer used and can be deleted.

Exclusion behavior does not change: both legacy directories stay runtime-private. This keeps a
legacy goal's commit_push from staging the 1500+ tracked spec files or a staging directory.

## Acceptance Criteria

1. One object in runtime-contracts owns the legacy project-state names, and the runtime-domain and runtime-engine exclusion sites read from it.
   A new `LegacyProjectStatePaths` object (package `skillbill.contracts.projectstore`) declares `RUNTIME_STATE_DIRECTORY = ".skill-bill"`, `FEATURE_SPECS_DIRECTORY = ".feature-specs"`, and the slash-suffixed forms `RUNTIME_STATE_PREFIX = ".skill-bill/"` and `FEATURE_SPECS_PREFIX = ".feature-specs/"`.
   These sites drop their private literal and use the object: `FeatureTaskRuntimeCheckpointScope.kt` (`RUNTIME_PRIVATE_ROOT`), `WorktreeEditJournalWriter.kt` (`RUNTIME_PRIVATE_ROOT` and its use near line 132), `GoalPlanningExcludedPaths.kt` (the `.skill-bill` entry), `GoalRunnerTickProgressReader.kt` (`FEATURE_SPEC_ROOT`), `FeatureTaskRuntimeRunLoop.kt` (`FEATURE_SPEC_ROOT` and its uses near lines 182 to 184), and `PrReviewFixExecution.kt` (`SPEC_ROOT`).
   Each site keeps excluding exactly the paths it excluded before; only the source of the string changes.
   The infra staging site is criterion 2, and the intake marker `GOAL_INTAKE_FEATURE_SPECS_MARKER` moves in subtask 5. Audit confirms by reading the object and finding no remaining private `.skill-bill` or `.feature-specs` literal in these six files.
2. Scoped git staging reads its runtime-private spec root from the legacy-path owner.
   In runtime-infra/workflow `GitScopedStagingOperations.kt`, the private `FEATURE_SPEC_ROOT` constant near line 24 is removed and the exclusion near line 226 uses `LegacyProjectStatePaths.FEATURE_SPECS_PREFIX`, and the same file treats `LegacyProjectStatePaths.RUNTIME_STATE_PREFIX` as runtime-private wherever it already did.
   Scoped staging therefore still never stages paths under `./.feature-specs/` or `./.skill-bill/`, which covers the staging fallback directory `./.skill-bill/staging/` that subtask 5 introduces.
   Audit confirms by reading the file and finding no private `.feature-specs` literal.
3. The resolver warns once per project that the old `./.skill-bill/` directory can be deleted, and never deletes it.
   The project-store record schema from subtask 1 gains an optional `legacy_state_notice_at` (ISO-8601 UTC string). The contract version stays `"0.1"` because the field is additive and the contract has not shipped.
   During resolve in the runtime-infra/host implementation, when `<checkout root>/.skill-bill` exists and the record has no `legacy_state_notice_at`, the resolver emits one `RuntimeDiagnostics.warning` `seam=project_store_legacy_state value_expected=<store root> value_used=<checkout root>/.skill-bill cause=legacy_state_not_migrated`, with the bounded text that run-evidence, feature-task-tracking and acceptance-repair were not migrated, the store starts fresh, and the old directory can be deleted. It then stamps `legacy_state_notice_at` through the same atomic record write.
   A missing legacy directory emits nothing. A record that already has the stamp emits nothing. The resolver never deletes, moves or modifies anything under the checkout's `.skill-bill`.
   Copying the legacy `config.yaml` is subtask 3. Audit confirms by reading the schema field and the resolver branch.

## Criterion Sizing

1. About 60 production lines, 7 files, 3 areas (`runtime-contracts`, `runtime-domain`, `runtime-engine`). Fits.
2. About 15 production lines, 1 file, 1 area (`runtime-infra/workflow`). Fits.
3. About 60 production lines, 3 files, 2 areas (`orchestration/contracts`, `runtime-infra/host`). Fits.

## Non-Goals

- Relocating any runtime state (subtask 3) or spec bundle (subtasks 4 and 5).
- Deleting or migrating any legacy file.

## Dependency Notes

Depends on subtask 1 for the record schema and the resolver. Subtasks 3 to 5 import `LegacyProjectStatePaths` for every legacy compatibility path.

## Test Obligations

- One resolver test that a checkout with `.skill-bill/` emits the legacy warning on the first resolve, emits nothing on the second (stamp persisted across a fresh resolver instance), and leaves the directory intact. That catches a repeat warning on every launch and an accidental delete.
- No new tests for the constant swaps; existing checkpoint, staging and journal tests already cover the exclusions and must stay green.

## Implementation Details

Assumptions for implement to confirm before editing:

- A1. Subtask 1 lands the port `ProjectStorePort` in runtime-ports, its `@Inject` implementation in runtime-infra/host next to `CanonicalRepositoryRoot`, a Kotlin record model for `project.json`, a YAML schema under `orchestration/contracts/` with a `*_CONTRACT_VERSION = "0.1"` constant and a parity test, and an atomic record write (temp file plus move). Exact class and file names come from subtask 1. Use whatever it named; do not add a second record writer.
- A2. runtime-domain, runtime-engine and runtime-infra/workflow already depend on runtime-contracts (runtime-engine uses `IssueAndFeature.kt` from it today). If runtime-domain does not, add the module dependency rather than moving the object.
- A3. The resolver already receives `RuntimeDiagnostics` and the checkout root (the enclosing repository root, or the primary root for a linked worktree) during resolve. The legacy check uses the checkout the launch runs in, so a linked worktree checks its own `.skill-bill`.

Ordered tasks:

1. Add the legacy-path owner (criterion 1).
   Create `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/projectstore/LegacyProjectStatePaths.kt` with `object LegacyProjectStatePaths` holding the four `const val`s from criterion 1. No KDoc (it is not an interface) and no comments.
2. Swap the domain and engine exclusion sites to the owner (criterion 1).
   - `runtime-domain/.../goalrunner/planning/GoalPlanningExcludedPaths.kt:13`: the `.skill-bill` entry becomes `LegacyProjectStatePaths.RUNTIME_STATE_DIRECTORY`.
   - `runtime-engine/.../featuretask/lifecycle/checkpoint/FeatureTaskRuntimeCheckpointScope.kt:10`: delete the private `RUNTIME_PRIVATE_ROOT` and use `RUNTIME_STATE_PREFIX` (the old value had the trailing slash).
   - `runtime-engine/.../worktreeedit/WorktreeEditJournalWriter.kt:150` and its use at `:132`: delete `RUNTIME_PRIVATE_ROOT` and use `RUNTIME_STATE_DIRECTORY` (old value had no slash). If other files reference `WorktreeEditJournalWriter.RUNTIME_PRIVATE_ROOT`, point them at the owner too.
   - `runtime-engine/.../goalrunner/execution/support/GoalRunnerTickProgressReader.kt:14`: delete `FEATURE_SPEC_ROOT` and use `FEATURE_SPECS_DIRECTORY`; repoint any external references.
   - `runtime-engine/.../featuretask/runloop/core/FeatureTaskRuntimeRunLoop.kt:175` and uses at `:182-184`: delete the private constant and use `FEATURE_SPECS_DIRECTORY`.
   - `runtime-engine/.../operation/prreviewfix/PrReviewFixExecution.kt:187`: delete `SPEC_ROOT` and use `FEATURE_SPECS_PREFIX` (old value had the slash).
   For each site, match the old string form exactly (slash or no slash) so exclusion behavior stays identical. Imports only, no inline FQNs.
3. Swap the scoped staging site (criterion 2).
   `runtime-infra/workflow/.../git/scoped/GitScopedStagingOperations.kt:24`: delete the private `FEATURE_SPEC_ROOT`. The exclusion at `:226` uses `LegacyProjectStatePaths.FEATURE_SPECS_PREFIX`. Any existing `.skill-bill` runtime-private check in the file uses `RUNTIME_STATE_PREFIX`. If the file has no `.skill-bill` check today, do not add one here; `./.skill-bill/` exclusion for staging stays wherever it already lives (checkpoint scope) and implement records where that is.
4. Add the notice stamp to the record contract (criterion 3).
   - Add optional `legacy_state_notice_at` (string, ISO-8601 UTC, not in `required`) to the subtask 1 project-store record schema. Keep `contract_version` at `"0.1"`.
   - Add a nullable `legacyStateNoticeAt` field (default null) to the Kotlin record model. Decode treats an absent key as null; encode omits or writes it per the existing record encoder convention. Declare the wire key in the existing `*Keys` object for the record (WireVocabularyArchitectureTest).
   - Update the subtask 1 parity test only if it enumerates schema properties.
5. Emit the warn-once notice in the resolver (criterion 3).
   In the runtime-infra/host `ProjectStorePort` implementation, after the record is loaded or created and before the final record write, check `Files.isDirectory(checkoutRoot.resolve(LegacyProjectStatePaths.RUNTIME_STATE_DIRECTORY))`. When true and `legacyStateNoticeAt == null`, call `diagnostics.warning` once with `seam=project_store_legacy_state value_expected=<store root> value_used=<checkout root>/.skill-bill cause=legacy_state_not_migrated` plus the bounded sentence that run-evidence, feature-task-tracking and acceptance-repair were not migrated, the store starts fresh, and the old directory can be deleted. Then set `legacyStateNoticeAt` to the current UTC instant from the resolver's existing clock and persist it through the same atomic write that updates last-seen. Missing directory or existing stamp: no record, no write beyond what resolve already does. No delete, move or write under the checkout. A stamp write failure must not block resolve: follow subtask 1's write-failure handling (warn and continue).
   Keep the branch in a small private function or helper file if the implementation file nears its line ceiling (ProductionFileLineCeilingArchitectureTest).

Tests:

- Add one test in runtime-infra/host next to subtask 1's resolver tests (criterion 3): temp state dir and a temp git checkout containing `.skill-bill/`. First resolve records exactly one warning whose message contains `seam=project_store_legacy_state`. A fresh resolver instance on the same store records no such warning. `.skill-bill/` and a file inside it still exist afterwards. Use a recording `RuntimeDiagnostics` fake and assert on recorded messages, not call ordering.
- No tests for tasks 1 to 3. Existing checkpoint, scoped staging, progress and journal tests cover the exclusions and must pass unchanged.

Constraints:

- runtime-kotlin rules: no `//` or non-KDoc block comments, KDoc only on interfaces, file line ceilings, no inline FQNs, wire keys declared once.
- `GOAL_INTAKE_FEATURE_SPECS_MARKER` is untouched here (subtask 5). No runtime state relocation (subtask 3) and no legacy config copy (subtask 3).
- Never delete or modify user project files. Do not run builds, tests, check, or any install command in implement; validate owns `./gradlew check`.

## Validation Strategy

The validate phase runs `./gradlew check` in `runtime-kotlin`, including repoTest architecture guards. This phase does not run it.

## Next Path

```bash
skill-bill goal SKILL-441
```
