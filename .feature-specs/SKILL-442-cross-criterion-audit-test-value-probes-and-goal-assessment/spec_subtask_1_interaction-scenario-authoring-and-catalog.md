# SKILL-442 subtask 1 - interaction scenario authoring and catalog

## Scope

Parent criterion AC-001. Planning learns to author `## Interaction Scenarios` and `## Transitional Regressions`, the runtime gets a governed catalog contract and a parser for both sections, and the plan step's producer-side fix loop rejects malformed or missing sections. Binding the catalog to the repair state and tracing it belong to subtask 2. The goal-assessment use of parent sections belongs to subtask 5.

Shared section grammar, used by every criterion below. Both sections are markdown lists, one top-level numbered or bullet item per entry, because `GovernedSpecSectionParser`'s heading regex ends a section at any `###` heading. A scenario item starts with `IS-NNN.` (three digits) and carries labeled fields, each starting a sentence on the item's first line or an indented continuation line: `Criteria:` (comma-separated `AC-NNN` refs), `Subtasks:` (comma-separated subtask ids, parent spec only), `Entry point:`, `Preconditions:`, `Steps:`, `Outcome:`. A regression item starts with `TR-NNN.` and carries `Behavior:`, `Disabled by: subtask N.`, and `Restored by: subtask M.`. A regressions section whose only content is `None.` is an empty, valid list.

## Acceptance Criteria

1. `runtime-kotlin/runtime-engine/src/main/resources/skillbill/engine/featuretask/slot/plan/feature-spec-directive.md` tells the planner when and how to write both sections, in a few sentences added to the existing `Writing Acceptance Criteria` and `Spec Format Contract` sections rather than a new document.
   The text requires `## Interaction Scenarios` in every subtask spec with two or more criteria and in every authored parent spec whose subtasks share state, files, or user-facing entry points. It gives the item grammar from Scope, the limits (at most six scenarios per subtask spec, at most six per parent, each parent scenario naming two or more subtasks), and the rule that every criterion describing a failure, rejection, retry, or recovery gets a scenario that starts in that failure state and continues through the next dependent user action, with the two abstract examples from AC-001 ("the stored config is invalid, then submit the form, then the redirect happens"; "the cache is corrupt, then run the command, then the process exits").
   It requires `## Transitional Regressions` in every authored parent of a bundle with two or more subtasks, listing each shipped behavior a subtask disables until a later subtask restores it with both subtask ids, or `None.`, with the two examples from AC-001 (an old file format rejected before its migration lands; an endpoint removed before its replacement lands). It states that a spec with fewer than two criteria and no parent-level sharing needs no scenario section.
   `AgentPlanStrategy.specBundleRequirement` in `slot/plan/AgentPlanStrategy.kt` adds one sentence beside "The parent and every subtask spec need an Acceptance Criteria list" naming the scenario and regression sections. When `specRewritable` is false, the sentence says the operator-authored parent is not changed and needs neither section.
   No text names a language, framework, engine, or test runner. This criterion does not cover parsing or rejection (criteria 3 and 4). Audit confirms it by reading the directive and `specBundleRequirement`.
2. A Draft 2020-12 contract `orchestration/contracts/interaction-scenario-catalog-schema.yaml` governs the parsed catalog. Its top level requires `contract_version` (const `"0.1"`), `spec_digest` (64 hex characters), `scenarios`, and `transitional_regressions`, with `additionalProperties: false`.
   Each `scenarios` item requires `scenario_ref` (pattern `^IS-[0-9]{3}$`), `criterion_refs` (array of `^AC-[0-9]{3}$`, `minItems: 2`, `uniqueItems: true`), `subtask_refs` (array of integers at least 1, unique, empty for a subtask spec), `entry_point`, `preconditions`, `steps`, and `outcome` (non-blank strings). `scenarios` has `maxItems: 6`. Each `transitional_regressions` item requires `regression_ref` (pattern `^TR-[0-9]{3}$`), `behavior` (non-blank), `disabled_by_subtask` and `restored_by_subtask` (integers at least 1).
   `runtime-contracts` gains `INTERACTION_SCENARIO_CATALOG_CONTRACT_VERSION = "0.1"` and an `InteractionScenarioCatalogPayloadKeys` object holding every wire key above; no wire key appears as an inline literal elsewhere. `FeatureTaskRuntimeFailureCode` gains `INVALID_INTERACTION_SCENARIO_CATALOG`, registered in the code classification list in `FeatureTaskRuntimeFailureCode.kt` and in `runtime-domain/.../blockincident/BlockIncidentClassificationRule.kt` the same way `INVALID_ACCEPTANCE_CRITERION_AUDIT_RESULT_SCHEMA` is.
   A runtime-domain wire codec encodes the catalog model to that map and decodes it back, loud-failing with `INVALID_INTERACTION_SCENARIO_CATALOG` on any schema violation. This criterion does not parse markdown (criterion 3). Audit confirms it by reading the schema, the constant, the payload keys, the failure code registrations, and the codec.
