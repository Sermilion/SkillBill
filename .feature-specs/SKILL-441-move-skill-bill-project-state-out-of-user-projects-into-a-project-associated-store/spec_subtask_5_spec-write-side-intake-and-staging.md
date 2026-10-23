# SKILL-441 subtask 5: write spec bundles to the store, treat legacy specs as intake, stage for Cursor and Junie

## Scope

Switch every spec-bundle writer and the plan prompt to the project store, make a new launch on a
legacy `./.feature-specs/` spec replan into the store, add the staging fallback for providers that
cannot write outside the checkout, and update user-facing texts that still name `.feature-specs/`.
After this subtask, new goals leave no spec files in the project except a transient staging
directory for Cursor or Junie.

In-flight and blocked legacy goals keep their legacy governed paths and finish in place through the
readers from subtask 4.

## Acceptance Criteria

1. Spec preparation and manifest writing produce store bundles with store-relative manifest paths.
   runtime-engine `FeatureSpecPreparationWriter.kt` (near lines 52 and 69) writes `<ProjectStoreLocation.specsRoot>/<KEY>-<slug>/spec.md` instead of `<repoRoot>/.feature-specs/<dir>/spec.md`, creating the directories it needs.
   runtime-application `DecompositionManifestWriterPaths.repoRelativePath` becomes a store-aware relativizer: a path under `specsRoot` is written as `specs/<KEY>-<slug>/<file>`, and a path under `<checkout>/.feature-specs` keeps its legacy relative form so a legacy goal's manifest stays legacy. `DecompositionManifest.atExistingSpecBundle(repoRoot, fileStore)` uses `specsRoot` as the active root and remaps archived bundles to `specs/done/`; a legacy bundle keeps the `.feature-specs/done/` remap.
   runtime-engine `StandalonePlanRun.kt` (near lines 380 to 391) compares and resolves bundle spec paths through the same rule instead of `root.resolve(specPath)`.
   Audit confirms by reading the writer, the writer paths and the comparisons.
2. The plan prompt names the store bundle, and plan bundle authorization accepts only the governed bundle location.
   In runtime-engine `AgentPlanStrategy.kt` (near lines 123, 126, 154, 185, 197 and 207), the spec bundle requirement names the absolute bundle directory `<specsRoot>/<KEY>-<slug>/` as the only writable location for the bundle and shows manifest example paths in the store-relative form `specs/<issue key>-<slug>/spec.md` and `specs/<issue key>-<slug>/spec_subtask_<id>_<slug>.md`. For a legacy in-flight goal whose governed path starts with `.feature-specs/`, the prompt keeps naming that legacy directory. For a staged provider (criterion 4) it names the staging bundle directory instead.
   `PlanBundleAuthorization.kt` drops `SPECS_DIRECTORY = ".feature-specs"`. It authorizes writes in exactly one bundle directory: under `specsRoot.toRealPath()` for store goals, under `<checkout>/.feature-specs` for legacy goals, or under the staging bundle for staged providers. It keeps the "more than one bundle" refusal for each root.
   Audit confirms by reading both files.
3. A new launch whose intake is a legacy spec path is treated as intake and replanned into the store, while legacy in-flight goals resume in place.
   In runtime-contracts `IssueAndFeature.kt`, `GOAL_INTAKE_FEATURE_SPECS_MARKER` is removed and `looksLikeGoalIntakeToken` uses `LegacyProjectStatePaths.FEATURE_SPECS_PREFIX` and also recognizes absolute paths under the store specs root. runtime-engine `GoalIntake.kt` (near lines 47 and 48), `GoalIntakePreparation.kt` (near lines 157, 179 and 209, where `const val FEATURE_SPECS` is removed) and `IntakeIssueKeys.kt` (`FEATURE_SPECS_DIRECTORY`, near line 12) parse tokens through the same owner.
   When the token or the issue-key lookup (origin `LEGACY` from subtask 4) points at a legacy spec and an in-progress or blocked workflow already records that governed spec path, the launch resumes that workflow in place. Otherwise the legacy spec's content and its issue key become the intake for a new plan that writes a new bundle in the store; the legacy file is left untouched. The launch never blocks on a legacy path. `PhaseRunIntakeResolver.kt` applies the same rule for standalone phase runs.
   Audit confirms by reading these five files.
