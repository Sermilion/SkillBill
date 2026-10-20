package skillbill.engine.operation.verify

import skillbill.engine.featuretask.model.execution.QaExpectedIdentity
import skillbill.engine.featuretask.qa.receipt.QaVerdict
import skillbill.engine.featuretask.qa.receipt.qaTestJson
import skillbill.engine.featuretask.qa.receipt.qaTestReceipt
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
import kotlin.test.assertTrue

class VerifyQaOperationTest {
  @Test
  fun `without a qa token the run writes a disabled_policy skip and never probes a device or launches QA`() {
    VerifyOperationHarness().use { harness ->
      val headTree = harness.treeIdentity(harness.repo)
      harness.dirtyGreeter()
      val parked = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose())
      assertContains(parked.proposalSummary, "QA: off")

      val report = assertIs<OperationOutcome.Completed>(harness.confirm(parked.token))

      assertContains(report.text, "QA outcome: skipped (disabled_policy)")
      val receipt = qaReceipt(harness, parked.token)
      assertEquals("skipped", receipt["verdict"])
      assertEquals("disabled_policy", receipt["reason"])
      assertEquals(headTree, receipt["tree_identity"])
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
      assertContains(verdictDirective, "unsupported_target")
      assertFalse(VerifyPromptSections.QA_STEP in harness.runner.stepNames())
      assertEquals(listOf(VerifyOperationHarness.QA_INVOCATION), harness.qaLeases.releasedInvocations)
      assertFalse(Files.exists(harness.qaWorktree(parked.token)))
    }
  }

  @Test
  fun `opted-in QA runs in a temporary worktree at the target head and leaves the operator checkout alone`() {
    VerifyOperationHarness().use { harness ->
      harness.qaManifests = listOf(ANDROID_PACK)
      harness.dirtyGreeter()
      val status = harness.status()
      val head = harness.headCommit()
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose(qa = "on")).token
      var testedGreeter: String? = null
      harness.runner.sideEffects[VerifyPromptSections.QA_STEP] = {
        testedGreeter = harness.qaWorktree(token).resolve("src/main/kotlin/Greeter.kt").readText()
        harness.qaWorkspaces.receipt = receipt(harness, token, QaVerdict.PASSED)
      }

      val report = assertIs<OperationOutcome.Completed>(harness.confirm(token))

      assertContains(report.text, "QA outcome: passed")
      assertEquals(harness.qaWorktree(token), harness.runner.input(VerifyPromptSections.QA_STEP).facts.repoRoot)
      assertEquals("fun greet(name: String) = \"hi \$name\"\n", testedGreeter)
      assertEquals(status, harness.status())
      assertEquals(head, harness.headCommit())
      assertFalse(Files.exists(harness.qaWorktree(token)))
      val steps = harness.runner.stepNames()
      assertEquals(
        listOf(
          VerifyPromptSections.COMPLETENESS_AUDIT_STEP,
          VerifyPromptSections.QA_STEP,
          VerifyPromptSections.VERDICT_STEP,
        ),
        steps.takeLast(3),
      )
      assertContains(harness.runner.input(VerifyPromptSections.VERDICT_STEP).directive, "Mobile QA Verdict Rules")
    }
  }

  @Test
  fun `open QA findings turn an unqualified APPROVE into APPROVE WITH FIXES with a warning`() {
    VerifyOperationHarness().use { harness ->
      harness.qaManifests = listOf(ANDROID_PACK)
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose(qa = "on")).token
      harness.runner.sideEffects[VerifyPromptSections.QA_STEP] = {
        harness.qaWorkspaces.receipt = receipt(harness, token, QaVerdict.FINDINGS_OPEN)
      }

      val report = assertIs<OperationOutcome.Completed>(harness.confirm(token))

      assertContains(report.text, "recorded APPROVE WITH FIXES")
      assertContains(report.text, "QA outcome: findings")
      assertContains(harness.runner.input(VerifyPromptSections.VERDICT_STEP).directive, "[F-1] criteria=AC-1")
      assertEquals("findings", harness.telemetry.finished.single().qaOutcome)
    }
  }

  @Test
  fun `a receipt bound to another tree is recorded incomplete with a warning and verify still completes`() {
    VerifyOperationHarness().use { harness ->
      harness.qaManifests = listOf(ANDROID_PACK)
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose(qa = "on")).token
      harness.runner.sideEffects[VerifyPromptSections.QA_STEP] = {
        harness.qaWorkspaces.receipt = receipt(harness, token, QaVerdict.PASSED, tree = "f".repeat(64))
      }

      val report = assertIs<OperationOutcome.Completed>(harness.confirm(token))

      assertContains(report.text, "QA outcome: incomplete (other)")
      val receipt = qaReceipt(harness, token)
      assertContains((receipt["warnings"] as List<*>).single().toString(), "tree_identity")
      assertFalse(Files.exists(harness.qaWorktree(token)))
    }
  }

  @Test
  fun `a failed worktree removal is recorded on the receipt and in diagnostics`() {
    VerifyOperationHarness().use { harness ->
      harness.failWorktreeRemoval = true
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.propose(qa = "on")).token

      assertIs<OperationOutcome.Completed>(harness.confirm(token))

      val receipt = qaReceipt(harness, token)
      assertEquals("retained", receipt["cleanup"])
      assertEquals(listOf("worktree ${harness.qaWorktree(token)}"), receipt["retained_resources"])
      assertTrue(harness.warnings.any { "verify qa worktree retained" in it }, harness.warnings.toString())
    }
  }

  @Test
  fun `a workflow proposed before verify QA existed reports not_selected`() {
    VerifyOperationHarness().use { harness ->
      val token = harness.seedSkillWorkflowAtCodeReview()

      val report = assertIs<OperationOutcome.Completed>(harness.confirm(token, target = harness.range))

      assertContains(report.text, "QA outcome: not_selected")
      assertFalse("qa_receipt" in harness.snapshot(token).artifacts)
      assertEquals("not_selected", harness.telemetry.finished.single().qaOutcome)
    }
  }

  private fun qaReceipt(
    harness: VerifyOperationHarness,
    token: String,
  ): Map<*, *> = harness.snapshot(token).artifacts["qa_receipt"] as Map<*, *>

  private fun receipt(
    harness: VerifyOperationHarness,
    token: String,
    verdict: QaVerdict,
    tree: String = harness.treeIdentity(harness.qaWorktree(token)),
  ): String {
    val expected = QaExpectedIdentity(harness.head, tree, ".git/skill-bill/qa/${VerifyOperationHarness.QA_INVOCATION}/")
    return qaTestJson(qaTestReceipt(verdict, expected) + ("policy" to "on"))
  }

  private companion object {
    val ANDROID_PACK =
      kotlinPackWithValidationGate()
        .copy(qaCapability = QaCapabilityDeclaration(setOf(QaPlatform.ANDROID), "qa/android-qa.md"))
  }
}