3. A runtime-domain parser, placed beside `runtime-domain/src/main/kotlin/skillbill/review/spec/GovernedSpecSectionParser.kt` and reusing its section lookup, turns spec text into an interaction-scenario catalog model (the criterion 2 shape, with the caller-supplied spec digest) or a list of issues, each with a field path and a reason.
   It finds `## Interaction Scenarios` and `## Transitional Regressions` by case-insensitive heading prefix and reads the Scope grammar. It reports an issue for each of these: an item without an `IS-NNN.` or `TR-NNN.` label, a duplicate label, a missing or blank field, fewer than two distinct criterion refs, a criterion ref that does not resolve to a criterion of the same spec through `acceptanceCriterionIdentity` canonical refs, more than six scenarios, a parent scenario naming fewer than two subtasks or a subtask id the caller did not supply as known, a `Subtasks:` field on a subtask spec, and a regression whose restoring subtask is not later than its disabling subtask or names an unknown subtask.
   An absent section yields an empty list and a `present = false` flag for that section, never an issue. `None.` yields an empty regression list with `present = true`.
   This criterion does not decide when a section is required (criterion 4). Audit confirms it by reading the parser and its issue rules.
4. The plan step's existing producer-side fix loop rejects malformed or missing sections. `FeatureSpecPreparationWriter.verifyAuthored` in `runtime-engine/.../featuretask/prepare/FeatureSpecPreparationWriter.kt` runs the criterion 3 parser on every subtask spec and on the parent, next to `requireAcceptanceList`, and throws `InvalidFeatureSpecPreparationRequestError` with field path `subtasks[<index>].interaction_scenarios`, `parent.interaction_scenarios`, or `parent.transitional_regressions` and the first issue's reason.
   Presence rules: a subtask spec with two or more criteria must have `## Interaction Scenarios`. A parent the plan authored or rewrote (the run has no `protectedSpecSha256`) in a bundle with two or more subtasks must have `## Transitional Regressions`. A parent `## Interaction Scenarios` section is validated when present but never required by the runtime, because whether subtasks share state is a judgment the directive owns.
   An operator-authored parent (the run has a `protectedSpecSha256`) is never rejected for these sections. When such a parent lacks them or they fail to parse, the runtime records a `RuntimeDiagnosticsBestEffortWarning` naming the parent path and treats both as empty. The rewritable flag reaches `verifyAuthored` through `FeatureTaskRuntimeDecompositionPlanner.verifyAuthoredBundle` and `PlanDecompositionStop.recordAuthoredBundleOrRejection`.
   The rejection reason flows through the existing `PlanningOutputView.settleAuthoredBundle` block-in-phase path and `PlanResumeRules.persistedBlockResume` relaunch. No new block reason or budget is added. Audit confirms it by reading `verifyAuthored`, the planner and stop wiring, and the warning call.

## Criterion Sizing

1. About 40 production lines, 2 files, 1 area (`runtime-engine` plan slot). Fits.
2. About 170 production lines, 6 files, 3 areas (`orchestration/contracts`, `runtime-contracts`, `runtime-domain`). Fits.
3. About 220 production lines, 2 files, 1 area (`runtime-domain` review/spec). Fits.
4. About 90 production lines, 4 files, 2 areas (`runtime-engine` featuretask/prepare, `runtime-engine` runloop/planning). Fits.

