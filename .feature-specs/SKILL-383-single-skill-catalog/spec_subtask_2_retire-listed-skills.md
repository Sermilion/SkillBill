# SKILL-383 Subtask 2 - Retire listed skills and delete bill-monitor

Parent spec: [spec.md](spec.md)
Issue key: SKILL-383

## Scope

Last catalog commit. After this subtask the listed catalog is exactly `skill-bill`.

**Inline worker.** If SKILL-380 subtask 8's census found no production caller of
`ParallelCodeReviewRunner`'s inline lane shape, delete that lane shape,
`PARALLEL_REVIEW_INLINE_NATIVE_WORKER`, and `skills/bill-code-review-inline`, and add
the name to `InstallLegacySkillNames`. Otherwise keep it as the `internal-for:
skill-bill` sidecar subtask 1 made it.

**Delete listed `skills/bill-*` trees** except `bill-code-review-inline` (see above):
`bill-feature`, `bill-feature-spec`, `bill-code-review`, `bill-code-check`,
`bill-pr-description`, `bill-boundary-history`, `bill-boundary-decisions`,
`bill-pr-review-fix`, `bill-unit-test-value-check`, `bill-update-check`,
`bill-release`, `bill-feature-verify`, `bill-feature-guard`,
`bill-feature-guard-cleanup`, `bill-monitor`. Every authored rule already lives in a
strategy or operation (SKILL-380 subtasks 8–11, SKILL-382). If a rule is found that has no home, stop and
block; do not port it here. `bill-monitor` is not an operation. `skill-bill goal status`
remains CLI-only; remove its help text that names `bill-monitor`.

**Agent add-on consumer.** Drop the `bill-feature` consumer SKILL-380 kept beside
`skill-bill`. Persisted add-on selections that record `bill-feature` still decode as
`skill-bill`, and each such read emits a field-adoption record.

**Skill classes.** Retire the `orchestration/skill-classes/*.yaml` entries whose only
match is a deleted skill (`code-review-shell`, `quality-check-shell`, `pr-description`,
`feature-verify`, and the `bill-feature` match in `feature-launch-warning`). Keep
`code-review-specialist` for pack specialists. Update `SkillClassLoaderTest`'s goldens
and `AuthoringContentMutation`'s skill-family map.

**Install cleanup.** Add every deleted name to `InstallLegacySkillNames` so an install
over an old home removes the stale links and installed copies.

**Prompts that name retired skills (planned re-baseline).** Replace retired skill names
in engine and application prompt text:

- prohibition lines naming `bill-code-check` or `bill-code-review` (build, validate,
  gate-proof, discipline, audit, output-contract, and last-commit review directives)
  name `skill-bill phase validation` or `skill-bill phase review`
- the `FeatureSpecPreparationWriter` default validation strategy and the
  `FeatureTaskRuntimePhaseProjectionShapes` text name `skill-bill phase validation`
- `ReviewSkillStructureValidatorContent`'s "run/invoke/spawn bill-…" rule and
  `ScaffoldContentStarters` follow the same replacement

Re-baseline every affected prompt, spec-writer, and phase-run/operation fixture in this
commit. The fixture diff contains only these name replacements.

**Stable labels (parent criterion 5).** These keep their current values, and
`agent/decisions.md` records why (remote telemetry and stored rows key on them):

- `skill` values in `LifecycleTelemetryPayloads`, `TelemetrySettingsLoading`,
  `ReviewStatsContractWorkflowPayloadMappers`, and `McpAdapterContracts`
- the feature-verify `workflow_name` default and its migration
- the workflow skill label written by `WorkflowStateWrites`

**Tests that read deleted trees.** Update or remove repo tests that read the deleted
`content.md` files (`SkillClassLoaderTest`, `AuthoringRenderSnapshotTest`,
`ExcludedRootAgentTreeAbsenceTest`, `QualityCheckRoutingTest`'s shell-content case,
`AuthoringContentMutation` entries, `RepoValidationRuntimeRepoChecksManifest`).

**Install catalog test.** The listed catalog after a temporary-`HOME` install is
`{skill-bill}`. The test also greps production docs for `/bill-monitor` and for
`/bill-feature` as a listed skill.

**Docs.** Rewrite `AGENTS.md`, `docs/skill-source-generation.md`,
`docs/internal-skills-architecture.md`, `docs/getting-started.md`, and the README
slash-command tables. Document in `runtime-kotlin/ARCHITECTURE.md` and `AGENTS.md`
that `/skill-bill` is the only listed skill and how phases and operations are reached
through it.
Record the retirement in `runtime-kotlin/agent/decisions.md`.

Do not run `./install.sh` against the real home (parent constraint). The parent Next
path installs after merge.

## Acceptance Criteria

1. A test installs into a temporary `HOME` and asserts the listed catalog is exactly `skill-bill`. `skills/bill-feature` and `skills/bill-monitor` do not exist. No production doc tells the operator to invoke `/bill-monitor` or `/bill-feature` as a listed skill.
2. The trees listed in Scope are gone from `skills/`. `bill-code-review-inline` is gone if the SKILL-380 subtask 8 census found no caller, and otherwise remains with `internal-for: skill-bill`.
3. Every deleted name is in `InstallLegacySkillNames`, and a test installs over a home that has the old skills and asserts their links and installed copies are removed.
4. No production prompt tells an agent to invoke, run, or avoid a retired skill by its old name. The re-baselined fixtures differ from their previous baseline only by the name replacements.
5. Telemetry `skill` values, the feature-verify `workflow_name` default, and the stored workflow skill label are unchanged.
6. No other full-run, phase-run, or operation fixture baseline is changed in this subtask.

## Non-goals

- A `commit_push` phase definition, plugin UI, worktree locks.
- Deleting platform-pack specialist `content.md` or native-agent generation.
- Removing `skill-bill new/show/fill/render` authoring CLI.
- Migrating `bill-monitor` to an operation.
- Deleting `bill-code-review-inline` while a production caller remains.
- Porting any rule not already ported by SKILL-380 or SKILL-382.

## Dependency notes

- Depends on subtask 1 (sidecars re-parented). Recheck install catalog tests at start. Local
  clone for Spotless.

## Validation strategy

Catch: a second listed skill; `/bill-monitor` still documented; a prompt still naming a
retired skill; a telemetry label renamed; stale links left by an install over an old
home. Cover with the install-catalog test and its docs grep, a prompt-text grep test
over production sources, the legacy-cleanup install test, and the fixture diffs. Run
`cd runtime-kotlin && ./gradlew check` plus CLI and infra-skills. Run
`bill-unit-test-value-check` (the installed skill) on changed tests.

## Next path

Last subtask. `skill-bill goal SKILL-383` opens the PR; run `./install.sh` from a local
clone after merge.

## Spec Path

.feature-specs/SKILL-383-single-skill-catalog/spec_subtask_2_retire-listed-skills.md
