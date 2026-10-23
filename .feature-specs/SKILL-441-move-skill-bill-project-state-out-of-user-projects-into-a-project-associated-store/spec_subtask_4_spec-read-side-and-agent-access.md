# SKILL-441 subtask 4: read spec bundles through the store and grant agents store access

## Scope

Make every spec-bundle reader and the governed spec path contract accept store bundles while still
accepting legacy `./.feature-specs/` bundles, and add the store directory to every agent launch.
Readers land before writers (subtask 5), so this commit works on its own: nothing writes the store
yet, and the legacy fallback keeps current runs working. The legacy fallback is permanent because
in-flight and blocked legacy goals must finish in place.

## Acceptance Criteria

1. The governed spec path contract accepts a store-relative form and the legacy form.
   The store-relative form is `specs/<KEY>-<slug>/<file>.md`, resolved against `ProjectStorePort.resolve(repoRoot).root`. The legacy form `.feature-specs/<...>.md` keeps resolving against the checkout root.
   In runtime-application `FeatureTaskGovernedSpecPathResolution.kt`, `resolveFeatureTaskGovernedSpecPath` normalizes an absolute path inside `ProjectStoreLocation.specsRoot` to the store-relative form and an absolute path inside `<checkout>/.feature-specs` to the legacy form, and returns `OutsideRepository` only for paths in neither place. In runtime-domain `FeatureTaskExecutionIdentityPolicy.kt` (near lines 96 and 117), the prefix validation accepts both `specs/` and `LegacyProjectStatePaths.FEATURE_SPECS_PREFIX`.
   In `orchestration/contracts/feature-task-execution-identity-schema.yaml`, the `governed_spec_path` pattern becomes an alternation of the two prefixes, keeping the existing negative-lookahead guard on each alternative. `contract_version` stays `"0.1"` because the change only widens the accepted set, so existing rows stay valid and nothing is quarantined. The rationale is recorded for history as decision D1 by the phase that owns `agent/decisions.md`. `orchestration/contracts/decomposition-manifest-schema.yaml` (near line 4) describes the parent spec as living in the project store under `specs/`, with `.feature-specs/` accepted for legacy bundles.
   Audit confirms by reading both Kotlin files and both schemas.
2. Issue-key and manifest discovery search the store first and the legacy directory second.
   runtime-infra/workflow `FileSystemFeatureSpecPathResolver.kt` scans `<specsRoot>/<KEY>-*/spec.md`, then `<checkout>/.feature-specs/<KEY>-*/spec.md` (excluding `done/` as today). A store match wins over a legacy match for the same key. Two matches in the same root keep returning `Ambiguous`. `NoMatch.specsRoot` reports the store specs root. The `FeatureSpecPathResolveInput` result types in runtime-ports carry an origin (`STORE` or `LEGACY`) so subtask 5's intake can treat a legacy-only match as intake.
   `FileSystemDecompositionManifestFileStoreDiscovery.kt` (near line 32) and `DecompositionManifestBundleJournal.kt` (near lines 104 and 109) recognize a bundle when its directory is under the store specs root or under a legacy `.feature-specs` directory; the walk-up uses `LegacyProjectStatePaths.FEATURE_SPECS_DIRECTORY` instead of a private `FEATURE_SPECS_DIRECTORY_NAME`.
   Path consumers are criterion 3. Audit confirms by reading the resolver, the port result types and the two discovery files.
3. Manifest spec paths and bundle consumers resolve through the store, not by joining the checkout root.
   runtime-application `DecompositionManifestWriterPaths.resolvedParentSpecPath(repoRoot, parentSpecPath)` resolves a `specs/...` path against the store root and a legacy `.feature-specs/...` path against the checkout root; an absolute path is used as is. `DecompositionManifestRuntimeState.kt` (near line 23) treats both `specs/done/` and `.feature-specs/done/` as archived.
   runtime-infra/workflow `FileSystemFeatureTaskRuntimeRunInvariantsSource.kt` (near lines 61, 102 and 126) and runtime-application `SpecIntentProjectionResolver.kt` (near line 108) resolve and list spec bundles through the same rule: store bundles under `specsRoot`, legacy bundles under `<checkout>/.feature-specs`.
   Writing new paths in the store form is subtask 5. Audit confirms by reading the four files and finding no `repoRoot.resolve(".feature-specs")` join left in them.
