package skillbill.engine.goalrunner.planning.hydration

import java.time.Clock
import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationResult
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydrationOutcome.Conflicted
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydrationOutcome.Hydrated
import skillbill.engine.goalrunner.planning.model.GoalChildPlanningHydration
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepUpdates

@Inject
class GoalChildPlanningHydratorPortAdapter(
  clock: Clock,
) : GoalChildPlanningHydratorPort {
  private val hydrator = GoalChildPlanningHydrator(clock)

  override fun hydrate(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydrateResult =
    when (val result = hydrator.hydrate(unitOfWork, setup, request)) {
      is Hydrated -> GoalChildPlanningHydrateResult.Hydrated(result.hydration.toPortResult())
      is Conflicted -> GoalChildPlanningHydrateResult.Conflicted(result.conflict)
    }

  override fun requireMatchingImport(
    unitOfWork: GoalRunnerPersistenceSession,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  ): GoalPlanningPreparationConflict? = hydrator.requireMatchingImport(unitOfWork, existing, setup)

  private fun GoalChildPlanningHydration.toPortResult() =
    GoalChildPlanningHydrationResult(
      currentStepId = currentStepId,
      stepUpdates = WorkflowStepUpdates.from(stepUpdates) ?: WorkflowStepUpdates.EMPTY,
      artifacts = WorkflowArtifactPatch.from(artifacts) ?: WorkflowArtifactPatch.EMPTY,
    )
}
