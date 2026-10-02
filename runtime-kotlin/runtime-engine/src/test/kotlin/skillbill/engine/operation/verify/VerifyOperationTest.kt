package skillbill.engine.operation.verify

import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.unittestvalue.UnitTestValueCheckPromptRules
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VerifyOperationTest {
  @Test
  fun `the first call parks the workflow at extract_criteria and confirm runs it through finish`() {
    VerifyOperationHarness().use { harness ->
      val parked = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose())
      val token = parked.token

      assertTrue(token.startsWith("wfv-"), token)
      assertTrue("Greets by name." in parked.proposalSummary, parked.proposalSummary)
      assertEquals("pending", harness.workflowStatus(token))
      assertEquals("extract_criteria", harness.currentStep(token))
      assertEquals(0, harness.rowCount("operation_proposals"))
      assertEquals(0, harness.rowCount("feature_task_workflows"))
      assertEquals(listOf(VerifyPromptSections.EXTRACT_CRITERIA_STEP), harness.runner.stepNames())
      assertTrue(harness.telemetry.started.isEmpty())

      val report = assertIs<OperationOutcome.Completed>(harness.confirm(token))

      assertTrue("APPROVE" in report.text && "Verify workflow: $token" in report.text, report.text)
      assertEquals("completed", harness.workflowStatus(token))
      assertEquals("finish", harness.currentStep(token))
      assertEquals(
        listOf(
          VerifyPromptSections.EXTRACT_CRITERIA_STEP,
          VerifyPromptSections.FEATURE_FLAG_AUDIT_STEP,
          VerifyPromptSections.CODE_REVIEW_STEP,
          UnitTestValueCheckPromptRules.REVIEW_STEP,
          VerifyPromptSections.COMPLETENESS_AUDIT_STEP,
          VerifyPromptSections.VERDICT_STEP,
        ),
        harness.runner.stepNames(),
      )
      assertEquals(1, harness.telemetry.started.size)
      assertEquals(listOf("completed"), harness.telemetry.finished.map { it.completionStatus })
      assertEquals(1, harness.telemetry.imports.size)
      val snapshot = harness.snapshot(token)
      assertEquals(
        mapOf(
          "collect_inputs" to "completed",
          "extract_criteria" to "completed",
          "gather_diff" to "completed",
          "feature_flag_audit" to "skipped",
          "code_review" to "completed",
          "unit_test_value_check" to "completed",
          "completeness_audit" to "completed",
          "verdict" to "completed",
          "finish" to "completed",
        ),
        snapshot.steps.associate { step -> step.stepId to step.status.wireValue },
      )
      assertTrue(snapshot.artifacts.keys.containsAll(ARTIFACTS), snapshot.artifacts.keys.toString())
    }
  }

  @Test
  fun `a step that edits a file fails the run and persists no receipt for that step`() {
    VerifyOperationHarness().use { harness ->
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose()).token
      harness.editDuring(VerifyPromptSections.CODE_REVIEW_STEP)

      val failed = assertIs<OperationOutcome.Failed>(harness.confirm(token))

      assertTrue("read-only" in failed.reason, failed.reason)
      assertEquals("failed", harness.workflowStatus(token))
      assertEquals("code_review", harness.currentStep(token))
      assertFalse("code_review_receipt" in harness.snapshot(token).artifacts)
      assertEquals(listOf("abandoned_at_review"), harness.telemetry.finished.map { it.completionStatus })
    }
  }

  @Test
  fun `a workflow the skill left at code_review resumes there when confirmed with its target`() {
    VerifyOperationHarness().use { harness ->
      val token = harness.seedSkillWorkflowAtCodeReview()
      val skillSession = harness.snapshot(token).sessionId
      assertTrue(skillSession.isNotBlank())

      assertIs<OperationOutcome.Completed>(harness.confirm(token, target = harness.range))

      assertEquals(
        listOf(
          VerifyPromptSections.CODE_REVIEW_STEP,
          UnitTestValueCheckPromptRules.REVIEW_STEP,
          VerifyPromptSections.COMPLETENESS_AUDIT_STEP,
          VerifyPromptSections.VERDICT_STEP,
        ),
        harness.runner.stepNames(),
      )
      assertEquals("completed", harness.workflowStatus(token))
      assertTrue(harness.telemetry.started.isEmpty())
      assertEquals(listOf(skillSession), harness.telemetry.finished.map { it.sessionId })
    }
  }

  @Test
  fun `neither the proposal nor the confirmed run changes the worktree or HEAD, inline or delegated`() {
    listOf("inline" to 0, "delegated" to 1).forEach { (mode, delegatedCalls) ->
      VerifyOperationHarness().use { harness ->
        val status = harness.status()
        val head = harness.headCommit()

        val parked = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose(mode = mode))
        assertEquals(status, harness.status(), mode)
        assertIs<OperationOutcome.Completed>(harness.confirm(parked.token), mode)

        assertEquals(status, harness.status(), mode)
        assertEquals(head, harness.headCommit(), mode)
        assertEquals(delegatedCalls, harness.delegatedReviews.size, mode)
      }
    }
  }

  @Test
  fun `each evaluator reads only its declared projection and the verdict reads only the receipts`() {
    VerifyOperationHarness().use { harness ->
      val parked = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose())
      assertIs<OperationOutcome.Completed>(harness.confirm(parked.token))

      val evaluator = { rubric: String -> setOf("criteria_summary", rubric, "diff_projection") }
      mapOf(
        VerifyPromptSections.EXTRACT_CRITERIA_STEP to emptySet(),
        VerifyPromptSections.FEATURE_FLAG_AUDIT_STEP to evaluator("feature_flag_policy"),
        VerifyPromptSections.CODE_REVIEW_STEP to evaluator("review_rubric"),
        UnitTestValueCheckPromptRules.REVIEW_STEP to evaluator("unit_test_value_rubric"),
        VerifyPromptSections.COMPLETENESS_AUDIT_STEP to evaluator("completeness_rubric"),
        VerifyPromptSections.VERDICT_STEP to
          setOf(
            "feature_flag_audit_receipt",
            "code_review_receipt",
            "unit_test_value_receipt",
            "completeness_audit_receipt",
            "diff_projection",
          ),
      ).forEach { (step, keys) ->
        assertEquals(keys, harness.runner.input(step).priorValues.keys, step)
      }
      val completenessRubric = harness.runner.input(VerifyPromptSections.COMPLETENESS_AUDIT_STEP).priorValues
      assertTrue("## Completeness Audit" in completenessRubric.getValue("completeness_rubric"))
    }
  }

  @Test
  fun `an interrupted run resumes at its current step without rerunning the settled ones`() {
    VerifyOperationHarness().use { harness ->
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose()).token
      harness.runner.failAt = VerifyPromptSections.COMPLETENESS_AUDIT_STEP
      assertFailsWith<IllegalStateException> { harness.confirm(token) }
      assertEquals("running", harness.workflowStatus(token))
      assertEquals("completeness_audit", harness.currentStep(token))
      harness.runner.inputs.clear()

      assertIs<OperationOutcome.Completed>(harness.confirm(token))

      assertEquals(
        listOf(VerifyPromptSections.COMPLETENESS_AUDIT_STEP, VerifyPromptSections.VERDICT_STEP),
        harness.runner.stepNames(),
      )
      assertEquals("completed", harness.workflowStatus(token))
      assertEquals(1, harness.telemetry.started.size)
    }
  }

  @Test
  fun `a row with an unknown contract version fails loudly on confirm and is left untouched`() {
    VerifyOperationHarness().use { harness ->
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose()).token
      harness.setContractVersion(token, "9.9")
      val before = harness.row(token)

      assertFailsWith<InvalidWorkflowStateSchemaError> { harness.confirm(token) }

      assertEquals(before, harness.row(token))
      assertEquals(listOf(VerifyPromptSections.EXTRACT_CRITERIA_STEP), harness.runner.stepNames())
    }
  }

  @Test
  fun `an adjusted re-invocation supersedes the parked row and its token then names the new one`() {
    VerifyOperationHarness().use { harness ->
      val first = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose()).token
      val second =
        assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose(instructions = "Also check logging.")).token

      assertEquals("abandoned", harness.workflowStatus(first))
      assertEquals("pending", harness.workflowStatus(second))
      val refused = assertIs<OperationOutcome.Blocked>(harness.confirm(first))
      assertTrue(second in refused.reason, refused.reason)
      assertEquals("abandoned", harness.workflowStatus(first))
    }
  }

  @Test
  fun `a free-text intake with no spec or target verifies HEAD against origin HEAD from that text`() {
    VerifyOperationHarness().use { harness ->
      val parked =
        assertIs<OperationOutcome.AwaitingConfirmation>(harness.invoke(OperationArguments(), instructions = LINEAR))

      assertTrue("Verify criteria for $LINEAR against HEAD" in parked.proposalSummary, parked.proposalSummary)
      val extraction = harness.runner.input(VerifyPromptSections.EXTRACT_CRITERIA_STEP)
      assertTrue("Linear issue" in extraction.directive, extraction.directive)
      assertFalse("Read the task spec at" in extraction.directive, extraction.directive)
      assertEquals(LINEAR, extraction.operatorInstructions)
      val inputContext = harness.snapshot(parked.token).artifacts.getValue("input_context") as Map<*, *>
      assertEquals(LINEAR, inputContext["intake"])
      assertFalse("spec_path" in inputContext)
      assertEquals(harness.base, inputContext["base_revision"])
      assertEquals(harness.head, inputContext["head_revision"])

      assertIs<OperationOutcome.Completed>(harness.confirm(parked.token))
      assertEquals(listOf(LINEAR), harness.telemetry.started.map { it.specSummary })
    }
  }

  @Test
  fun `raw requirements text is labelled by its first line against an explicit target`() {
    VerifyOperationHarness().use { harness ->
      val text = "\n  Greets the user by name.\nFalls back to a plain greeting without one."

      val parked =
        assertIs<OperationOutcome.AwaitingConfirmation>(
          harness.invoke(OperationArguments(target = harness.range), instructions = text),
        )

      assertTrue(
        "Verify criteria for Greets the user by name. against ${harness.range}" in parked.proposalSummary,
        parked.proposalSummary,
      )
      val inputContext = harness.snapshot(parked.token).artifacts.getValue("input_context") as Map<*, *>
      assertEquals(text.trim(), inputContext["intake"])
    }
  }

  @Test
  fun `verify with neither free text nor spec is a usage error that opens no workflow`() {
    VerifyOperationHarness().use { harness ->
      assertIs<OperationOutcome.Usage>(harness.invoke(OperationArguments(target = harness.range)))

      assertEquals(0, harness.rowCount("feature_verify_workflows"))
    }
  }

  private companion object {
    const val LINEAR = "https://linear.app/acme/issue/FP-1/greeting"

    val ARTIFACTS =
      setOf(
        "input_context",
        "criteria_summary",
        "diff_projection",
        "feature_flag_audit_receipt",
        "code_review_receipt",
        "unit_test_value_receipt",
        "completeness_audit_receipt",
        "verdict_result",
      )
  }
}
