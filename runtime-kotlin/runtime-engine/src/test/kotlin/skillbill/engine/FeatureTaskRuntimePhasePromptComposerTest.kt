
package skillbill.engine

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.model.phase.ValidationFindingSetProjection
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditPromptSections
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class FeatureTaskRuntimePhasePromptComposerTest {
  @Test
  fun `review prompt forwards selected execution mode through a parallel lane`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("review"),
      ) { copy(codeReviewMode = CodeReviewExecutionMode.INLINE) }

    assertContains(prompt, "Fix every Blocker and Major")
    assertFalse(prompt.contains("Run `bill-code-review"))
    assertFalse(prompt.contains("parallel:claude"))
  }

  @Test
  fun `initial preplan prompt excludes review mode, commit-PR, and finalization mandate text`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN),
      )

    assertContains(prompt, "non-blank value")
    assertContains(prompt, "produced_outputs", false, "preplan warns about produced_outputs shape")
    assertContains(prompt, "\"projection_kind\": \"preplanning_digest\"", false, "preplan teaches stuffed digest JSON")
    assertFalse(prompt.contains("bill-code-review mode:"), "review execution mode must not reach preplan")
    assertFalse(prompt.contains("Review execution mode"), "review execution directive must not reach preplan")
    assertFalse(
      prompt.contains("commit_push") && prompt.contains("Run no git command in this phase"),
      "commit/PR instructions must not reach preplan",
    )
    assertFalse(prompt.contains("PR URL"), "PR finalization language must not reach preplan")
    assertFalse(prompt.contains("boundary history"), "history finalization language must not reach preplan")
  }

  @Test
  fun `preplan value content carries the digest and the final object declares value prose`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN),
      )

    val valueExample =
      prompt.substringAfter("## Value content")
        .substringAfter("```json")
        .substringBefore("```")
    assertContains(valueExample, "\"projection_kind\": \"preplanning_digest\"", false, "value carries the digest")
    val finalObject = prompt.substringAfter("## Required final output")
    assertContains(finalObject, "\"value\":", false, "the final object must name value prose")
  }

  @Test
  fun `plan prompt names exactly the phase prose required fields`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
      )

    assertContains(prompt, "\"value\":", false, "the copyable shape example must name value prose")
    assertContains(prompt, "prompt", false, "plan may optionally carry prompt prose")
    assertContains(prompt, "Carry this JSON object as the value text", false, "plan teaches stuffed plan JSON")
    assertContains(
      prompt,
      "\"projection_kind\": \"executable_plan\"",
      false,
      "plan inner example names executable_plan",
    )
  }

  @Test
  fun `implement prompt names the implementation-receipt required fields`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
      )

    assertContains(prompt, "Carry this JSON object as the value text", false, "implement teaches stuffed receipt JSON")
    assertContains(prompt, "completed_task_ids")
    assertContains(prompt, "changed_paths")
    assertContains(prompt, "tests_executed")
    assertContains(prompt, "reconciliation_evidence")
    assertContains(prompt, "runtime-owned")
    assertContains(prompt, "omit it entirely")
    assertContains(
      prompt,
      "\"projection_kind\": \"implementation_receipt\"",
      false,
      "implement shows the stuffed receipt inner shape",
    )
    assertContains(
      prompt,
      "\"reconciliation_evidence\": { \"reconciled\": true",
      false,
      "implement shows the receipt evidence",
    )
    assertContains(
      prompt,
      "deviations entries are objects { \"ref\", \"note\" }",
      false,
      "implement shows the deviation object item shape",
    )
  }

  @Test
  fun `implement_fix prompt carries the repair receipt census shape and the unchanged scope prohibition`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX),
      )

    assertContains(prompt, "\"repair_receipt\": {")
    assertContains(prompt, "\"contract_version\": \"0.3\"")
    assertContains(prompt, "\"finding_id\": \"F-001\", \"outcome\": \"addressed\"")
    assertContains(prompt, "finding_id")
    assertContains(prompt, "Coverage matches on finding_id and outcome alone")
    assertContains(prompt, "Recommended optional fields")
    assertFalse(prompt.contains("HARD SIZE LIMITS enforced by the schema"))
    assertFalse(prompt.contains("no Kotlin backtick"))
    assertFalse(prompt.contains("over-length field is rejected"))
    assertFalse(
      prompt.contains("pre_fix_checkpoint_sha"),
      "The remediation base sha is runtime-owned and absent from the briefing, so asking for it can " +
        "only produce an unrepairable rejection loop.",
    )
    assertContains(prompt, "specialist narratives and raw review output are not")
    assertContains(prompt, "Do not re-apply the plan from scratch")
    assertContains(prompt, "repair_receipt")
  }

  @Test
  fun `verify_findings prompt carries the disposition census shape and envelope verdict`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS),
      )

    assertContains(prompt, "VERIFYING phase")
    assertContains(prompt, "\"findings_verified\" or \"no_findings_verified\"")
    assertContains(prompt, "Required example: {\"finding_id\":\"F-001\",\"disposition\":\"verified\"}")
    assertContains(prompt, "Recommended optional fields")
    assertFalse(prompt.contains("at least one finding is verified"))
    assertFalse(prompt.contains("HARD SIZE LIMITS enforced by the schema"))
    assertFalse(prompt.contains("no Kotlin backtick"))
    assertFalse(prompt.contains("over-length field is rejected"))
  }

  @Test
  fun `only validate may run the pack check gate`() {
    val ownershipTitle = "Validation ownership"
    val phasesRequiringValidationOwnership =
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
      )
    phasesRequiringValidationOwnership.forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)
      assertContains(prompt, ownershipTitle, false, "ownership title for $phaseId")
      assertContains(prompt, "Only the validate phase may run the pack validation gate", false, phaseId)
      assertContains(prompt, "./gradlew check", false, phaseId)
      assertContains(prompt, "must not compile, build,", false, phaseId)
    }

    val validatePrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      )
    assertFalse(
      validatePrompt.contains(ownershipTitle),
      "validate must not carry the non-validate forbid; it owns the gate",
    )
    assertContains(validatePrompt, "Discover the validation checks")
    assertFalse(validatePrompt.contains("Invoke `bill-code-check` exactly once"))
    assertFalse(validatePrompt.contains("Invoke bill-code-check for collect-all and confirmation"))

    val reviewPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW),
      )
    assertContains(reviewPrompt, "validate owns those")

    val buildPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD),
      )
    assertFalse(buildPrompt.contains(ownershipTitle), "build owns compile proof, not validate gate ownership")
    assertContains(buildPrompt, "pack build_command")
  }

  @Test
  fun `audit with gate-proof AC stays inspection-only`() {
    val criteria = listOf("detekt reports zero LongMethod issues under maxIssues 0")
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("audit", PromptComposerBriefingOptions(acceptanceCriteria = criteria)),
      )
    assertContains(prompt, "Validation ownership")
    assertContains(prompt, "Only the validate phase may run the pack validation gate")
    assertContains(prompt, "must not compile, build,")
    assertContains(prompt, AcceptanceAuditPromptSections.AUDIT_READONLY_EVIDENCE_SENTENCE)
    assertFalse(prompt.contains("require mechanical gate proof"))
    assertFalse(prompt.contains("MAY run the commands those criteria name"))
  }

  @Test
  fun `forward implement still forbids the pack gate even when ACs mention detekt`() {
    val criteria = listOf("detekt reports zero LongMethod issues")
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("implement", PromptComposerBriefingOptions(acceptanceCriteria = criteria)),
      )
    assertContains(prompt, "Only the validate phase may run the pack validation gate")
    assertContains(prompt, "must not compile, build,")
    assertFalse(prompt.contains("require mechanical gate proof"))
    assertContains(prompt, "do not run builds or tests here")
  }

  @Test
  fun `validate discovers project checks and returns a boolean result`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      )

    assertContains(prompt, "Discover the validation checks required by this project")
    assertContains(prompt, "Do not run pack validation_gate argv")
    assertContains(prompt, "Keep repairing in this same session")
    assertContains(prompt, "Settle completed only when every required check passes")
    assertContains(prompt, "verdict progress")
    assertContains(prompt, "no_progress")
    assertContains(prompt, "The runtime does not rerun the checks")
    assertContains(prompt, "## Required final output")
    assertFalse(prompt.contains("validated schema gate"))
    assertFalse(prompt.contains("validation_passed is true"))
    assertFalse(prompt.contains("Do not emit a phase envelope"))
    assertFalse(prompt.contains("runtime independently confirms"))
    assertFalse(prompt.contains("Invoke `bill-code-check` exactly once"))
    assertFalse(prompt.contains("cache_bypassing_collect_all_full_gate_command"))
  }

  @Test
  fun `build prompt names pack build_command and forbids collect-all and validate checklists`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD),
      ) { copy(packBuildCommand = "./gradlew compileKotlin") }
    assertContains(prompt, "./gradlew compileKotlin")
    assertContains(prompt, "collect_all_full_gate_command")
    assertContains(prompt, "skill-bill validate")
    assertContains(prompt, "bill-code-check")
    assertContains(prompt, "check --continue")
    assertContains(prompt, "do not emit build_receipt")
    assertContains(prompt, "up to three repair turns")
  }

  @Test
  fun `runtime-owned build prompt names the complete finding set`() {
    val finding = ValidationGateFinding("m", "t", "broken", "loc")
    val page =
      ValidationFindingSetProjection(
        findings = listOf(finding),
      )
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD),
      ) {
        copy(
          validationGateFindings = page,
          validationGateRepair = true,
          packBuildCommand = "./gradlew compileKotlin",
        )
      }
    assertContains(prompt, "## Runtime build gate findings")
    assertContains(prompt, "A prior gate run parsed these items")
    assertContains(prompt, "full open set for this repair turn")
    assertContains(prompt, "pack-declared build command")
    assertContains(prompt, "collect_all_full_gate_command")
    assertContains(prompt, "Do not spawn delegated subagents")
    assertContains(prompt, "module=m id=t location=loc message=broken")
    assertContains(prompt, "Gate repair — prose only, no phase-output schema")
    assertContains(prompt, "blast radius")
    assertFalse(prompt.contains("Required final output (validated schema gate)"))
    assertFalse(prompt.contains("Required produced_outputs shape: emit a build_receipt"))
  }

  @Test
  fun `full validate prompt carries no-suppression clause absent from non-validate phases`() {
    val validatePrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      )
    assertContains(
      validatePrompt,
      "Never silence findings with annotations, baselines, disabled rules, weakened configuration, or skipped tests",
    )
    assertContains(validatePrompt, "Discover the validation checks required by this project")
    assertFalse(validatePrompt.contains("Invoke `bill-code-check` exactly once"))
    assertFalse(validatePrompt.contains("Invoke bill-kotlin-code-check"))
    assertContains(validatePrompt, "## Required final output")

    val nonValidatePhases =
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
      )
    nonValidatePhases.forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)
      assertFalse(
        prompt.contains("Invoke bill-code-check for collect-all and confirmation"),
        "phase $phaseId must not carry the validate gate-invocation clause",
      )
      assertFalse(
        prompt.contains("Never silence findings with annotations, baselines, disabled rules"),
        "phase $phaseId must not carry the validate no-suppression clause",
      )
    }
  }

  @Test
  fun `review prompt preserves every durable execution mode unchanged`() {
    CodeReviewExecutionMode.entries.forEach { mode ->
      val prompt =
        composePhasePrompt(
          PROMPT_COMPOSER_ISSUE_KEY,
          promptComposerBriefingFor("review"),
        ) { copy(codeReviewMode = mode) }

      assertFalse(prompt.contains("Run `bill-code-review"))
      assertFalse(prompt.contains("bill-code-review mode:${mode.wireValue}"))
    }
  }

  @Test
  fun `review prompt lists the durable baseline-untracked inventory without CLI exclude flags`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("review"),
      ) {
        copy(
          codeReviewMode = CodeReviewExecutionMode.INLINE,
          reviewPassNumber = 1,
          baselineUntrackedPaths = listOf("z-before.tmp", "a-before.tmp"),
        )
      }

    assertContains(prompt, "Baseline-untracked review policy")
    assertFalse(prompt.contains("--baseline-untracked-exclude"))
    assertContains(prompt, "- `a-before.tmp`")
    assertContains(prompt, "- `z-before.tmp`")
  }

  @Test
  fun `the single review pass receives last-commit scope framing`() {
    val input =
      GoalSubtaskReviewInput(
        reviewBaseSha = "a".repeat(40),
        currentHeadSha = "b".repeat(40),
        trackedDelta = "scope-fingerprint:abc\n",
        ownedUntrackedPatches = "",
      )

    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("review"),
      ) {
        copy(
          codeReviewMode = CodeReviewExecutionMode.INLINE,
          reviewPassNumber = 1,
          goalSubtaskReviewInput = input,
        )
      }

    assertFalse(prompt.contains("scope-fingerprint:abc"))
    assertContains(prompt, "last commit `${input.currentHeadSha}`")
    assertFalse(prompt.contains("durable base `${input.reviewBaseSha}`"))
    assertContains(prompt, "resolves last-commit itself")
  }

  @Test
  fun `composes header briefing and output contract for every runtime phase`() {
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)

      assertContains(prompt, PROMPT_COMPOSER_ISSUE_KEY, false, "issue key for $phaseId")
      assertContains(prompt, "Phase: $phaseId", false, "phase header for $phaseId")
      assertContains(prompt, "# Feature-task-runtime phase briefing", false, "briefing body for $phaseId")
      assertContains(prompt, "feature_size: MEDIUM", false, "feature size for $phaseId")
      assertContains(prompt, "Scaling changes scope and verbosity only", false, "gate integrity for $phaseId")
      assertContains(prompt, PROMPT_COMPOSER_SPEC_REFERENCE, false, "spec reference for $phaseId")
      assertContains(prompt, "## Required final output", false, "output contract for $phaseId")
      assertContains(prompt, "\"completed\", \"blocked\", \"failed\"", false, "status enum for $phaseId")
      assertContains(prompt, "failure_disposition", false, "typed failure behavior for $phaseId")
      if (phaseId in PhaseSlot.CODE_REVIEW.steps) {
        assertContains(prompt, "Required final output (validated schema gate)", false, "envelope for $phaseId")
        assertContains(prompt, "\"phase_id\": must be \"$phaseId\"", false, "pinned phase id for $phaseId")
        assertContains(
          prompt,
          "\"contract_version\": must be exactly " +
            "\"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION\"",
          false,
          "contract version for $phaseId",
        )
        assertContains(prompt, "produced_outputs", false, "produced_outputs for $phaseId")
      } else {
        assertContains(prompt, "\"value\": non-blank prose", false, "minimal value field for $phaseId")
        assertFalse(prompt.contains("validated schema gate"), "$phaseId must carry only the minimal settlement")
        assertFalse(prompt.contains("\"phase_id\": must be"), "$phaseId must not pin the phase id")
        assertFalse(prompt.contains("\"contract_version\": must be"), "$phaseId must not pin the contract version")
        assertFalse(prompt.contains("\"derived_notes\""), "$phaseId must not offer derived_notes")
      }
      assertContains(
        prompt,
        "Do not read orchestration/contracts",
        false,
        "installed-runtime authority for $phaseId",
      )
    }
  }
}
