package skillbill.engine.goalrunner.planning.hydration

import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationResult
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.workflow.engine.model.WorkflowStateSnapshot

interface GoalChildPlanningHydratorPort {
  fun hydrate(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydrationResult

  fun requireMatchingImport(
    unitOfWork: GoalRunnerPersistenceSession,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  )
}
