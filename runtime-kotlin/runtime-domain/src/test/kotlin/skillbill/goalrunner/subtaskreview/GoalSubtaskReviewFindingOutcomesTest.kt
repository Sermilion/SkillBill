package skillbill.goalrunner.subtaskreview

import skillbill.goalrunner.model.ReviewFindingOutcome
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.subtaskreview.model.UnaddressedFindingLedgerScope
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceiptEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalSubtaskReviewFindingOutcomesTest {
  private val scope = UnaddressedFindingLedgerScope("SKILL-1", 1, "wf-1", 1)

  private fun reviewOutput(vararg findings: Map<String, Any?>): Map<String, Any?> =
    mapOf(
      "produced_outputs" to
        mapOf(
          "review_run_id" to "rvw-1",
          "findings" to findings.toList(),
        ),
    )

  private fun finding(
    id: String,
    severity: String,
    settlement: String? = null,
  ): Map<String, Any?> =
    buildMap {
      put("finding_id", id)
      put("severity", severity)
      put("message", "defect $id")
      put("location", "src/$id.kt:1")
      settlement?.let { put("settlement", it) }
    }

  @Test
  fun `a fixed or refuted finding neither blocks nor enters the unaddressed ledger`() {
    val output =
      reviewOutput(
        finding("F-001", "blocker", "fixed"),
        finding("F-002", "major", "refuted"),
        finding("F-003", "minor"),
      )

    assertEquals(listOf("F-003"), GoalSubtaskReviewSummaryReducer.fromOutput(output).map { it.findingId })
    assertEquals(0, GoalSubtaskReviewSummaryReducer.outcomeFor(output).unresolvedFindingCount)
    assertEquals(
      listOf("F-003"),
      GoalSubtaskReviewSummaryReducer.unaddressedFindings(output, scope).map { it.findingId },
    )
  }

  @Test
  fun `settled findings record their outcome on ordinals after the open ones`() {
    val output =
      reviewOutput(
        finding("F-001", "minor", "fixed"),
        finding("F-002", "minor", "refuted"),
        finding("F-003", "minor"),
      )

    val outcomes = GoalSubtaskReviewSummaryReducer.settledFindingOutcomes(output, scope)

    assertEquals(
      listOf(
        Triple("F-001", 2, ReviewFindingOutcome.ADDRESSED),
        Triple("F-002", 3, ReviewFindingOutcome.REJECTED),
      ),
      outcomes.map { Triple(it.findingId, it.findingOrdinal, it.outcome) },
    )
    assertEquals(setOf("resolved"), outcomes.mapTo(mutableSetOf()) { it.keyState })
  }

  @Test
  fun `a receipt closes only repaired findings of the latest pass`() {
    fun ledgerFinding(
      id: String,
      pass: Int,
      ordinal: Int,
      disposition: String? = null,
    ) = UnaddressedFinding(
      issueKey = "SKILL-1",
      subtaskId = 1,
      workflowId = "wf-1",
      reviewPassNumber = pass,
      findingOrdinal = ordinal,
      severity = "minor",
      issueCategory = "other",
      location = "src/$id.kt:1",
      summary = "defect $id",
      reviewRunId = "rvw-$pass",
      findingId = id,
      verificationDisposition = disposition,
    )
    val ledger =
      listOf(
        ledgerFinding("F-001", 1, 1),
        ledgerFinding("F-001", 2, 1),
        ledgerFinding("F-002", 2, 2),
        ledgerFinding("F-003", 2, 3),
        ledgerFinding("F-004", 2, 4, UNADDRESSED_FINDING_REJECTED_DISPOSITION),
      )
    val receipt =
      FeatureTaskRuntimeRepairReceipt(
        roundNumber = 1,
        preFixCheckpointSha = "a".repeat(40),
        entries =
          listOf(
            FeatureTaskRuntimeRepairReceiptEntry(FeatureTaskRuntimeRepairOutcome.ADDRESSED, "F-001"),
            FeatureTaskRuntimeRepairReceiptEntry(
              FeatureTaskRuntimeRepairOutcome.ATTEMPTED_UNRESOLVED,
              "F-002",
              unresolvedReason = "still reproduces",
            ),
            FeatureTaskRuntimeRepairReceiptEntry(
              FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED,
              "F-003",
              noEditReason = "already fixed by F-001",
            ),
            FeatureTaskRuntimeRepairReceiptEntry(FeatureTaskRuntimeRepairOutcome.ADDRESSED, "F-004"),
          ),
      )

    val outcomes = GoalSubtaskReviewSummaryReducer.repairedFindingOutcomes(ledger, receipt)

    assertEquals(listOf(2 to "F-001", 2 to "F-003"), outcomes.map { it.reviewPassNumber to it.findingId })
    assertEquals(setOf(ReviewFindingOutcome.ADDRESSED), outcomes.mapTo(mutableSetOf()) { it.outcome })
  }
}
