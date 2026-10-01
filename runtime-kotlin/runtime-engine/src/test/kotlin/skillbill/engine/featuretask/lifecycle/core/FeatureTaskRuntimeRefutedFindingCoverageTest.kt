package skillbill.engine.featuretask.lifecycle.core

import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeOmittedFindingsRetryReason
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRepairReceiptOmittedFindings
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.GoalSubtaskReviewPassResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeRefutedFindingCoverageTest {
  private val sha = "d".repeat(40)

  private val surviving =
    GoalSubtaskReviewCompactFinding(
      severity = "minor",
      label = "CapmoProjectCatalogRepository",
      text = "inlines the row-to-domain conversion the mapper already owns",
      findingId = "F-001",
    )
  private val alsoSurviving =
    GoalSubtaskReviewCompactFinding(
      severity = "minor",
      label = "CapmoWeatherForecastRepositoryAutoWeatherTest",
      text = "the test passes whatever operation the transport is handed",
      findingId = "F-002",
    )
  private val refuted =
    GoalSubtaskReviewCompactFinding(
      severity = "nit",
      label = "WeatherForecastQuery",
      text = "the hourly selection is never read by the auto path",
      findingId = "F-003",
    )

  @Test
  fun `a round that closed every surviving finding settles when verification refuted the rest`() {
    val state = reviewStateCarrying(surviving, alsoSurviving, refuted)
    val receipt = receiptAddressing(surviving, alsoSurviving)

    assertTrue(featureTaskRuntimeRepairReceiptOmittedFindings(receipt, state, setOf("F-003")).isEmpty())
  }

  @Test
  fun `the refuted finding stays owed while nothing records the refutation`() {
    val state = reviewStateCarrying(surviving, alsoSurviving, refuted)
    val receipt = receiptAddressing(surviving, alsoSurviving)

    assertEquals(
      listOf("F-003"),
      featureTaskRuntimeRepairReceiptOmittedFindings(receipt, state).map { finding -> finding.findingId },
    )
  }

  @Test
  fun `a refutation waives only the finding it names`() {
    val state = reviewStateCarrying(surviving, alsoSurviving, refuted)
    val receipt = receiptAddressing(surviving)

    assertEquals(
      listOf("F-002"),
      featureTaskRuntimeRepairReceiptOmittedFindings(receipt, state, setOf("F-003"))
        .map { finding -> finding.findingId },
    )
  }

  @Test
  fun `a refuted finding is never named in the reason the next attempt is sent`() {
    val state = reviewStateCarrying(surviving, alsoSurviving, refuted)
    val omitted =
      featureTaskRuntimeRepairReceiptOmittedFindings(
        receiptAddressing(surviving),
        state,
        setOf("F-003"),
      )

    val reason = featureTaskRuntimeOmittedFindingsRetryReason(omitted)
    assertTrue(reason.contains("F-002"))
    assertTrue(!reason.contains("F-003"))
  }

  @Test
  fun `a refutation naming a finding the pass never carried waives nothing`() {
    val state = reviewStateCarrying(surviving, alsoSurviving)
    val receipt = receiptAddressing(surviving)

    assertEquals(
      listOf("F-002"),
      featureTaskRuntimeRepairReceiptOmittedFindings(receipt, state, setOf("F-009"))
        .map { finding -> finding.findingId },
    )
  }

  @Test
  fun `a carried finding whose review omitted its ref is still owed an entry`() {
    val unnamed =
      GoalSubtaskReviewCompactFinding(
        severity = "major",
        label = "Policy",
        text = "the review output carried no finding id",
      )
    val state = reviewStateCarrying(surviving, unnamed)
    val receipt = receiptAddressing(surviving)

    assertEquals(
      listOf("F-002"),
      featureTaskRuntimeRepairReceiptOmittedFindings(receipt, state).map { finding -> finding.findingId },
    )
  }

  private fun receiptAddressing(vararg findings: GoalSubtaskReviewCompactFinding) =
    FeatureTaskRuntimeRepairReceipt(
      roundNumber = 1,
      preFixCheckpointSha = sha,
      entries =
        findings.map { finding ->
          FeatureTaskRuntimeRepairReceiptEntry(
            outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
            findingId = requireNotNull(finding.findingId),
          )
        },
    )

  private fun reviewStateCarrying(vararg findings: GoalSubtaskReviewCompactFinding) =
    GoalSubtaskReviewState(
      reviewBaseSha = sha,
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
      completedPassCount = 1,
      remediationBaseSha = sha,
      passResults =
        listOf(
          GoalSubtaskReviewPassResult(
            passNumber = 1,
            verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
            reviewResultArtifact = "goal_subtask_review_results.1",
            unresolvedFindingCount = findings.size,
            findings = findings.toList(),
          ),
        ),
    )
}