4. Providers that cannot write outside the checkout get a staging bundle that the runtime promotes into the store at phase completion.
   A capability flag beside the agent provider identity in runtime-ports (assumption: the existing provider model; implement confirms) states whether the provider honors `additionalWritableDirectories`. Claude and Codex declare true. Cursor (`CursorAgentRunCommandBuilder.kt`, `--workspace` only) and Junie (`JunieAgentRunCommandBuilder.kt`, `--project` only) declare false. If implement finds a documented extra-directory flag in the installed cursor-agent or junie CLI, that builder passes the store root with it and declares true instead.
   For a false provider, the plan phase's bundle directory is `<checkout>/.skill-bill/staging/<workflowId>/specs/<KEY>-<slug>/`. When the plan phase completes and before the bundle is validated, runtime-engine copies every file in that staging bundle into `<specsRoot>/<KEY>-<slug>/` with per-file temp-and-move writes, emits one `RuntimeDiagnostics.warning` `seam=agent_spec_staging value_expected=<store bundle> value_used=<staging bundle> cause=provider_without_additional_directories provider=<name>`, and deletes only `<checkout>/.skill-bill/staging/<workflowId>/`.
   A promotion failure emits a warning with the cause, leaves the staging directory in place, and continues to normal bundle validation, which reports a missing or incomplete bundle the usual way. Promotion never deletes any other project file. Subtask 2 already keeps `./.skill-bill/` out of commits.
   Audit confirms by reading the capability flag, both builders and the promotion step.
5. User-facing texts and KDoc name the project store instead of `.feature-specs/`.
   These messages name the store specs root (absolute, from `ProjectStoreLocation.specsRoot`) and mention `skill-bill project path` where they tell the user where to look: `StandalonePlanRun.kt` (near line 247), `FeatureTaskRuntimeExecutionEntry.kt` (near line 90), `FeatureTaskRuntimeDecompositionPlanner.kt` (near line 45, the reason text), `PhaseRunIntakeResolver.kt` (near line 47) and runtime-cli `FeatureTaskRuntimeCliFormatting.kt` (near line 20, the `UsageError` text).
   The KDoc in runtime-engine `GoalIntakeAdmission.kt` (near line 9, `.feature-specs/done/`) and runtime-ports `SpecScratchStore.kt` describes store bundles, with legacy `.feature-specs/` mentioned only as the in-place compatibility case.
   Audit confirms by reading each text.

## Criterion Sizing

1. About 150 production lines, 3 files, 2 areas (`runtime-engine`, `runtime-application`). Fits.
2. About 120 production lines, 2 files, 1 area (`runtime-engine`). Fits.
3. About 180 production lines, 5 files, 2 areas (`runtime-contracts`, `runtime-engine`). Fits.
4. About 240 production lines, 6 files, 3 areas (`runtime-ports`, `runtime-infra/launcher`, `runtime-engine`). Fits.
5. About 40 production lines, 7 files, 3 areas (`runtime-engine`, `runtime-cli`, `runtime-ports`). Fits.

## Non-Goals

- Migrating legacy bundles or deleting legacy files.
- Desktop links, CLI `project path`, skill content and repository untracking (subtasks 6 and 7).

## Dependency Notes

Depends on subtask 2 (`LegacyProjectStatePaths`, staging exclusion) and subtask 4 (store-aware readers, discovery origin, launch directories). Subtask 6 consumes the store bundle locations for its bundle file list. Subtask 5 names `skill-bill project path` in messages; subtask 6 adds that command, and both land on the same branch before the PR.

## Test Obligations

- Prepare plus manifest write: a new bundle lands under the temp store with `specs/...` manifest paths and nothing appears under the checkout's `.feature-specs`.
- Intake: a legacy spec path with no active workflow replans into a store bundle; the same path with a blocked workflow resumes it. This is the backwards-compatibility rule.
- Staging: a Cursor-style provider's staged bundle is promoted into the store, the staging directory is removed, and no other checkout file changes; a failed promotion leaves staging and does not throw.
- Plan authorization rejects a write outside the single governed bundle.

## Validation Strategy

The validate phase runs `./gradlew check` in `runtime-kotlin`. This phase does not run it.

## Next Path

```bash
skill-bill goal SKILL-441
```

## Implementation Details

All paths are under `runtime-kotlin/` unless noted. Line numbers come from the preplan digest and may have shifted after subtasks 1 to 4; implement locates each site by symbol. `ProjectStorePort` and `ProjectStoreLocation` (subtask 1), `LegacyProjectStatePaths` and the `./.skill-bill/` staging exclusion (subtask 2), and the store-aware readers, discovery origin `LEGACY` and store directory in `additionalWritableDirectories` (subtask 4) are inputs. This subtask does not redefine them.

