package skillbill.engine.goalrunner.planning.model

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDispositionVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoalSubtaskBlockerDispositionParseTest {
  private fun output(vararg dispositions: Map<String, Any?>): Map<String, Any?> =
    mapOf("produced_outputs" to mapOf("blocker_dispositions" to dispositions.toList()))

  @Test
  fun `an evidenced disposition parses one verdict per prior blocker`() {
    val parsed =
      GoalSubtaskReviewSummaryReducer.blockerDispositions(
        output(
          mapOf(
            "finding_id" to "F-001",
            "verdict" to "resolved",
            "evidence" to listOf("guard added at the write seam"),
          ),
          mapOf("finding_id" to "F-002", "verdict" to "unresolved", "evidence" to listOf("still reproduces")),
        ),
      )
    assertEquals(listOf("F-001", "F-002"), parsed.map { it.findingId })
    assertEquals(GoalSubtaskBlockerDispositionVerdict.RESOLVED, parsed.first().verdict)
    assertEquals(GoalSubtaskBlockerDispositionVerdict.UNRESOLVED, parsed.last().verdict)
  }

  @Test
  fun `superseded verdict loud-fails at the parse seam`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        GoalSubtaskReviewSummaryReducer.blockerDispositions(
          output(
            mapOf("finding_id" to "F-001", "verdict" to "superseded", "evidence" to listOf("call site deleted")),
          ),
        )
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA, it.code) }
    assertTrue(error.message.orEmpty().contains("superseded"))
  }

  @Test
  fun `an unevidenced disposition is rejected at the parse seam`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        GoalSubtaskReviewSummaryReducer.blockerDispositions(
          output(mapOf("finding_id" to "F-001", "verdict" to "resolved")),
        )
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA, it.code) }
    assertTrue(error.message.orEmpty().contains("evidence"))

    assertFailsWith<SkillBillRuntimeException> {
      GoalSubtaskReviewSummaryReducer.blockerDispositions(
        output(mapOf("finding_id" to "F-001", "verdict" to "resolved", "evidence" to listOf("   "))),
      )
    }.also { assertEquals(InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA, it.code) }
  }

  @Test
  fun `an unknown verdict is rejected rather than coerced`() {
    assertFailsWith<SkillBillRuntimeException> {
      GoalSubtaskReviewSummaryReducer.blockerDispositions(
        output(mapOf("finding_id" to "F-001", "verdict" to "probably_fine", "evidence" to listOf("line 3"))),
      )
    }.also { assertEquals(InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA, it.code) }
  }

  @Test
  fun `a pass with no dispositions parses empty rather than failing`() {
    assertEquals(emptyList(), GoalSubtaskReviewSummaryReducer.blockerDispositions(linkedMapOf<String, Any?>()))
  }

  @Test
  fun `a remediation pass dispositions blockers its immediately preceding pass introduced`() {
    val introducedByPassFour = listOf("F-041", "F-042")

    val parsed =
      GoalSubtaskReviewSummaryReducer.blockerDispositions(
        output(
          mapOf("finding_id" to "F-041", "verdict" to "resolved", "evidence" to listOf("guard restored")),
          mapOf("finding_id" to "F-042", "verdict" to "unresolved", "evidence" to listOf("still reproduces")),
        ),
        priorBlockerFindingIds = introducedByPassFour,
      )
    assertEquals(introducedByPassFour, parsed.map { it.findingId })

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        GoalSubtaskReviewSummaryReducer.blockerDispositions(
          output(mapOf("finding_id" to "F-001", "verdict" to "resolved", "evidence" to listOf("stale round"))),
          priorBlockerFindingIds = introducedByPassFour,
        )
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA, it.code) }
    assertTrue(
      error.message.orEmpty().contains("F-001"),
      "A disposition against an older round's Blocker must be rejected by name.",
    )
  }

  @Test
  fun `a prior blocker does not require a disposition`() {
    assertEquals(
      emptyList(),
      GoalSubtaskReviewSummaryReducer.blockerDispositions(
        output(),
        priorBlockerFindingIds = listOf("F-001"),
      ),
    )
  }
}
