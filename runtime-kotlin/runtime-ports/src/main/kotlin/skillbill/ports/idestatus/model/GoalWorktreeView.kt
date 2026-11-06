package skillbill.ports.idestatus.model

import skillbill.goalrunner.model.GOAL_MAIN_CHECKOUT_CHECKED_OUT
import skillbill.goalrunner.model.GOAL_MAIN_CHECKOUT_FAILED
import skillbill.goalrunner.model.GOAL_MAIN_CHECKOUT_SKIPPED_UNCOMMITTED_CHANGES

data class GoalWorktreeView(
  val path: String,
  val state: GoalWorktreeState,
  val retentionReason: String? = null,
  val retentionDetail: String? = null,
  val mainCheckoutOutcome: GoalMainCheckoutOutcome? = null,
  val mainCheckoutDetail: String? = null,
)

enum class GoalWorktreeState { ACTIVE, RETAINED, REMOVED }

enum class GoalMainCheckoutOutcome(val wireValue: String) {
  CHECKED_OUT(GOAL_MAIN_CHECKOUT_CHECKED_OUT),
  SKIPPED_UNCOMMITTED_CHANGES(GOAL_MAIN_CHECKOUT_SKIPPED_UNCOMMITTED_CHANGES),
  FAILED(GOAL_MAIN_CHECKOUT_FAILED),
}
