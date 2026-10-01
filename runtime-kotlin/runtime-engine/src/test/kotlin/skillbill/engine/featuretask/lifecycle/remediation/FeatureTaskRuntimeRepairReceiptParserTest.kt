package skillbill.engine.featuretask.lifecycle.remediation

import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCensusCoverageTestSupport.assertRepairOmits
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.omittedCarriedFindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeRepairReceiptParserTest {
  private val sha = "b".repeat(40)

  private val carried =
    listOf(
      GoalSubtaskReviewCompactFinding("blocker", "Type", "unsafe mutation at the seam", "F-001"),
      GoalSubtaskReviewCompactFinding("major", "Policy", "stale comment is already gone", "F-002"),
    )

  @Test
  fun `the runtime stamps the remediation base and round on a receipt read from prose`() {
    val receipt =
      featureTaskRuntimeRepairReceiptFromProse("F-001 was fixed at the seam. F-002 was fixed too.", carried, sha, 3)

    assertEquals(sha, receipt.preFixCheckpointSha)
    assertEquals(3, receipt.roundNumber)
    assertTrue(receipt.omittedCarriedFindings(carried).isEmpty())
  }

  @Test
  fun `a finding the prose report never names stays owed`() {
    val receipt = featureTaskRuntimeRepairReceiptFromProse("F-001 was fixed at the seam.", carried, sha, 1)

    assertEquals(listOf("F-002"), receipt.omittedCarriedFindings(carried).map { it.findingId })
  }

  @Test
  fun `an empty prose report leaves every carried finding owed`() {
    val receipt = featureTaskRuntimeRepairReceiptFromProse("", carried, sha, 1)

    assertEquals(carried, receipt.omittedCarriedFindings(carried))
  }

  @Test
  fun `a finding reported still open is read as unresolved rather than addressed`() {
    val receipt =
      featureTaskRuntimeRepairReceiptFromProse(
        "F-001 is fixed.\nF-002 is still open after my attempt; the gate cannot see the pass ids.",
        carried,
        sha,
        1,
      )

    assertEquals(setOf("F-002"), assertNotNull(featureTaskRuntimeUnresolvedFindings(receipt)).refs)
  }

  @Test
  fun `a finding reported as needing no edit records the reason`() {
    val receipt =
      featureTaskRuntimeRepairReceiptFromProse(
        "F-001 fixed. F-002 needs no edit required because the comment is already gone.",
        carried,
        sha,
        1,
      )

    val entry = receipt.entries.single { it.findingId == "F-002" }
    assertEquals(FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED, entry.outcome)
    assertNotNull(entry.noEditReason)
  }

  @Test
  fun `a finding id that only prefixes another id is not a mention`() {
    val receipt = featureTaskRuntimeRepairReceiptFromProse("F-0011 was fixed.", carried, sha, 1)

    assertEquals(carried, receipt.omittedCarriedFindings(carried))
  }

  @Test
  fun `a round that edits one finding and records no_edit_required for the other is accepted`() {
    val edited = GoalSubtaskReviewCompactFinding("blocker", "Type", "unsafe mutation at the seam", "F-001")
    val leftover = GoalSubtaskReviewCompactFinding("major", "Policy", "stale comment is already gone", "F-002")
    val receipt =
      receiptFor(
        FeatureTaskRuntimeRepairReceiptEntry(
          outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
          findingId = edited.findingId!!,
        ),
        FeatureTaskRuntimeRepairReceiptEntry(
          outcome = FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED,
          findingId = leftover.findingId!!,
          noEditReason = "construct already matched the finding",
        ),
      )
    assertTrue(receipt.omittedCarriedFindings(listOf(edited, leftover)).isEmpty())
  }

  @Test
  fun `a receipt that omits a carried finding names it for the next attempt without echoing it`() {
    val edited = GoalSubtaskReviewCompactFinding("blocker", "Type", "unsafe mutation at the seam", "F-001")
    val leftover = GoalSubtaskReviewCompactFinding("major", "Policy", "stale comment is already gone", "F-002")
    val receipt =
      receiptFor(
        FeatureTaskRuntimeRepairReceiptEntry(
          outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
          findingId = edited.findingId!!,
        ),
      )
    assertRepairOmits(receipt, listOf(edited, leftover), setOf("F-002"))
    val omitted = receipt.omittedCarriedFindings(listOf(edited, leftover))

    assertEquals(listOf(leftover), omitted)
    val reason = featureTaskRuntimeOmittedFindingsRetryReason(omitted)
    assertTrue(reason.contains("F-002"))
    assertTrue(!reason.contains(leftover.text))
  }

  @Test
  fun `an attempted_unresolved entry carries the finding ref and the producer's own account`() {
    val carried = GoalSubtaskReviewCompactFinding("major", "Policy", "the gate still admits an empty set", "F-002")
    val receipt =
      receiptFor(
        FeatureTaskRuntimeRepairReceiptEntry(
          outcome = FeatureTaskRuntimeRepairOutcome.ATTEMPTED_UNRESOLVED,
          findingId = requireNotNull(carried.findingId),
          unresolvedReason = "the gate has no access to the review pass ids it would have to compare",
        ),
      )

    assertTrue(receipt.omittedCarriedFindings(listOf(carried)).isEmpty())
    val unresolved = assertNotNull(featureTaskRuntimeUnresolvedFindings(receipt))
    assertEquals(setOf("F-002"), unresolved.refs)
    assertTrue(unresolved.detail.contains("the gate has no access to the review pass ids"))
    assertTrue(unresolved.retryReason.contains("one more attempt"))
  }

  @Test
  fun `a receipt with no attempted_unresolved entry owes nothing`() {
    val receipt =
      receiptFor(
        FeatureTaskRuntimeRepairReceiptEntry(
          outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
          findingId = "F-002",
        ),
      )

    assertNull(featureTaskRuntimeUnresolvedFindings(receipt))
  }

  @Test
  fun `census-only receipt entry satisfies coverage for carried finding id`() {
    val locationBearing =
      GoalSubtaskReviewCompactFinding(
        severity = "blocker",
        label = "ReducerLabel",
        text = "the compact text is long but coverage keys on finding id only",
        findingId = "F-001",
      )
    val receipt =
      receiptFor(
        FeatureTaskRuntimeRepairReceiptEntry(
          outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
          findingId = "F-001",
        ),
      )
    assertTrue(receipt.omittedCarriedFindings(listOf(locationBearing)).isEmpty())
  }

  private fun receiptFor(vararg entries: FeatureTaskRuntimeRepairReceiptEntry) =
    FeatureTaskRuntimeRepairReceipt(
      roundNumber = 1,
      preFixCheckpointSha = sha,
      entries = entries.toList(),
    )
}
