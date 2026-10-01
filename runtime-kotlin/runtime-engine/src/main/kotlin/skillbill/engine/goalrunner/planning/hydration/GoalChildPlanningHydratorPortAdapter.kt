package skillbill.engine.goalrunner.planning.hydration

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationResult
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.planning.model.GoalChildPlanningHydration
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepUpdates
import java.time.Clock

@Inject
class GoalChildPlanningHydratorPortAdapter(
  clock: Clock,
) : GoalChildPlanningHydratorPort {
  private val hydrator = GoalChildPlanningHydrator(clock)

  override fun hydrate(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydrationResult = hydrator.hydrate(unitOfWork, setup, request).toPortResult()

  override fun requireMatchingImport(
    unitOfWork: GoalRunnerPersistenceSession,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  ) = hydrator.requireMatchingImport(unitOfWork, existing, setup)

  private fun GoalChildPlanningHydration.toPortResult() =
    GoalChildPlanningHydrationResult(
      currentStepId = currentStepId,
      stepUpdates = WorkflowStepUpdates.from(stepUpdates) ?: WorkflowStepUpdates.EMPTY,
      artifacts = WorkflowArtifactPatch.from(artifacts) ?: WorkflowArtifactPatch.EMPTY,
    )
}