## Interaction Scenarios

1. IS-001. Criteria: AC-003, AC-004.
   Entry point: the plan step settling through `feature_task_phase_complete` during a goal run.
   Preconditions: the authored subtask spec has three criteria and a scenario whose `Criteria:` names `AC-001, AC-009`, and AC-009 does not exist.
   Steps: the plan settles; `verifyAuthored` parses the bundle.
   Outcome: the plan is blocked in phase with field path `subtasks[0].interaction_scenarios` and a reason naming AC-009, and the plan relaunches through the existing fix loop with a fresh budget.
2. IS-002. Criteria: AC-001, AC-004.
   Entry point: a goal run whose parent spec is operator-authored, at the plan step.
   Preconditions: the parent has a protected digest and no scenario or regression sections.
   Steps: the plan prompt is composed, the plan settles, `verifyAuthored` runs.
   Outcome: the prompt does not ask for parent sections, the bundle is accepted, and one best-effort warning names the parent path.
3. IS-003. Criteria: AC-002, AC-003.
   Entry point: the parser called on a valid subtask spec, followed by the codec.
   Preconditions: a spec whose regressions section reads `None.`.
   Steps: parse, encode, decode.
   Outcome: the encoded map validates against the schema with an empty `transitional_regressions` list, and a decoded map with a seven-item `scenarios` list fails with `INVALID_INTERACTION_SCENARIO_CATALOG`.

## Non-Goals

- Building the catalog into the repair binding or tracing it (subtask 2).
- Assessors reading parent sections (subtask 5).
- Re-verifying bundles that were imported before this change. Verification runs where `verifyAuthored` runs today.

## Dependency Notes

First subtask. Subtasks 2 and 5 consume the parser and the catalog model.

## Test Obligations

- Contract parity test for `interaction-scenario-catalog-schema.yaml` against `INTERACTION_SCENARIO_CATALOG_CONTRACT_VERSION` and the codec output (governed parity).
- Parser test: a scenario citing a criterion ref absent from its spec is reported. Bug caught: a typo ref passes and the later trace has no owner to reopen.
- `verifyAuthored` test: a subtask spec with two criteria and no `## Interaction Scenarios` is rejected with `subtasks[0].interaction_scenarios`, while an operator-authored parent without sections is accepted. Bug caught: the presence rule applied to the wrong spec, either letting scenario-less subtasks through or making protected parents unplannable.

## Validation Strategy

The validate phase runs the runtime-kotlin Gradle `check`, including repoTest guards such as `WireVocabularyArchitectureTest`, `CommentAndInterfaceKdocArchitectureTest`, and `RetiredSkillNameSweepRepoTest`, which reads the directive. Nothing runs here.

## Next Path

```bash
skill-bill goal SKILL-442
```

## Implementation Details

The upstream preplan digest is the starting evidence. This plan implements only this subtask's AC-001 through AC-004, which together serve parent AC-001. Binding or persisting the catalog in acceptance repair state belongs to subtask 2. Assessment consumption belongs to subtask 5. No external service capability is required.

### Evidence and resolved implementation choices

Required guidance read for this plan: `docs/architecture-guidelines.md`, the Design Principles in `runtime-kotlin/ARCHITECTURE.md`, `docs/code-principles.md`, and `docs/runtime-command-guidance.md`. The following targeted source reads resolve implementability details left by the digest, rather than repeating its discovery:

