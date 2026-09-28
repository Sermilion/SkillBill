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
`bill-feature-guard-cleanup`, `bill-monitor`. Each tree is deleted in the same commit
that moves its text (next section), so no rule is left without a home. `bill-monitor` is
not an operation and its text moves nowhere. `skill-bill goal status` remains CLI-only;
remove its help text that names `bill-monitor`.

**Move each skill's text verbatim (scope revision, 2026-09-28).** The first attempt
blocked because SKILL-380 and SKILL-382 re-authored skill rules as new prompt constants
and dropped some (subtask sizing, the spec format contract, fork-parent base detection,
release tag and fetch rules, remediation blocker dispositions). Do not re-author or
summarize. Copy each deleted `content.md` body (below its frontmatter) byte for byte
into a directive resource that its owner loads into its prompt, following the existing
precedent (`runtime-engine/src/main/resources/skillbill/engine/operation/release/changelog-directive.md`
loaded by `ReleaseOperation`). Resources live under
`runtime-engine/src/main/resources/skillbill/engine/<owner package path>/`.

| Deleted skill | Owner that loads the text |
| --- | --- |
| `bill-feature` | already in `../../../skills/skill-bill/content.md` (SKILL-380 subtask 12); verify, move any missing section there |
| `bill-feature-spec` | `slot/plan` (`AgentPlanStrategy`) and `slot/preplan` (`AgentPreplanStrategy`) |
| `bill-code-review` | argument, target, and register sections into `../../../skills/skill-bill/content.md` `phase:review`; review rules into `slot/codereview` (`InlineReviewStrategy`, `DelegatedReviewStrategy`) |
| `bill-code-review-inline` (only if deleted) | `slot/codereview` inline review prompt |
| `bill-code-check` | Repair Window and Pack validation_gate into `slot/qualitygate/packbuild`; Routing into `../../../skills/skill-bill/content.md` `phase:validation` |
| `bill-pr-description` | `slot/pullrequest` (`PrDescriptionStrategy`) |
| `bill-boundary-history`, `bill-boundary-decisions` | `slot/writehistory` (`BoundaryHistoryStrategy`) |
| `bill-update-check` | `operation/updatecheck` |
| `bill-release` | `operation/release` (whole body; the existing changelog directive becomes part of it) |
| `bill-unit-test-value-check` | `operation/unittestvalue` |
| `bill-feature-guard` | `operation/featureguard` |
| `bill-feature-guard-cleanup` | `operation/featureguardcleanup` |
| `bill-pr-review-fix` | `operation/prreviewfix` (Phase 1 sections into the analysis directive; the rest into the thread/execution directive) |
| `bill-feature-verify` | `operation/verify` |

Allowed edits to the copied text, and nothing else:

- Drop a section only when the runtime now performs it mechanically: the
  `bill-feature-verify` Workflow State, Continuation Mode, and Telemetry sections; the
  `bill-pr-description` Telemetry section; the `bill-feature-spec` Shared Preparation
  Path, Output Rules manifest template, and Goal Runner Boundary sections (the runtime
  writer owns bundle writing). Any other drop needs a line in the census.
- Replace a retired skill name that tells the agent to invoke, run, or avoid that skill
  with its replacement (`skill-bill phase review`, `skill-bill phase validation`,
  `operation:<name>`), the same replacement the prompt section below applies.
