package skillbill.engine.goalrunner.status

import skillbill.goalrunner.model.GoalRunnerReviewFinding
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRunnerReviewPassFindingsTest {
  // The compact summary loses dotted code spans and paths; the projected finding must keep the stored text whole.
  @Test
  fun `stored findings keep their full text, lane and status and drop refuted claims`() {
    val kept =
      NumberedFinding(
        number = 1,
        findingId = "F-001",
        severity = "Minor",
        confidence = "High",
        location = "desktop/ChatPane.kt:25",
        description = "The tab title reads `session.item.issueKey` from `ChatPane.kt`",
        laneSkillName = "bill-kmp-code-review-ui",
      )
    val untriaged = kept.copy(number = 2, findingId = "F-002", confidence = "", location = "")
    val refuted = kept.copy(number = 3, findingId = "F-003", claimVerdict = ReviewClaimVerdict.REFUTED)

    val projected =
      storedReviewPassFindings(listOf(kept, untriaged, refuted), mapOf("F-001" to ReviewFindingStatus.FIXED))

    assertEquals(
      listOf(
        GoalRunnerReviewFinding(
          "F-001", "Minor", "High", "bill-kmp-code-review-ui", "desktop/ChatPane.kt:25",
          "The tab title reads `session.item.issueKey` from `ChatPane.kt`", ReviewFindingStatus.FIXED,
        ),
        GoalRunnerReviewFinding(
          "F-002", "Minor", null, "bill-kmp-code-review-ui", null,
          "The tab title reads `session.item.issueKey` from `ChatPane.kt`", ReviewFindingStatus.OPEN,
        ),
      ),
      projected,
    )
  }
}