- `runtime-domain/.../review/spec/GovernedSpecSectionParser.kt` exposes `sectionBody` and `parseListSection`. The latter flattens continuation lines and rewrites nested list items as `Subcriterion:` text. Preserve raw section bodies for field boundaries and malformed-item detection. Reuse section lookup, but do not rely on flattened list output to validate scenario fields. Existing criterion-list parsing can still use `parseListSection`.
- `runtime-domain/.../workflow/taskruntime/artifact/AcceptanceCriterionRepairStateWire.kt` exposes a codec through `FeatureTaskRuntimeWorkflowArtifactMap`, declared in `model/core/FeatureTaskRuntimeWorkflowArtifactMap.kt`. Follow that existing typed carrier for the new public codec instead of adding a public raw `Map<String, Any?>` signature. Keep map construction and field decoding internal. This resolves A12 without a new wrapper or guard exemption.
- `FeatureSpecPreparationWriter.verifyAuthored`, lines 100-140, already reads the parent and each manifest-selected subtask and requires their acceptance lists. Add section checks at that seam. `FeatureTaskRuntimeDecompositionPlanner.verifyAuthoredBundle`, in `runtime-engine/.../featuretask/phase/planning/`, delegates to it and currently has no protected-parent parameter.
- `PlanDecompositionStop.recordBundle`, lines 104-135, serves both authored and existing bundle admission. `resolveAuthoredBundle`, lines 267-294, is the other verification call site. Both have the run request and can pass `request.protectedSpecSha256 == null`. This is a confirmed local propagation path, not an assumption for implementation.
- The digest identifies the authoritative schema packaging path in `runtime-infra/contracts/build.gradle.kts`, the schema locator in `FeatureTaskRuntimeSchemaPaths.kt`, and the existing rejection route through `PlanningOutputView.settleAuthoredBundle` and `PlanResumeRules.persistedBlockResume`. Reuse those owners. The domain must not import the infrastructure schema loader.

The authored grammar in Scope is fixed. Presence flags describe parsing and are not catalog wire fields. Parent sharing and meaningful failure-state coverage remain planner and audit judgments. Runtime validation enforces the declared syntax, references, counts, and presence rules; it does not guess semantic sharing from file names or criterion keywords.

### Ordered tasks

1. Define the catalog contract and its shared vocabulary. Serves AC-002 and precedes tasks 2 and 3.
   - Add `orchestration/contracts/interaction-scenario-catalog-schema.yaml` as Draft 2020-12 with version `0.1`, the exact fields and constraints in AC-002, and closed top-level and item objects. Non-blank strings require a non-whitespace character. Specify any cross-field constraints as `x-coherence-checks`, with enforcement owned by the parser for same-spec membership, parent/subtask context, duplicate labels, and restoration ordering.
   - Add `InteractionScenarioCatalogPayloadKeys` and `INTERACTION_SCENARIO_CATALOG_CONTRACT_VERSION` in `runtime-contracts/.../contracts/workflow/featuretask/`. Shared production consumers are the domain codec and the infrastructure contract loader. Reuse existing owners for any already-declared shared envelope vocabulary instead of restating wire strings.
   - Register schema packaging and lookup in `runtime-infra/contracts/build.gradle.kts` and `.../locator/FeatureTaskRuntimeSchemaPaths.kt`, following the digest's acceptance-audit schema pattern.
   - Follow the governed sequence: schema, Kotlin version, parity coverage, then `INVALID_INTERACTION_SCENARIO_CATALOG` and its typed failure factory in `FeatureTaskRuntimeFailureCode.kt`. Register classification there and in `BlockIncidentClassificationRule.kt`. Complete the sequence before consumers land. No new exception subclass is needed.
   - Test obligation: governed schema/version/codec parity under task 6. Schema packaging must be observable through the production classpath loader, not a test-only source-file loader.

2. Add typed catalog models and a strict wire codec. Serves AC-002 and IS-003; depends on task 1.
   - Put scenario and regression models in an area-owned model package under `runtime-domain/.../review/spec/`, with catalog fields corresponding to the schema. Keep section-presence flags in the parser result rather than the wire catalog. The caller supplies the spec digest; parsing does not read files or derive execution identity.
   - Add `InteractionScenarioCatalogWire` in the existing `workflow/taskruntime/artifact` family. Public encode/decode operations use the existing `FeatureTaskRuntimeWorkflowArtifactMap` carrier and typed catalog model. Use the owning payload keys throughout.
   - Reject malformed objects and unsupported versions through the new failure code. Enforce all schema shape rules on decoding, including exact integer values without truncation, positive subtask numbers, unknown fields, duplicate criterion/subtask refs, reference patterns, digest shape, whitespace-only fields, and the six-scenario limit. Keep context-dependent spec membership checks in task 3. Encoding must not emit a catalog that decoding rejects.
   - Domain enforcement is structural and pure. The infrastructure parity test checks agreement with the actual schema; do not inject a schema validator into domain or introduce a new module dependency.
   - Test obligation: task 6's parity case validates a normal round trip and rejects a seven-scenario catalog with the typed code.