- Where a re-authored Kotlin constant (`FeatureGuardPromptRules`,
  `FeatureGuardCleanupPromptRules`, `UnitTestValueCheckPromptRules`,
  `PrDescriptionPromptRules`, `BoundaryMemoryPromptRules`, `VerifyPromptSections`,
  `InlineReviewPromptSections`, `AgentPlanStrategy`'s rule text) restates a copied rule,
  delete the restatement and keep only runtime output-contract text in Kotlin. Where the
  copy conflicts with a constant, the copy wins: `SPEC_BUNDLE_REQUIREMENT` asks for one
  or more subtasks, not "at least two".

Write `census_subtask_2.md` in the spec folder: for each deleted skill, its target
resource path(s), every dropped section with its reason, and every name replacement.

**Surfaces the copy alone does not restore.**

- `phase:review` accepts the `pr`, `staged`, and `unstaged` targets that
  `StandaloneCodeReviewTarget` already parses, in addition to `HEAD`, `uncommitted`,
  and a sha.
- The `/skill-bill` dispatcher routes a standalone quality check to `phase:validation`.
- `operation:update-check` accepts `--include-prereleases` and `--format json` and passes
  them through; `SkillBillUpdateService` no longer hard-codes `includePrereleases = false`.

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
commit. The fixture diff contains only these name replacements and the moved directive
text.

**Stable labels (parent criterion 5).** These keep their current values, and
`../../../agent/decisions.md` records why (remote telemetry and stored rows key on them):

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

**Docs.** Rewrite `../../../AGENTS.md`, `docs/skill-source-generation.md`,
`docs/internal-skills-architecture.md`, `docs/getting-started.md`, and the README
slash-command tables. Document in `runtime-kotlin/ARCHITECTURE.md` and `AGENTS.md`
that `/skill-bill` is the only listed skill and how phases and operations are reached
through it.
Record the retirement in `runtime-kotlin/agent/decisions.md`.

Do not run `./install.sh` against the real home (parent constraint). The parent Next
path installs after merge.

## Acceptance Criteria

1. A test installs into a temporary `HOME` and asserts the listed catalog is exactly `skill-bill`. `skills/bill-feature` and `skills/bill-monitor` do not exist. No production doc tells the operator to invoke `/bill-monitor` or `/bill-feature` as a listed skill.
2. The trees listed in Scope are gone from `../../../skills`. `bill-code-review-inline` is gone if the SKILL-380 subtask 8 census found no caller, and otherwise remains with `internal-for: skill-bill`.
3. Every deleted name is in `InstallLegacySkillNames`, and a test installs over a home that has the old skills and asserts their links and installed copies are removed.
4. No production prompt tells an agent to invoke, run, or avoid a retired skill by its old name. The re-baselined fixtures differ from their previous baseline only by the name replacements and the moved directive text.
5. Telemetry `skill` values, the feature-verify `workflow_name` default, and the stored workflow skill label are unchanged.
6. No full-run, phase-run, or operation fixture baseline changes for any other reason in this subtask.
7. For every deleted skill in the Scope table except `bill-monitor`, its `content.md` body is present in the listed owner's directive resource or in `../../../skills/skill-bill/content.md`, differing only by the dropped sections and name replacements that `census_subtask_2.md` lists. Each drop outside the Scope's allowed list has a reason in the census.
8. Each directive resource is loaded by its owner, and a prompt test per owner asserts the built prompt contains a heading from that resource (for example `AgentPlanStrategy`'s prompt contains `## Subtask Sizing` and `## Spec Format Contract`). `SPEC_BUNDLE_REQUIREMENT` no longer says "at least two".
9. Kotlin prompt constants no longer restate rules that a directive resource now carries.
10. `phase:review` accepts `pr`, `staged`, and `unstaged` targets; the dispatcher routes a standalone quality check to `phase:validation`; `operation:update-check` passes `--include-prereleases` and `--format json` through. Each has a test.

## Non-goals

- A `commit_push` phase definition, plugin UI, worktree locks.
- Deleting platform-pack specialist `content.md` or native-agent generation.
- Removing `skill-bill new/show/fill/render` authoring CLI.
- Migrating `bill-monitor` to an operation.
- Deleting `bill-code-review-inline` while a production caller remains.
- Re-authoring, summarizing, or improving the moved skill text.
- Inventing rules that no deleted `content.md` states.

## Dependency notes

- Depends on subtask 1 (sidecars re-parented). Recheck install catalog tests at start. Local
  clone for Spotless.

## Validation strategy

Catch: a second listed skill; `/bill-monitor` still documented; a prompt still naming a
retired skill; a telemetry label renamed; stale links left by an install over an old
home; a skill rule lost in the move; a rule restated in both Kotlin and a resource.
Cover with the install-catalog test and its docs grep, a prompt-text grep test over
production sources, the legacy-cleanup install test, the per-owner prompt tests, and the
fixture diffs. Check the move with `git diff -M HEAD~1 -- skills/ runtime-kotlin/runtime-engine/src/main/resources/`
against `census_subtask_2.md`. Run
`cd runtime-kotlin && ./gradlew check` plus CLI and infra-skills. Run
`bill-unit-test-value-check` (the installed skill) on changed tests.

## Next path

Last subtask. `skill-bill goal SKILL-383` opens the PR; run `./install.sh` from a local
clone after merge.

## Spec Path

.feature-specs/SKILL-383-single-skill-catalog/spec_subtask_2_retire-listed-skills.md
