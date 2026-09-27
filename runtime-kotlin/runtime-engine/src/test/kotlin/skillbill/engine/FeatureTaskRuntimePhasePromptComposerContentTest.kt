
package skillbill.engine

import skillbill.engine.featuretask.slot.audit.AcceptanceAuditPromptSections
import skillbill.engine.featuretask.slot.pullrequest.PullRequestTemplateSearch
import skillbill.engine.featuretask.slot.writehistory.BoundaryMemoryPromptRules
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimePhasePromptComposerContentTest {
  @Test
  fun `each phase carries its own task directive`() {
    val preplanPrompt = composePromptForPhase("preplan")
    val planPrompt = composePromptForPhase("plan")
    val implementPrompt = composePromptForPhase("implement")
    val historyPrompt = composePromptForPhase("write_history")
    val commitPrompt = composePromptForPhase("commit_push")
    val prPrompt = composePromptForPhase("pr")

    assertContains(preplanPrompt, "scaled pre-planning digest")
    assertContains(preplanPrompt, "full preplan covering boundaries")
    assertContains(preplanPrompt, "Do not modify repository files during this phase.")
    assertContains(preplanPrompt, "non-blank value")
    assertContains(planPrompt, "Do not modify repository files during this phase.")
    assertContains(planPrompt, "upstream preplan value")
    assertContains(implementPrompt, "Reconcile the repository to the intended state")
    assertTrue(
      !implementPrompt.contains("Do not modify repository files during this phase."),
      "implement must not carry the plan directive",
    )
    assertContains(implementPrompt, "Mutating-phase idempotency contract")
    assertContains(implementPrompt, "implementation_receipt JSON")
    assertContains(implementPrompt, "Carry this JSON object as the value text")
    assertTrue(
      !implementPrompt.contains("reconciliation report missing or \"reconciled\" not true fails the schema gate"),
      "implement must not keep the sibling reconciled_state schema-gate prompt",
    )
    assertTrue(
      !planPrompt.contains("Mutating-phase idempotency contract"),
      "non-mutating plan phase must not carry the idempotency directive",
    )
    assertTrue(
      !historyPrompt.contains("Mutating-phase idempotency contract"),
      "non-mutating write_history phase must not carry the idempotency directive",
    )
    mapOf("write_history" to historyPrompt, "pr" to prPrompt).forEach { (phaseId, prompt) ->
      listOf("Invoke ", "bill-boundary-history", "bill-boundary-decisions", "bill-pr-description").forEach { skill ->
        assertFalse(prompt.contains(skill), "the $phaseId prompt must not name '$skill'")
      }
    }
    assertContains(historyPrompt, "Always write for `MEDIUM` and `LARGE` features.")
    assertContains(historyPrompt, BoundaryMemoryPromptRules.HISTORY_ENTRY_FORMAT)
    assertContains(historyPrompt, BoundaryMemoryPromptRules.DECISION_ENTRY_FORMAT)
    assertFalse(historyPrompt.contains("history_result"), "write_history must not ask the agent for history_result")
    assertContains(commitPrompt, "does not launch an agent")
    assertContains(commitPrompt, "records commit_sha")
    assertContains(prPrompt, PullRequestTemplateSearch.SEARCH_ORDER.joinToString(", ") { "`$it`" })
    assertContains(prPrompt, "`[<issue key>] <descriptive title>`")
    assertContains(prPrompt, "# How Has This Been Tested?")
    assertContains(prPrompt, "create or reuse the open")
    assertFalse(prPrompt.contains("pr_result"), "pr must not ask the agent for pr_result")
  }

  @Test
  fun `test-value discipline renders for plan implement and implement_fix with six element anchors`() {
    val presentPhases =
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
      )
    presentPhases.forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)
      assertContains(prompt, TEST_VALUE_DISCIPLINE_TITLE, false, "title for $phaseId")
      assertContains(prompt, "name the realistic bug", false, "nameable-bug element for $phaseId")
      assertContains(prompt, "critical paths", false, "critical-path element for $phaseId")
      assertContains(
        prompt,
        "observable behavior at boundaries",
        false,
        "boundaries / no structure-coupling element for $phaseId",
      )
      assertContains(prompt, "One strong test per rule", false, "one-test-per-rule element for $phaseId")
      assertContains(
        prompt,
        "empty test_obligations list is a valid",
        false,
        "empty test_obligations guidance for $phaseId",
      )
      assertContains(
        prompt,
        "parity tests or validator-backed rules",
        false,
        "regression / governed carve-out for $phaseId",
      )
    }
  }

  @Test
  fun `test-value discipline is absent from evaluator and non-producer phases`() {
    val absentPhases =
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      )
    absentPhases.forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)
      assertFalse(
        prompt.contains(TEST_VALUE_DISCIPLINE_TITLE),
        "phase $phaseId must not carry the test-value discipline section",
      )
    }
  }

  @Test
  fun `test-value discipline sits immediately after minimalism on mutating phases`() {
    listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
    ).forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)
      val minimalismIdx = prompt.indexOf("## Minimalism discipline")
      val testValueIdx = prompt.indexOf(TEST_VALUE_DISCIPLINE_TITLE)
      assertTrue(minimalismIdx >= 0, "minimalism present for $phaseId")
      assertTrue(testValueIdx > minimalismIdx, "test-value follows minimalism for $phaseId")
      val between = prompt.substring(minimalismIdx, testValueIdx)
      assertFalse(
        between.indexOf("\n## ", startIndex = 1) >= 0,
        "no other titled section between minimalism and test-value for $phaseId",
      )
    }

    val planPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
      )
    assertContains(planPrompt, TEST_VALUE_DISCIPLINE_TITLE)
    assertFalse(
      planPrompt.contains("## Minimalism discipline"),
      "plan must not render minimalism; test-value uses its own phase predicate",
    )

    val preplanPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN),
      )
    val ceremonyIdx = preplanPrompt.indexOf("## Runtime ceremony scaling")
    val briefingIdx = preplanPrompt.indexOf("# Feature-task-runtime phase briefing")
    assertTrue(ceremonyIdx >= 0 && briefingIdx > ceremonyIdx)
    assertFalse(preplanPrompt.contains(TEST_VALUE_DISCIPLINE_TITLE))
  }

  @Test
  fun `small prompts encode lighter ceremony and current unit review scope without skipping gates`() {
    val preplanPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("preplan", PromptComposerBriefingOptions(FeatureTaskRuntimeFeatureSize.SMALL)),
      )
    val reviewPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("review", PromptComposerBriefingOptions(FeatureTaskRuntimeFeatureSize.SMALL)),
      )
    val auditPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("audit", PromptComposerBriefingOptions(FeatureTaskRuntimeFeatureSize.SMALL)),
      )

    assertContains(preplanPrompt, "feature_size: SMALL")
    assertContains(preplanPrompt, "preplan_ceremony: light")
    assertContains(reviewPrompt, "review_scope: current_unit_of_work")
    assertContains(reviewPrompt, "current-unit-of-work review scope")
    assertContains(auditPrompt, "audit_ceremony: light")
    assertContains(auditPrompt, "must not skip or weaken review, audit, validation")
  }

  @Test
  fun `upstream outputs flow into the prompt through the briefing text`() {
    val prompt = composePromptForPhase("implement")

    assertContains(prompt, "### from: plan")
    assertContains(prompt, "Fixture plan prose for downstream implement and audit.")
    assertTrue(!prompt.contains("Phase produced a validated output."))
  }

  @Test
  fun `does not instruct the goal-continuation activation flow`() {
    val prompt = composePromptForPhase("plan")

    assertTrue(!prompt.contains("goal-continuation mode"))
    assertTrue(!prompt.contains("First execute this exact command"))
    assertContains(prompt, "do not call `skill-bill workflow continue`")
  }

  @Test
  fun `goal-continuation plan does not treat future acceptance work as a prerequisite`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("plan"),
      ) { copy(suppressDecomposition = true) }

    assertContains(prompt, "Goal-continuation planning constraint")
    assertContains(prompt, "Never include installer, uninstall, or")
    assertContains(prompt, "install-sync commands in the plan")
    assertContains(prompt, "`./install.sh`")
    assertContains(prompt, "it does not require that work to have already")
    assertContains(prompt, "Never block planning merely because a later implementation or validation action")
    assertContains(prompt, "genuinely missing input or an irreconcilable constraint")
    assertTrue(!prompt.contains("return a blocked plan"))
  }

  @Test
  fun `commit_push prompt forbids amending foreign commits and includes every dirty path`() {
    val prompt = composePromptForPhase("commit_push")

    assertContains(prompt, "Commit ownership")
    assertContains(prompt, "Never amend, reset, or restage a commit this runtime does not own")
    assertContains(prompt, "including `.feature-specs/`")
    assertTrue(!prompt.contains("Never list any `.feature-specs/`"))
    assertTrue(!prompt.contains("Feature-spec commit exclusion"))
    assertTrue(
      !prompt.contains("do not add, amend,"),
      "the blanket amend prohibition is replaced by a scope bound to runtime-owned commits",
    )
    assertTrue(!prompt.contains("The committed tree must contain no feature spec"))
  }

  @Test
  fun `commit ownership directive is absent on non-commit phases`() {
    val implementPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("implement"),
      )

    assertTrue(!implementPrompt.contains("Commit ownership"))
    assertTrue(!implementPrompt.contains("Feature-spec commit exclusion"))
  }

  @Test
  fun `a blank issue key loud-fails`() {
    assertFailsWith<IllegalArgumentException> {
      composePhasePrompt(" ", promptComposerBriefingFor("plan"))
    }
  }

  @Test
  fun `review names its schema-gate signal and audit names its remaining-criteria value`() {
    val reviewPrompt = composePromptForPhase("review")
    val auditPrompt = composePromptForPhase("audit")

    assertContains(reviewPrompt, "VERIFYING phase", false, "review names itself a verifying phase")
    assertContains(reviewPrompt, "\"findings\" array", false, "review names the findings signal")
    assertContains(reviewPrompt, "\"approved\" or \"changes_requested\"", false, "review names the verdict values")
    assertFalse(auditPrompt.contains("VERIFYING phase"), "audit carries no envelope verifying-signal addendum")
    assertAuditPromptNamesSignal(
      auditPrompt,
      "value carries the remaining acceptance criteria only",
      "the audit prose signal",
    )
    assertAuditPromptNamesSignal(auditPrompt, "explicit empty list", "the remaining-criteria completion contract")
    assertAuditPromptNamesSignal(
      auditPrompt,
      "verdict: omit it unless every criterion is met, then set satisfied",
      "the audit-specific verdict rule",
    )
  }

  @Test
  fun `audit requires meaningful test coverage and repairs without execution or handoff`() {
    val prompt = composePromptForPhase("audit")

    assertContains(prompt, "test cases whose assertions verify that behavior")
    assertContains(prompt, "Missing implementation, missing tests")
    assertContains(prompt, "mock-only interaction, or tautological assertion is not coverage")
    assertContains(prompt, "Repair every fixable gap in this same agent session")
    assertContains(prompt, "re-check the entire in-scope criterion list from the beginning")
    assertContains(prompt, "Do not spawn subagents, invoke repair skills, or hand findings")
    assertContains(prompt, "Validation owns test execution")
    assertTrue(!prompt.contains("TEST EXCLUSION"))
    assertTrue(!prompt.contains("free-form note prose"))
  }

  @Test
  fun `audit remaining-criteria briefing is identical across shipped platform pack gates`() {
    val briefing = promptComposerBriefingFor("audit")
    val baseline = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, briefing)
    val slugs = shippedPlatformPackSlugs()
    assertTrue(slugs.isNotEmpty(), "expected shipped platform packs under platform-packs/")
    (slugs + "unshipped-pack").forEach { slug ->
      val prompt =
        composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, briefing) {
          copy(
            packCollectAllCommand = "collect-all-$slug",
            packConfirmationGateCommand = "confirm-$slug",
            packBuildCommand = "build-$slug",
          )
        }
      assertEquals(baseline, prompt, "audit remaining-criteria contract forked for pack $slug")
      assertContains(prompt, AcceptanceAuditPromptSections.DIRECTIVE)
      assertTrue(!prompt.contains("collect-all-$slug"))
      assertTrue(!prompt.contains("confirm-$slug"))
      assertTrue(!prompt.contains("build-$slug"))
    }
  }

  @Test
  fun `review prompt is the producer seam for commit-focused accounting`() {
    val reviewPrompt = composePromptForPhase("review")

    assertContains(reviewPrompt, "\"commit_focused_accounting\"", false, "review names the accounting key")
    assertContains(reviewPrompt, "commit_sequence_digest", false, "the sequence identity is required")
    assertContains(reviewPrompt, "integration_terminal_outcome", false, "the integration terminal state is required")
    assertContains(reviewPrompt, "skipped_not_applicable", false, "the skipped outcome is in the named vocabulary")
    assertContains(reviewPrompt, "incomplete_lanes", false, "incomplete lanes are reported as non-clean coverage")
    assertContains(reviewPrompt, "OMITS the key entirely", false, "an inline pass omits rather than fabricates")
    assertFalse(
      composePromptForPhase("audit")
        .contains("commit_focused_accounting"),
      "only the review phase produces the accounting record",
    )
  }

  @Test
  fun `non-verifying phases carry no verifying-signal addendum`() {
    listOf("preplan", "plan", "implement", "validate", "write_history", "commit_push", "pr").forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)
      assertTrue(!prompt.contains("VERIFYING phase"), "$phaseId must not carry the verifying-signal addendum")
    }
  }

  @Test
  fun `a prior schema-gate failure is surfaced as a corrective directive on retry`() {
    val reason = "Audit phase reported 'completed' without a verification signal"

    listOf("review").forEach { phaseId ->
      val firstAttempt = composePromptForPhase(phaseId)
      val retry =
        composePhasePrompt(
          PROMPT_COMPOSER_ISSUE_KEY,
          promptComposerBriefingFor(phaseId),
        ) { copy(priorSchemaFailure = reason) }

      assertTrue(!firstAttempt.contains("REJECTED by the schema gate"), "$phaseId first attempt: no correction")
      assertContains(retry, "Previous attempt was REJECTED by the schema gate", false, "$phaseId retry: rejection")
      assertContains(retry, reason, false, "$phaseId retry carries the validator's reason verbatim")
    }
  }

  @Test
  fun `a retryable terminal envelope is prompted to retry, not told it was rejected`() {
    val reason = "Implement phase reported blocked: the target module does not compile on this branch."

    val retry =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("implement"),
      ) { copy(priorTerminalFailure = reason) }

    assertContains(retry, "reported a retryable block", false, "terminal retry names its own kind")
    assertContains(retry, reason, false, "terminal retry carries the reported reason verbatim")
    assertTrue(
      !retry.contains("REJECTED by the schema gate"),
      "a schema-valid terminal envelope must never receive the schema-correction directive",
    )
  }
}