3. Parse the two authored sections. Serves AC-003 and IS-001/IS-003; depends on tasks 1 and 2.
   - Add `InteractionScenarioSectionParser` beside `GovernedSpecSectionParser.kt`. Inputs are spec text, its caller-supplied digest, and parent/subtask context with known manifest subtask IDs. Return a typed parsed result containing the catalog and separate presence flags, or issues carrying field path and reason. Do not use exceptions for ordinary markdown authoring issues.
   - Find headings through case-insensitive prefix matching with `sectionBody`. Read numbered or bullet top-level entries and indented continuations from the raw body. Recognize Scope's field labels at sentence starts and continuation starts, preserving non-blank field content. Reject malformed nonempty section content instead of silently treating it as an empty catalog. Recognize `None.` only as the entire trimmed regression section.
   - Collect issues for every rule listed in AC-003, including duplicate labels, missing/blank fields, unknown canonical criterion refs, excess scenarios, illegal or insufficient parent/subtask refs, unknown regression endpoints, and restoration not later than disabling. Resolve criteria through `acceptanceCriterionIdentity` applied to the same spec's acceptance list. Parent criterion refs refer to parent criteria, not a union of subtask criteria.
   - Missing sections return empty lists with false presence flags. Parent known IDs come from the selected manifest; a subtask has no authority to declare cross-subtask refs. A nonempty regression in subtask context cannot resolve known subtask IDs. Leave required-section decisions to task 5.
   - Validate successful catalog shape with task 2's shared structural rules so malformed authored values yield issues before encoding. Keep parser/model dependencies inward and avoid a package cycle between artifact and spec packages.
   - Test obligation: one boundary test rejects a scenario naming absent `AC-009` and preserves its field path and reason. Other distinct parser rejection branches get compact parameterized cases only where needed to prove the validator rules; do not repeat the same branch with different literals.

4. Teach planning to author the sections. Serves AC-001 and IS-002; independent of task 3 once the grammar is fixed.
   - Edit `runtime-engine/src/main/resources/skillbill/engine/featuretask/slot/plan/feature-spec-directive.md` only in its existing Writing Acceptance Criteria and Spec Format Contract sections. Add the required headings, Scope grammar, scenario caps, parent subtask references, failure-state continuation rule, exemptions, and both pairs of examples required by AC-001. Require `None.` when a required regression section has no entries.
   - Update `AgentPlanStrategy.specBundleRequirement` beside its acceptance-list sentence. Rewritable parents follow the authored-parent requirements. Protected parents remain unchanged and need neither section; subtask presence rules still apply. Phrase the protected-parent exception consistently with task 5.
   - Keep additions short and stack-agnostic. Semantic failure coverage and parent sharing are explicit instructions, not inferred parser rules.
   - Test obligations: no new exact-prose snapshots. Audit reads both prompt surfaces; validation retains the existing directive and retired-name checks.

5. Reject invalid authored bundles through the existing planning repair path. Serves AC-004 and IS-001/IS-002; depends on tasks 3 and 4.
   - Add a focused section-check helper in `runtime-engine/.../featuretask/prepare/`, invoked by `FeatureSpecPreparationWriter.verifyAuthored` alongside `requireAcceptanceList`. Reuse the text already read and compute its digest using the existing SHA-256 convention. Parse every selected spec with the manifest's known subtask IDs for parent context.
   - Extend `verifyAuthoredBundle` and `verifyAuthored` with explicit parent-rewritability input. Pass it at both `PlanDecompositionStop` verification call sites, including the shared authored/existing path. Do not add permissive defaults that silently lose protected-parent status.
   - Subtasks with at least two criteria require a scenario section. Validate present sections even below that threshold. Rewritable parents require a regression section for two or more subtasks; validate present scenario sections without guessing whether sharing makes them mandatory. Map the first issue to the exact AC-004 field path and existing `InvalidFeatureSpecPreparationRequestError` mechanism.
   - Protected parents never fail these section checks. If either section is absent or invalid, record one warning per verification naming the parent path and the reason, and treat both sections as empty for this verification. Return or deliver warnings through a narrow value/callback seam; the planner owns recording through injected `RuntimeDiagnostics` and `RuntimeDiagnosticsBestEffortWarning`. Do not catch unrelated failures or cancellation as missing sections.
   - Preserve the existing rejection-to-block-to-relaunch path and attempt budget. Existing bundles admitted through this seam receive the same rules; there is no new historical bundle scan or separate migration.
   - Test obligations: task 6 covers rejected subtask presence, protected-parent acceptance and warning, and the settlement/relaunch outcome for a bad criterion reference.