4. Every agent launch gets the project store root as an additional writable directory.
   In runtime-engine, `PhaseRun.stepFacts(issueKey, attempt)` in `PhaseRunFacts.kt` sets `additionalWritableDirectories = listOfNotNull(projectStore.root, qaExecution?.workspace?.directory)`, using `ProjectStorePort.resolve` on the run's repository root, so launches from the main checkout and from linked worktrees get the same store. `PhaseStepIo.kt` and `DefaultPhaseRunner.kt` keep forwarding the field.
   Operations get the same: `OperationContext` in `Operation.kt` (near line 75) and `OperationStepRunner.kt` (near line 127) include the store root. `VerifyQaStep.kt` (near line 161) appends `qa.workspace.directory` to the existing list instead of replacing it. `BlockIncidentAgentPass.kt` (near line 49) passes the store root instead of `emptyList()`.
   The list holds no duplicates. Claude and Codex already turn the list into `--add-dir` flags; Cursor and Junie handling is subtask 5. Audit confirms by reading each of these sites.

## Criterion Sizing

1. About 150 production lines, 4 files, 3 areas (`orchestration/contracts`, `runtime-application`, `runtime-domain`). Fits.
2. About 160 production lines, 4 files, 2 areas (`runtime-infra/workflow`, `runtime-ports`). Fits.
3. About 130 production lines, 4 files, 2 areas (`runtime-application`, `runtime-infra/workflow`). Fits.
4. About 60 production lines, 7 files, 1 area (`runtime-engine`). Fits.

## Non-Goals

- Writing bundles to the store, the plan prompt, intake parsing, user-facing messages and the Cursor/Junie staging fallback (subtask 5).
- Migrating legacy bundles.

## Dependency Notes

Depends on subtask 1 (`ProjectStorePort`) and subtask 2 (`LegacyProjectStatePaths`). Subtask 5 depends on this one: its writers produce the store-relative paths these readers accept.

## Test Obligations

- Governed path: a store-relative path and a legacy `.feature-specs/` path both validate, and a path outside both is rejected. This covers the in-flight compatibility risk.
- Discovery: a key present in both store and legacy resolves to the store with origin `STORE`; a legacy-only key resolves with origin `LEGACY`.
- Launch facts: a phase launched from a linked worktree carries the store root, and `VerifyQaStep` keeps the store root alongside the QA workspace. This catches the replace-instead-of-append bug.
- Schema parity for the widened `governed_spec_path` pattern.
- Fixtures that hardcode `.feature-specs/...` (for example `DecompositionPlanningTestFixtures.kt`, `TestDecompositionManifestStore.kt`) keep legacy coverage or inject a temp store, whichever each test needs.

## Implementation Details

All facts below come from the preplan digest. Items marked "Assumption" were not in the digest; implement confirms them before relying on them.

### Constraints

- runtime-kotlin rules apply: no `//` or non-KDoc block comments, KDoc only on interfaces, production file line ceilings, no inline FQNs, wire keys declared once in a `*Keys` object, and the A1-A12/P1-P8 architecture guidelines. Engine and application code depends on ports only; infra classes are `@Inject` kotlin-inject classes.
- Subtask 1 owns `ProjectStorePort` (runtime-ports) and its `ProjectStoreLocation(projectId, root, specsRoot, configPath, runEvidenceRoot, trackingRoot, acceptanceRepairRoot)` model. Subtask 2 owns `LegacyProjectStatePaths`. This subtask consumes both and does not redefine them. Assumption: `LegacyProjectStatePaths` exposes `FEATURE_SPECS_DIRECTORY = ".feature-specs"` and `FEATURE_SPECS_PREFIX = ".feature-specs/"` and lives in a module that runtime-domain can see (runtime-domain or runtime-contracts). If subtask 2 put it somewhere runtime-domain cannot import, move the two string constants down to runtime-domain and keep one owner.
- The store-relative prefix `specs/` gets one owner next to `LegacyProjectStatePaths`, for example `ProjectStoreLayout.SPECS_PREFIX = "specs/"` and `SPECS_DIRECTORY = "specs"`. Assumption: subtask 1 may already define it on the store layout; reuse it if so.
- `ProjectStorePort.resolve` never throws on identity problems (subtask 1 guarantees a fallback key plus a diagnostics record). Callers here do not add their own error handling around it.
- Legacy acceptance is permanent. No reader may drop the `.feature-specs/` path.
- No writers change here. Plan prompts, intake parsing, user-facing messages, `PlanBundleAuthorization`, `FeatureSpecPreparationWriter` and the Cursor/Junie staging fallback belong to subtask 5.
- Do not bump the identity `contract_version`. The pattern only widens.
- Decision D1 (widen the governed path pattern, keep `0.1`) is written to `agent/decisions.md` by the phase that owns that file, not by implement.

### Ordered tasks