### Ordered tasks

1. One bundle-path rule (criterion 1). Add a small owner, `SpecBundlePaths` in runtime-application `decomposition/` next to `DecompositionManifestWriterPaths.kt`, with two functions: `relativize(location: ProjectStoreLocation, checkoutRoot: Path, path: Path): String` and `resolve(location, checkoutRoot, relative: String): Path`. A path under `location.specsRoot` relativizes to `specs/<rest>`. A path under `<checkoutRoot>/.feature-specs` keeps its `LegacyProjectStatePaths.FEATURE_SPECS_PREFIX` form. Resolving reverses this: `specs/...` resolves against `location.root`, a legacy prefix resolves against the checkout. Anything else is refused at this seam with the existing manifest path failure. Assumption for implement to confirm: subtask 4 may already have added an equivalent resolver for readers. If it did, extend that owner with `relativize` instead of adding a second one, so readers and writers share one rule.
2. Manifest writer paths (criterion 1). In `DecompositionManifestWriterPaths.kt`, `repoRelativePath(repoRoot, path)` delegates to `SpecBundlePaths.relativize`, and `resolvedParentSpecPath` delegates to `resolve`. Thread `ProjectStoreLocation` through the call sites. `DecompositionManifest.atExistingSpecBundle(repoRoot, fileStore)` uses `specsRoot` as the active root and remaps archived bundles to `specs/done/`. When the parent spec is a legacy path it keeps `<repoRoot>/.feature-specs` and the `.feature-specs/done/` remap. `DecompositionManifestRuntimeState.kt` (near line 23, `relative.startsWith(".feature-specs/done/")`) also treats `specs/done/` as archived, with the legacy literal taken from `LegacyProjectStatePaths`.
3. Preparation writer (criterion 1). runtime-engine `featuretask/prepare/FeatureSpecPreparationWriter.kt` (near lines 52 and 69) gets the `ProjectStoreLocation` from `ProjectStorePort` and writes `<specsRoot>/<KEY>-<slug>/spec.md`, creating parent directories. It returns the store-relative governed path (`specs/<KEY>-<slug>/spec.md`, the D1 form accepted by the identity contract after subtask 3 or 4). Writes use temp-file-and-move, as for other store files.
4. Standalone plan comparisons (criterion 1). runtime-engine `goalrunner/plan/StandalonePlanRun.kt` (near lines 380 to 391) replaces `root.resolve(specPath)` with `SpecBundlePaths.resolve` for every manifest and subtask spec path it compares or reads. `planReport(issueKey, repoRoot)` (near lines 393 to 405) keeps its shape. Subtask 6 adds the bundle file list.
5. Provider capability flag (criterion 4). Add `honorsAdditionalWritableDirectories: Boolean` to the agent provider identity model in runtime-ports. Assumption for implement to confirm: the provider enum or descriptor that the launcher builders key on lives beside `agentrun/model/AgentRunLauncherModels.kt`. Put the flag on that type rather than adding a new one. Claude and Codex declare `true`. Cursor and Junie declare `false`. Implement runs `cursor-agent --help` and `junie --help` if they are installed. If either documents an extra-directory flag, its builder (`CursorAgentRunCommandBuilder.kt` or `JunieAgentRunCommandBuilder.kt` in runtime-infra/launcher `agentrun/`) passes each `additionalWritableDirectories` entry with that flag and the provider declares `true`. If a CLI is not installed or has no such flag, keep `false` and record that in the implement summary.
6. Plan bundle target (criteria 2 and 4). Add one engine value in `runloop/planning/`, `PlanBundleTarget`, a sealed type with `Store(directory)`, `Legacy(directory)` and `Staged(stagingRoot, directory, storeDirectory)`. It is computed once per plan phase from the governed spec path, the provider capability and the workflow id. A governed path with the legacy prefix gives `Legacy(<checkout>/.feature-specs/<KEY>-<slug>)`. A provider with the flag `false` gives `Staged(<checkout>/.skill-bill/staging/<workflowId>, <stagingRoot>/specs/<KEY>-<slug>, <specsRoot>/<KEY>-<slug>)`. Every other case gives `Store(<specsRoot>/<KEY>-<slug>)`. Build the staging directory name from `LegacyProjectStatePaths` plus a `staging` segment constant owned there, not a new literal. Assumption: the workflow id reaches the plan slot through the existing run context, as `PhaseRunFacts.stepFacts` already sees issue key and attempt. If it does not, implement threads it from the run invariants.
7. Plan prompt (criterion 2). runtime-engine `slot/plan/AgentPlanStrategy.kt` (near lines 123, 126, 154, 185, 197 and 207) takes the `PlanBundleTarget`. The spec bundle requirement names `target.directory` as an absolute path and states it is the only writable location for the bundle. Manifest examples use `specs/<issue key>-<slug>/spec.md` and `specs/<issue key>-<slug>/spec_subtask_<id>_<slug>.md` for `Store` and `Staged`, and the legacy `.feature-specs/...` form for `Legacy`. A staged prompt tells the agent to write manifest paths in the store form, so promotion needs no rewrite.
8. Plan authorization (criterion 2). `runloop/planning/PlanBundleAuthorization.kt` drops `SPECS_DIRECTORY` and the `segments[0]` check. It authorizes a write only when the real path of the written file lies under `target.directory` after `toRealPath()` of its root. For `Store` the root is `specsRoot.toRealPath()`. It keeps the "more than one bundle" refusal per root: a write in a sibling bundle directory under the same root is refused with the existing message.
9. Staging promotion (criterion 4). Add `PlanBundleStagingPromotion` in runtime-engine `runloop/planning/`. It is called when the plan phase completes and before bundle validation, only for `Staged`. This covers both the goal plan-phase completion path and `StandalonePlanRun.settlePlan`, which the digest shows called at two sites (near lines 223 and 349). Call it once inside `settlePlan` or at its shared entry, not at both call sites. Assumption: the goal plan phase settles through code in `runloop/planning/` that `FeatureTaskRuntimeDecompositionPlanner` reaches. Implement places the call at the single seam shared by both paths if one exists. It copies each regular file under `target.directory` to the same relative path under `storeDirectory`, each through a temp file and an atomic move. It emits one `RuntimeDiagnostics.warning("seam=agent_spec_staging value_expected=<storeDirectory> value_used=<directory> cause=provider_without_additional_directories provider=<name>")`, then deletes `stagingRoot` recursively and nothing else. On any `IOException` it emits `RuntimeDiagnostics.warning` with the same seam, `cause=staging_promotion_failed`, and the throwable. It leaves staging in place, does not rethrow, and returns, so normal bundle validation reports the missing or incomplete bundle. It must refuse to delete a `stagingRoot` that does not sit under `<checkout>/.skill-bill/staging/`.
10. Intake token parsing (criterion 3). runtime-contracts `issuekey/IssueAndFeature.kt` removes `GOAL_INTAKE_FEATURE_SPECS_MARKER`. `looksLikeGoalIntakeToken` recognizes tokens that contain `LegacyProjectStatePaths.FEATURE_SPECS_PREFIX`, and absolute `.md` paths whose parent chain contains a `projects/<id>/specs/` segment. runtime-contracts cannot see the store port, so this is a lexical check. The engine checks the real store root. runtime-engine `goalrunner/intake/GoalIntake.kt` (near lines 47 and 48), `GoalIntakePreparation.kt` (near lines 157 and 179; remove `const val FEATURE_SPECS` near 209) and `phaserun/IntakeIssueKeys.kt` (remove `FEATURE_SPECS_DIRECTORY` near 12) all use `LegacyProjectStatePaths` and `ProjectStoreLocation.specsRoot`. The candidate lookup near line 179 checks `<specsRoot>/<token>/spec.md` first, then the legacy `<repoRoot>/.feature-specs/<token>/spec.md`.
11. Legacy intake routing (criterion 3). In `GoalIntakePreparation`, when the resolved spec is legacy, either as a token or as a lookup result with origin `LEGACY`, query the existing workflow store for an in-progress or blocked workflow whose governed spec path equals the legacy relative path. If one exists, resume it in place through the existing resume path. Otherwise build a fresh intake from the legacy spec's text and issue key, mark it as not-a-plan so it routes to the plan phase, and let tasks 3 and 7 write a new store bundle. Do not modify or delete the legacy file. Any error reading the workflow store or the legacy file degrades to a fresh intake with a `RuntimeDiagnostics.warning` (`seam=legacy_spec_intake value_expected=store_bundle value_used=<legacy path> cause=<reason>`). It never blocks. `PhaseRunIntakeResolver.kt` routes standalone phase runs the same way by calling the same helper. Put the decision in one function used by both.
12. Texts and KDoc (criterion 5). Messages in `StandalonePlanRun.kt` (near 247), `lifecycle/execution/FeatureTaskRuntimeExecutionEntry.kt` (near 90), `phase/planning/FeatureTaskRuntimeDecompositionPlanner.kt` (near 45), `PhaseRunIntakeResolver.kt` (near 47) and runtime-cli `featuretask/FeatureTaskRuntimeCliFormatting.kt` (near 20) name the absolute `specsRoot` and point to `skill-bill project path`. The CLI text has no store location at parse time, so it names `~/.skill-bill/projects/<project-id>/specs/` and the command. Update the KDoc in runtime-engine `goalrunner/model/GoalIntakeAdmission.kt` (near 9) and runtime-ports `workflow/specscratch/SpecScratchStore.kt` to describe store bundles, with legacy `.feature-specs/` named only as the in-place compatibility case. Implement then searches main sources for any remaining `.feature-specs` literal outside `LegacyProjectStatePaths` and routes it through that owner.

