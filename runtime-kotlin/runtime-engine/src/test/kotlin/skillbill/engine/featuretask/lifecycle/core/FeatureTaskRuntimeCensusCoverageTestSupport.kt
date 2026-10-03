package skillbill.engine.featuretask.lifecycle.core

import skillbill.engine.featuretask.runner.disposition
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.omittedCarriedFindings
import kotlin.test.assertTrue

object FeatureTaskRuntimeCensusCoverageTestSupport {
  fun verifyDisposition(
    findingId: String,
    disposition: String = "verified",
  ): Map<String, String> =
    mapOf(
      "finding_id" to findingId,
      "disposition" to disposition,
    )

  fun repairEntry(
    findingId: String,
    outcome: String = FeatureTaskRuntimeRepairOutcome.ADDRESSED.wireValue,
  ): Map<String, String> =
    mapOf(
      "finding_id" to findingId,
      "outcome" to outcome,
    )

  fun assertRepairOmits(
    receipt: FeatureTaskRuntimeRepairReceipt,
    carried: List<GoalSubtaskReviewCompactFinding>,
    expectedOmittedIds: Set<String>,
  ) {
    val omittedIds = receipt.omittedCarriedFindings(carried).mapNotNull { it.findingId }.toSet()
    assertTrue(omittedIds == expectedOmittedIds, "expected omitted $expectedOmittedIds but got $omittedIds")
  }
}