1. Shared path classification helper (serves AC1, AC2, AC3).
   Add one small application-level helper, for example `ProjectSpecPaths` in runtime-application next to `FeatureTaskGovernedSpecPathResolution.kt`, with:
   - `classify(absolutePath, store: ProjectStoreLocation, checkoutRoot): SpecPathLocation?` returning `Store(relative)` when the real path sits under `store.specsRoot`, `Legacy(relative)` when it sits under `<checkoutRoot>/.feature-specs`, else `null`.
   - `resolve(specPath: String, store, checkoutRoot): Path` returning the absolute path for a `specs/...` value (against `store.root`), a `.feature-specs/...` value (against `checkoutRoot`), or an absolute value as is.
   This is the single rule criteria 1 and 3 reuse, so the four consumer files share it rather than repeating prefix checks. Assumption: runtime-infra/workflow already depends on runtime-application; if it does not, place the helper in runtime-domain as pure path logic that takes `specsRoot` and `checkoutRoot` as plain `Path` arguments.

2. Governed spec path contract (AC1).
   - runtime-application `FeatureTaskGovernedSpecPathResolution.kt`: `resolveFeatureTaskGovernedSpecPath(repositoryEnclosingRootPort, repositoryRoot, specPath)` takes `ProjectStorePort` (new parameter or injected through its caller). It resolves the store with `ProjectStorePort.resolve(repositoryRoot)`, uses the helper from task 1 to normalize, returns the store-relative form for store paths, the legacy form for checkout paths, and `OutsideRepository` only when `classify` returns `null`. Update every caller to pass the port.
   - runtime-domain `FeatureTaskExecutionIdentityPolicy.kt` (near lines 96 and 117): the prefix check accepts `ProjectStoreLayout.SPECS_PREFIX` or `LegacyProjectStatePaths.FEATURE_SPECS_PREFIX`. Keep the existing `.md` suffix and traversal guards on both.
   - `orchestration/contracts/feature-task-execution-identity-schema.yaml`: `governed_spec_path` pattern becomes `^(?:\.feature-specs/(?!<existing lookahead>)|specs/(?!<same lookahead>))[^\r\n]+\.md$`, copying the existing negative lookahead verbatim into each alternative. `contract_version` stays `"0.1"`.
   - `orchestration/contracts/decomposition-manifest-schema.yaml` (near line 4): reword the parent spec description to say it lives in the project store under `specs/`, with `.feature-specs/` accepted for legacy bundles. Description text only, no structural change.

3. Discovery result origin in runtime-ports (AC2).
   Add `enum class FeatureSpecOrigin { STORE, LEGACY }` next to `FeatureSpecPathResolverPort`. Add an `origin` field to the `SingleMatch` and `Explicit` results. `Ambiguous` keeps its current shape. `NoMatch.specsRoot` keeps its field name and now carries the store specs root. Assumption: `Explicit` origin is derived with the task 1 classifier, defaulting to `STORE` for an absolute path in neither place, since subtask 5 decides how explicit out-of-tree paths are treated. Update KDoc on the port interface only.

4. Store-first resolver (AC2).
   runtime-infra/workflow `FileSystemFeatureSpecPathResolver.kt`: inject `ProjectStorePort`. Scan `<specsRoot>/<KEY>-*/spec.md` first. If exactly one match, return `SingleMatch(origin = STORE)`. If more than one, return `Ambiguous`. If none, scan `<checkout>/.feature-specs/<KEY>-*/spec.md` excluding `done/` as today, with the same one/many rules and `origin = LEGACY`. If neither root matches, return `NoMatch(issueKey, specsRoot = store.specsRoot)`. A missing store specs directory counts as zero matches, not an error.

5. Manifest bundle discovery (AC2).
   - `FileSystemDecompositionManifestFileStoreDiscovery.kt` (near line 32) and `DecompositionManifestBundleJournal.kt` (near lines 104 and 109): recognize a bundle directory when it sits under `ProjectStorePort.resolve(repoRoot).specsRoot` or under a directory named `LegacyProjectStatePaths.FEATURE_SPECS_DIRECTORY`. Delete the private `FEATURE_SPECS_DIRECTORY_NAME` constant and use the shared one in the walk-up. Inject `ProjectStorePort` where the class has a repo root; where the journal only sees a bundle path, Assumption: the walk-up checks for the store specs root by comparing against an injected or passed `specsRoot`, falling back to the legacy directory-name check.

