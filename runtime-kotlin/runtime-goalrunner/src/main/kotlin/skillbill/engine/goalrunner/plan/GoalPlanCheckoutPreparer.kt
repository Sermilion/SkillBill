package skillbill.engine.goalrunner.plan

import java.nio.file.Path

public fun interface GoalPlanCheckoutPreparer {
  public fun prepareGoalCheckout(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    selectedWorkflowId: String?,
  ): StandalonePlanResult.Refused?
}
