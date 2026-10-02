package skillbill.goalrunner

import skillbill.contracts.SharedPayloadKeys
import skillbill.goalrunner.ledger.summarizeAttemptLedgerFromEntries
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class AttemptLedgerAccumulatorTest {
  @Test
  fun `ledger reduction preserves counts and latest values from representative entries`() {
    val entries =
      listOf(
        mapOf(
          "action" to GoalAttemptLedgerAction.CHILD_ACTIVATION.wireValue,
          "current_step" to "plan",
        ),
        mapOf(
          "action" to GoalAttemptLedgerAction.RESUME.wireValue,
          "previous_step" to "implement",
          "stop_reason" to "failed",
          "re_attempt_cause" to "review",
          "findings_in_scope" to 4,
        ),
        mapOf(
          "action" to GoalAttemptLedgerAction.DIAGNOSTIC_INSPECTION.wireValue,
          "diagnostic_class" to "supervisor_killed_confirmed_alive",
        ),
        mapOf(
          "action" to GoalAttemptLedgerAction.BACKWARD_EDGE_ENTRY.wireValue,
          SharedPayloadKeys.SUBTASK_ID to 2,
          "loop_id" to "review_fix",
          "cumulative_loop_count" to 3,
        ),
        mapOf(
          "action" to GoalAttemptLedgerAction.BACKWARD_EDGE_ENTRY.wireValue,
          SharedPayloadKeys.SUBTASK_ID to 2,
          "loop_id" to "review_fix",
          "cumulative_loop_count" to 5,
        ),
      )

    assertEquals(
      expected =
        GoalRunnerAttemptLedgerSummary(
          blockedAttemptCount = 1,
          supervisorKillCount = 1,
          phaseAttemptCounts = mapOf("plan" to 1, "implement" to 1),
          cumulativeFixIterations = mapOf("2:review_fix" to 5),
          reAttemptCauseCounts = mapOf("review" to 1),
          findingsInScope = 4,
        ),
      actual = summarizeAttemptLedgerFromEntries(entries),
    )
  }
}
