package skillbill.engine.work

import skillbill.goalrunner.model.GOAL_MAIN_CHECKOUT_FAILED
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.idestatus.model.GoalMainCheckoutOutcome
import skillbill.ports.idestatus.model.GoalWorktreeState
import skillbill.ports.idestatus.model.GoalWorktreeView
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkBoardGoalWorktreeViewTest {
  @Test
  fun `removed worktree carries the recorded main checkout outcome and detail`() {
    assertEquals(
      GoalWorktreeView(
        "/goal-worktree",
        GoalWorktreeState.REMOVED,
        mainCheckoutOutcome = GoalMainCheckoutOutcome.FAILED,
        mainCheckoutDetail = "rebase in progress",
      ),
      view(
        GoalRunnerControlState(
          executionMainCheckoutOutcome = GOAL_MAIN_CHECKOUT_FAILED,
          executionMainCheckoutDetail = "rebase in progress",
        ),
      ),
    )
  }

  @Test
  fun `an unrecognised outcome drops the outcome and its detail`() {
    assertEquals(
      GoalWorktreeView("/goal-worktree", GoalWorktreeState.REMOVED),
      view(
        GoalRunnerControlState(
          executionMainCheckoutOutcome = "from_a_newer_runtime",
          executionMainCheckoutDetail = "ignored",
        ),
      ),
    )
  }

  private fun view(state: GoalRunnerControlState): GoalWorktreeView? {
    val controls =
      object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
        override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
          state.copy(executionWorktreePath = "/goal-worktree", executionWorktreeRemovedAt = "2026-11-06T10:00:00Z")
      }
    val database = TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates(), controls = controls)
    return testWorkBoardItemProjection(database).goalWorktreeViewForTest("goal-1", database.unitOfWork())
  }
}
