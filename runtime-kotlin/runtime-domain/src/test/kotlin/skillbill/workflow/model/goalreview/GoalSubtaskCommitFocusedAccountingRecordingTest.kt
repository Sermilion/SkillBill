package skillbill.workflow.model.goalreview

import skillbill.review.context.model.accounting.ReviewIntegrationTerminalOutcome
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GoalSubtaskCommitFocusedAccountingRecordingTest {
  private val accounting =
    GoalSubtaskCommitFocusedAccounting(
      commitSequenceDigest = "a".repeat(64),
      commitCount = 6,
      laneCount = 2,
      focusedCommitCount = 4,
      skippedCommitCount = 2,
      integrationTerminalOutcome = ReviewIntegrationTerminalOutcome.COMPLETED,
      laneBundleSizes = mapOf("bill-kotlin-code-review-security" to 2048L),
      laneSegmentCounts = mapOf("bill-kotlin-code-review-security" to 2),
      incompleteLanes = listOf("bill-kotlin-code-review-testing"),
      parentAnalysisPairs = 12,
      parentAnalysisBytes = 4096,
      integrationFindingCount = 1,
    )

  private fun reservedFirstPass() =
    GoalSubtaskReviewState.initial(
      reviewBaseSha = "b".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.DELEGATED,
    ).reserveNextPass()

  @Test
  fun `a delegated pass records its commit-focused accounting and survives a durable round trip`() {
    val state =
      reservedFirstPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
        revision = GoalSubtaskReviewRevision(commitFocusedAccounting = accounting),
      )

    assertEquals(accounting, state.passResults.single().commitFocusedAccounting)
    val decoded = GoalSubtaskReviewState.fromArtifactMap(state.toArtifactMap())
    assertEquals(accounting, decoded.passResults.single().commitFocusedAccounting)
  }

  @Test
  fun `an inline review pass records no commit-focused accounting`() {
    val inlinePass =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "b".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
        revision = GoalSubtaskReviewRevision(commitFocusedAccounting = accounting),
      ).passResults.single()

    assertEquals(CodeReviewExecutionMode.INLINE, inlinePass.executedMode)
    assertNull(inlinePass.commitFocusedAccounting)
  }

  @Test
  fun `a skipped integration pass must name why it was not applicable`() {
    val skipped =
      accounting.copy(
        integrationTerminalOutcome = ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE,
        focusedCommitCount = 0,
        skippedCommitCount = 6,
        integrationSkipReason = "the commit sequence carries a single commit",
      )

    assertEquals(
      skipped,
      GoalSubtaskCommitFocusedAccounting.fromArtifactMap(skipped.toArtifactMap(), "accounting"),
    )
  }

  @Test
  fun `an unknown integration outcome is rejected at the artifact boundary`() {
    assertFailsWith<IllegalArgumentException> {
      GoalSubtaskCommitFocusedAccounting.fromArtifactMap(
        accounting.toArtifactMap() + ("integration_terminal_outcome" to "complete"),
        "accounting",
      )
    }
  }
}
