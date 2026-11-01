package skillbill.engine.goalrunner.planning.hydration

import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict
import skillbill.workflow.engine.model.WorkflowStateSnapshot

public interface GoalChildPlanningHydratorPort {
  public fun hydrate(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydrateResult

  public fun requireMatchingImport(
    unitOfWork: GoalRunnerPersistenceSession,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  ): GoalPlanningPreparationConflict?
}
