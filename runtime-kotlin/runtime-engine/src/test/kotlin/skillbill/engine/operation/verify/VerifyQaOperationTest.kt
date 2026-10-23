package skillbill.engine.operation.verify

import skillbill.engine.featuretask.qa.report.QaVerdict
import skillbill.engine.featuretask.qa.report.qaTestExpected
import skillbill.engine.featuretask.qa.report.qaTestReport
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.engine.operation.core.OperationOutcome
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaPlatform
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifyQaOperationTest {
  @Test
  fun `without a qa token the run writes a disabled_policy skip and never probes a device or launches QA`() {
    VerifyOperationHarness().use { harness ->
      harness.dirtyGreeter()
      val parked = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose())
      assertContains(parked.proposalSummary, "QA: off")

      val report = assertIs<OperationOutcome.Completed>(harness.confirm(parked.token))

      assertContains(report.text, "QA outcome: skipped (disabled_policy)")
      val artifact = qaReport(harness, parked.token)
      assertEquals("skipped", artifact["verdict"])
      assertEquals("disabled_policy", artifact["reason"])
      assertEquals("QA is off for this verify run.", artifact["report_text"])
      assertEquals(emptyList(), harness.qaLeases.releasedInvocations)
      assertFalse(VerifyPromptSections.QA_STEP in harness.runner.stepNames())
      assertFalse("Mobile QA Verdict Rules" in harness.runner.input(VerifyPromptSections.VERDICT_STEP).directive)
      val finished = harness.telemetry.finished.single()
      assertEquals("skipped" to "disabled_policy", finished.qaOutcome to finished.qaReason)
    }
  }

  @Test
  fun `qa on is stored with the proposal and a confirm that asks to change it is refused`() {
    VerifyOperationHarness().use { harness ->
      val parked = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose(qa = "on"))
      assertContains(parked.proposalSummary, "QA: on")
      val context = harness.snapshot(parked.token).artifacts["input_context"] as Map<*, *>
      assertEquals("on", context["qa_selection"])

      val refused = assertIs<OperationOutcome.Blocked>(harness.confirm(parked.token, qa = "off"))

      assertContains(refused.reason, "cannot change it to qa:off")
      assertEquals("extract_criteria", harness.currentStep(parked.token))
      val report = assertIs<OperationOutcome.Completed>(harness.confirm(parked.token, qa = "on"))
      assertContains(report.text, "QA outcome: skipped (unsupported_target)")
      val verdictDirective = harness.runner.input(VerifyPromptSections.VERDICT_STEP).directive
      assertContains(verdictDirective, "Untested coverage:")
      assertContains(verdictDirective, "no installed pack declares qa_capability for this repository")
      assertFalse(VerifyPromptSections.QA_STEP in harness.runner.stepNames())
      assertEquals(listOf(VerifyOperationHarness.QA_INVOCATION), harness.qaLeases.releasedInvocations)
      assertFalse(Files.exists(harness.qaWorktree))
    }
  }

  @Test
  fun `opted-in QA runs in a temporary worktree at the target head and embeds the prose report in the verdict`() {
    VerifyOperationHarness().use { harness ->
      harness.qaManifests = listOf(ANDROID_PACK)
      harness.dirtyGreeter()
      val status = harness.status()
      val head = harness.headCommit()
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose(qa = "on")).token
      val written = qaTestReport(QaVerdict.PASSED, expected())
      var testedGreeter: String? = null
      harness.runner.sideEffects[VerifyPromptSections.QA_STEP] = {
        testedGreeter = harness.qaWorktree.resolve("src/main/kotlin/Greeter.kt").readText()
        harness.qaWorkspaces.report = written
      }

      val report = assertIs<OperationOutcome.Completed>(harness.confirm(token))

      assertContains(report.text, "QA outcome: passed")
      assertEquals(harness.qaWorktree, harness.runner.input(VerifyPromptSections.QA_STEP).facts.repoRoot)
      assertEquals("fun greet(name: String) = \"hi \$name\"\n", testedGreeter)
      assertEquals(status, harness.status())
      assertEquals(head, harness.headCommit())
      assertFalse(Files.exists(harness.qaWorktree))
      val steps = harness.runner.stepNames()
      assertEquals(
        listOf(
          VerifyPromptSections.COMPLETENESS_AUDIT_STEP,
          VerifyPromptSections.QA_STEP,
          VerifyPromptSections.VERDICT_STEP,
        ),
        steps.takeLast(3),
      )
      val directive = harness.runner.input(VerifyPromptSections.VERDICT_STEP).directive
      assertContains(directive, "Mobile QA Verdict Rules")
      assertContains(directive, "QA report (verdict: passed):")
      assertContains(directive, written)
      assertEquals(written, qaReport(harness, token)["report_text"])
    }
  }

  @Test
  fun `open QA findings turn an unqualified APPROVE into APPROVE WITH FIXES with a warning`() {
    VerifyOperationHarness().use { harness ->
      harness.qaManifests = listOf(ANDROID_PACK)
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose(qa = "on")).token
      val written = qaTestReport(QaVerdict.FINDINGS_OPEN, expected())
      harness.runner.sideEffects[VerifyPromptSections.QA_STEP] = { harness.qaWorkspaces.report = written }

      val report = assertIs<OperationOutcome.Completed>(harness.confirm(token))

      assertContains(report.text, "recorded APPROVE WITH FIXES")
      assertContains(report.text, "QA outcome: findings")
      val directive = harness.runner.input(VerifyPromptSections.VERDICT_STEP).directive
      assertContains(directive, "### F-1 Home screen stays blank")
      assertContains(directive, "Observed: the home screen stays blank.")
      assertEquals("findings", harness.telemetry.finished.single().qaOutcome)
    }
  }

  @Test
  fun `a report naming no verdict is recorded incomplete with its text kept and verify still completes`() {
    VerifyOperationHarness().use { harness ->
      harness.qaManifests = listOf(ANDROID_PACK)
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose(qa = "on")).token
      val written = "The app looked fine to me but I did not finish.\n"
      harness.runner.sideEffects[VerifyPromptSections.QA_STEP] = { harness.qaWorkspaces.report = written }

      val report = assertIs<OperationOutcome.Completed>(harness.confirm(token))

      assertContains(report.text, "QA outcome: incomplete")
      val artifact = qaReport(harness, token)
      assertEquals("incomplete", artifact["verdict"])
      assertEquals(written, artifact["report_text"])
      assertContains(harness.runner.input(VerifyPromptSections.VERDICT_STEP).directive, written)
      assertFalse(Files.exists(harness.qaWorktree))
    }
  }

  @Test
  fun `an agent that wrote no report is recorded incomplete with a warning and no text`() {
    VerifyOperationHarness().use { harness ->
      harness.qaManifests = listOf(ANDROID_PACK)
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose(qa = "on")).token

      val report = assertIs<OperationOutcome.Completed>(harness.confirm(token))

      assertContains(report.text, "QA outcome: incomplete")
      val artifact = qaReport(harness, token)
      assertNull(artifact["report_text"])
      assertEquals(listOf("QA agent wrote no report.md"), artifact["warnings"])
      assertContains(harness.runner.input(VerifyPromptSections.VERDICT_STEP).directive, "QA agent wrote no report.md")
    }
  }

  @Test
  fun `a failed worktree removal is recorded as a cleanup note on the report and in diagnostics`() {
    VerifyOperationHarness().use { harness ->
      harness.qaManifests = listOf(ANDROID_PACK)
      harness.failWorktreeRemoval = true
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose(qa = "on")).token

      assertIs<OperationOutcome.Completed>(harness.confirm(token))

      val cleanup = qaReport(harness, token)["cleanup"].toString()
      assertContains(cleanup, "retained worktree ${harness.qaWorktree}")
      assertTrue(harness.warnings.any { "qa checkout retained" in it }, harness.warnings.toString())
    }
  }

  @Test
  fun `a workflow proposed before verify QA existed reports not_selected`() {
    VerifyOperationHarness().use { harness ->
      val token = harness.seedSkillWorkflowAtCodeReview()

      val report = assertIs<OperationOutcome.Completed>(harness.confirm(token, target = harness.range))

      assertContains(report.text, "QA outcome: not_selected")
      assertFalse("qa_report" in harness.snapshot(token).artifacts)
      assertFalse("qa_receipt" in harness.snapshot(token).artifacts)
      assertEquals("not_selected", harness.telemetry.finished.single().qaOutcome)
    }
  }

  @Test
  fun `a legacy qa_receipt artifact is still found and rendered when no qa_report exists`() {
    val legacy = mapOf("verdict" to "findings_open", "findings" to listOf(mapOf("id" to "F-1")))
    val current = mapOf("verdict" to "passed")

    val found = VerifyWorkflow.qaArtifact(mapOf("qa_receipt" to legacy))
    val preferred = VerifyWorkflow.qaArtifact(mapOf("qa_receipt" to legacy, "qa_report" to current))
    val directive = VerifyPromptSections.verdictDirective(legacy)

    assertEquals(legacy, found)
    assertEquals(current, preferred)
    assertContains(directive, "QA report (verdict: findings_open):")
    assertContains(directive, "F-1")
    assertFalse("No QA report text was recorded." in directive)
  }

  private fun qaReport(
    harness: VerifyOperationHarness,
    token: String,
  ): Map<*, *> = harness.snapshot(token).artifacts["qa_report"] as Map<*, *>

  private fun expected() = qaTestExpected(VerifyOperationHarness.QA_INVOCATION)

  private companion object {
    val ANDROID_PACK =
      kotlinPackWithValidationGate()
        .copy(qaCapability = QaCapabilityDeclaration(setOf(QaPlatform.ANDROID), "qa/android-qa.md"))
  }
}
