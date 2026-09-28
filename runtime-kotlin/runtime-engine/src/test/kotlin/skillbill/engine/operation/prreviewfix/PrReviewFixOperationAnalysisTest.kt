package skillbill.engine.operation.prreviewfix

import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationOutcome
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PrReviewFixOperationAnalysisTest {
  @Test
  fun `analysis proposes the matrix anchored to the PR head and live threads without replying, pushing, or editing`() {
    PrReviewFixHarness().use { harness ->
      val proposed = assertIs<OperationOutcome.AwaitingConfirmation>(harness.invoke())

      val anchors = assertNotNull(harness.proposal(proposed.token)).anchors.operationValues
      assertEquals("head-1", anchors[PrReviewFixAnchors.PR_HEAD_SHA])
      assertEquals("PRRT_a,PRRT_b", anchors[PrReviewFixAnchors.UNRESOLVED_THREAD_IDS])
      assertEquals(listOf(ANALYSIS_STEP), harness.runner.inputs.map { input -> input.stepName })
      val directive = harness.runner.inputs.single().directive
      assertContains(directive, "### T1 — a.kt:9 (thread PRRT_a)")
      assertContains(directive, "- PRRT_outdated — c.kt:2 (outdated)")
      assertContains(directive, "### Classify threads")
      assertEquals(emptyList(), harness.github.replies)
      assertEquals(emptyList(), harness.pushes)
      assertEquals("", harness.status())
    }
  }

  @Test
  fun `analyze-only prints the matrix and stores no proposal`() {
    PrReviewFixHarness().use { harness ->
      val completed = assertIs<OperationOutcome.Completed>(harness.invoke(OperationArguments(scope = "analyze-only")))

      assertContains(completed.text, harness.runner.matrix.trim())
      assertEquals(0, harness.proposalRowCount())
    }
  }

  @Test
  fun `analysis off the PR branch refuses before any step and stores no proposal`() {
    PrReviewFixHarness().use { harness ->
      harness.checkoutNewBranch("other")

      val refused = assertIs<OperationOutcome.Blocked>(harness.invoke())

      assertContains(refused.reason, PrReviewFixHarness.PR_BRANCH)
      assertEquals(emptyList(), harness.runner.inputs)
      assertEquals(0, harness.proposalRowCount())
    }
  }

  @Test
  fun `a bare leading number is a PR reference only when it is the whole text`() {
    assertEquals(PrReviewFixTarget("42", null), prReviewFixTarget("42"))
    assertEquals(PrReviewFixTarget("42", "keep it small"), prReviewFixTarget("#42 keep it small"))
    assertEquals(PrReviewFixTarget(null, "3 things to check"), prReviewFixTarget("3 things to check"))
  }

  @Test
  fun `an analysis step that edits the worktree fails and stores no proposal`() {
    PrReviewFixHarness().use { harness ->
      harness.runner.editDuringAnalysis = true

      assertIs<OperationOutcome.Failed>(harness.invoke())
      assertEquals(0, harness.proposalRowCount())
    }
  }
}