6. Add the few boundary regressions and repair affected fixtures. Serves AC-002 through AC-004; depends on tasks 1-5. Tests are authored here and executed only by the owning later phase.
   - Governed parity in `runtime-infra/contracts/src/test/.../workflow/featuretask/InteractionScenarioCatalogContractTest`: use the packaged schema loader, assert the version pin, validate the encoded parser result with `None.` regressions, and check that seven scenarios fail both schema validation and domain decoding with `INVALID_INTERACTION_SCENARIO_CATALOG`. This catches schema/codec divergence and protects IS-003.
   - Parser boundary coverage lives beside its domain owner. The absent-criterion case catches a typo that would leave interaction tracing without a valid owner. Use compact cases for distinct schema-backed rejection rules and presence/`None.` behavior; retain existing regression coverage.
   - Extend the engine writer/planning tests for the realistic bug where required-section enforcement lands on the wrong spec. First reject a two-criterion subtask without scenarios at `subtasks[0].interaction_scenarios`; then supply its valid section while leaving the protected parent sectionless, assert acceptance and one warning naming that parent. Include malformed protected sections in the same rule's case table.
   - Extend an existing planning settlement test for IS-001: an unknown criterion reference must become the field-specific in-phase rejection and preserve the existing fresh-budget relaunch route. Assert outcomes rather than helper call order. Avoid duplicating that route in several tests.
   - Update constructor call sites and authored fixtures affected by the new diagnostics and rewritability inputs. Supply valid scenarios in multi-criterion fixtures instead of relaxing production checks or weakening their assertions. Validation later runs the focused domain, engine, and contract tests plus the existing full checks and architecture guards specified above.

### Acceptance trace and phase constraints

- AC-001 uses the digest's two planning prompt owners, changed by task 4. Later audit observes the authoring requirements and protected-parent exception in their emitted text.
- AC-002 uses the existing schema packaging and typed artifact codec paths, changed by tasks 1 and 2. Later validation observes packaged contract parity, valid round-trip behavior, and typed rejection of invalid wire data.
- AC-003 uses the existing section lookup and canonical criterion identity, changed by task 3. Later validation observes issue fields and valid/absent section results. IS-003 crosses this parser boundary and the codec boundary.
- AC-004 uses the existing writer, planner, and planning-stop rejection chain, changed by task 5. Later validation observes IS-001's in-phase rejection/relaunch and IS-002's protected-parent acceptance with diagnostics. Task 6 supplies the boundary regressions for those outcomes.

Follow architecture rules A1-A12 and the section 5 review checklist. In particular, keep I/O in engine/infrastructure, typed models and pure parsing in domain, shared vocabulary with named production consumers, and diagnostics at the engine boundary. Add no speculative ports, forwarding interfaces, public raw maps, guard exemptions, baseline increases, or Kotlin comments forbidden by AGENTS.md. Keep new logic cohesive and within the existing file/package limits. Review and validation may repair production wiring, test setup, formatting, and lint while preserving behavior, assertions, and architecture rules.

This phase changes only this Implementation Details section. It runs no tests, build, or full checks. Implement owns repository end states and test authoring; later build and validate phases own execution evidence under their runtime briefings. Audit, review, history, commits, publication, and runtime environment refresh remain with their owners. No production success, test pass, or catalog consumer integration is claimed by this plan.