6. Manifest path consumers (AC3).
   - runtime-application `DecompositionManifestWriterPaths.kt`: `resolvedParentSpecPath(repoRoot, parentSpecPath)` delegates to the task 1 `resolve`, so `specs/...` resolves against the store root, `.feature-specs/...` against the checkout, absolute as is. This requires the store location; add it as a parameter and update callers. Leave `repoRelativePath` and `atExistingSpecBundle` write behavior alone except where they join `.feature-specs` for reading; their write-side move is subtask 5.
   - `DecompositionManifestRuntimeState.kt` (near line 23): the archived check accepts `specs/done/` and `.feature-specs/done/`.
   - runtime-infra/workflow `FileSystemFeatureTaskRuntimeRunInvariantsSource.kt` (near lines 61, 102, 126): replace each `repoRoot.resolve(".feature-specs")`-style join with the task 1 `resolve` (for a stored spec path) or with a listing over both roots (store first, then legacy). Inject `ProjectStorePort`.
   - runtime-application `SpecIntentProjectionResolver.kt` (near line 108): list children of `store.specsRoot` and then `<checkout>/.feature-specs`, store entries first, de-duplicated by bundle directory name with the store winning.
   - After this task, none of the four files contains a `resolve(".feature-specs")` join; legacy access goes through `LegacyProjectStatePaths` and the helper.

7. Store root on every agent launch (AC4).
   - runtime-engine `PhaseRunFacts.kt`: `PhaseRun.stepFacts(issueKey, attempt)` sets `additionalWritableDirectories = listOfNotNull(projectStore.root, qaExecution?.workspace?.directory).distinct()`. Assumption: `PhaseRun` is a data holder without injection. Resolve the store once where the `PhaseRun` is built (the runner or factory that already knows the repository root), using `ProjectStorePort.resolve(repoRoot)`, and carry the resolved `projectStoreRoot: Path` on `PhaseRun`. For a linked worktree, `ProjectStorePort` resolves through the common dir (subtask 1), so the same store comes back.
   - `PhaseStepIo.kt` (line 39) and `DefaultPhaseRunner.kt` (line 122): keep forwarding the field unchanged. Only touch them if the new `PhaseRun` field needs threading.
   - `Operation.kt` (near line 75, `OperationContext`) and `OperationStepRunner.kt` (near line 127): the operation context carries the store root and the step runner puts it first in `additionalWritableDirectories`, then any operation-specific entries, `.distinct()`.
   - `VerifyQaStep.kt` (near line 161): replace `listOf(qa.workspace.directory)` with `(inherited + qa.workspace.directory).distinct()`, where `inherited` is the list the step received.
   - `BlockIncidentAgentPass.kt` (near line 49): replace `emptyList()` with `listOf(projectStoreRoot)`, resolved with `ProjectStorePort.resolve` on the incident's repository root and injected into the class.
   - No change to `AgentRunCommandBuilders.kt`; Claude and Codex already map the list to `--add-dir`.

8. Test fixtures (supports all criteria).
   Give test code a fake `ProjectStorePort` that returns a location under a temp directory. Assumption: subtask 1 ships one in a testFixtures source set; reuse it if present. Update `DecompositionPlanningTestFixtures.kt` (application testFixtures, line 38), `TestDecompositionManifestStore.kt` (line 30) and other `.feature-specs` fixtures only where a constructor now needs the port. Leave the fixtures on legacy paths where the test is about legacy behavior, which keeps legacy coverage.

### Test obligations mapped to tasks

- AC1, task 2: one test in the governed path resolution suite. A store-relative path and a legacy `.feature-specs/` path both validate through `resolveFeatureTaskGovernedSpecPath` and the identity policy, and an absolute path outside both roots returns `OutsideRepository`. Realistic bug: the legacy branch is dropped and in-flight goals fail identity validation.
- AC1, task 2: update the existing identity schema parity test so the expected `governed_spec_path` pattern is the alternation. Realistic bug: the YAML and the Kotlin policy disagree, so rows pass one check and fail the other.
- AC2, task 4: one `FileSystemFeatureSpecPathResolver` test with a temp store and checkout. A key present in both roots returns `SingleMatch(origin = STORE)` pointing at the store file; a legacy-only key returns `origin = LEGACY`. Realistic bug: the legacy scan wins or the origin is never set, so subtask 5 cannot detect legacy intake.
- AC4, task 7: one `PhaseRun.stepFacts` test with a fake store and a repo root that is a linked worktree path, asserting the store root is in `additionalWritableDirectories`. Realistic bug: the store is resolved from the worktree path instead of the common dir.
- AC4, task 7: one `VerifyQaStep` test asserting the launch list contains both the store root and the QA workspace directory. Realistic bug: replace instead of append drops store access during QA.
- No new tests for tasks 3, 5, 6 beyond fixture updates. The archived-check and listing changes are covered by existing manifest tests once fixtures run against the shared helper; the resolver and governed-path tests already exercise the helper's two branches.
- Tests run only in the validate phase (`./gradlew check` in `runtime-kotlin`).

## Validation Strategy

The validate phase runs `./gradlew check` in `runtime-kotlin`. This phase does not run it.

## Next Path

```bash
skill-bill goal SKILL-441
```