### Tests

Each test uses a temp home through the state-dir override (`resolveTelemetryStateDir`) and a temp checkout.

- Prepare plus manifest write (criterion 1). One runtime-engine test drives `FeatureSpecPreparationWriter` and the manifest writer for a new issue. It asserts the bundle exists under `<tempStore>/specs/<KEY>-<slug>/`, the manifest parent and subtask paths start with `specs/`, and `<checkout>/.feature-specs` does not exist. Realistic bug: the writer still joins the repo root and leaks files into the project.
- Legacy manifest stays legacy (criterion 1). One runtime-application test calls `SpecBundlePaths.relativize` with a `<checkout>/.feature-specs/...` path and asserts the legacy form. Realistic bug: a resumed legacy goal's manifest gets rewritten to `specs/...` and no longer resolves.
- Intake routing (criterion 3). One parameterized engine test with a legacy spec path. With no active workflow, it asserts a fresh plan intake targets the store and the legacy file bytes are unchanged. With a blocked workflow recording that governed path, it asserts that workflow resumes. This is the backwards-compatibility rule.
- Staging promotion (criterion 4). One test promotes a staged bundle, then asserts the files are in the store, staging is gone, the warning names `seam=agent_spec_staging`, and a sentinel checkout file is untouched. A second case makes the store directory unwritable or pre-creates a file where the directory should be. It asserts no exception, staging still present, and a warning recorded. Realistic bugs: promotion deletes too much, or a failure throws and blocks the phase.
- Authorization (criterion 2). One `PlanBundleAuthorization` test asserts a write in the target bundle is authorized and a write in a sibling bundle under the same root is refused. Realistic bug: any path under the specs root is accepted.
- Builders (criterion 4). Update `CursorAgentRunCommandBuildersTest.kt` only if task 5 adds an extra-directory flag. Otherwise no new builder test: a hardcoded `false` flag has no branch to protect.
- Existing tests and fixtures that hardcode `.feature-specs` for new-goal behavior (for example `DecompositionPlanningTestFixtures.kt`, `TestDecompositionManifestStore.kt`) move to the store form. Fixtures that model legacy goals stay legacy. Do not weaken legacy regression assertions.

### Constraints

- runtime-kotlin rules: no `//` or non-KDoc block comments, KDoc only on interfaces, production file line ceilings (split `AgentPlanStrategy.kt` or `StandalonePlanRun.kt` helpers into sibling files if a ceiling is hit), no inline FQNs, wire keys declared once in `*Keys` objects, and docs/architecture-guidelines.md A1-A12/P1-P8. Engine and application depend on ports only. New engine helpers get injected dependencies, not static file access.
- Every warning follows docs/observability-policy.md: `seam=... value_expected=... value_used=...` plus cause, bounded, with no file payloads.
- Never delete or modify user project files. The only deletion this subtask adds is `<checkout>/.skill-bill/staging/<workflowId>/`.
- Never block a launch on a legacy path, a missing store or a promotion failure.
- The SKILL-441 run itself is a legacy in-flight goal. Its `.feature-specs/` bundle must keep resolving and authorizing through the `Legacy` target.
- No compile, test or check runs in implement for validation purposes. The validate phase owns `./gradlew check`. Do not plan or run install, uninstall or install-sync commands.
