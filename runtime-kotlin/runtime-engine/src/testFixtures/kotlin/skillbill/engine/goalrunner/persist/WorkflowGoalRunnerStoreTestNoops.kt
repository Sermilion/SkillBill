package skillbill.engine.goalrunner.persist

import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationResult
import skillbill.engine.goalrunner.model.GoalRunnerChildRepairApplyRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildRepairApplyResult
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydratorPort
import skillbill.engine.goalrunner.repair.GoalRunnerChildRepairRunnerPort
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepUpdates
import java.nio.file.Path

object NoopGoalChildPlanningHydrator : GoalChildPlanningHydratorPort {
  override fun hydrate(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydrationResult =
    GoalChildPlanningHydrationResult(
      currentStepId = setup.workflowId,
      stepUpdates = WorkflowStepUpdates.EMPTY,
      artifacts = WorkflowArtifactPatch.EMPTY,
    )

  override fun requireMatchingImport(
    unitOfWork: GoalRunnerPersistenceSession,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  ) = Unit
}

object NoopGoalRunnerChildRepairRunner : GoalRunnerChildRepairRunnerPort {
  override fun diagnose(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerChildWedgeDiagnosis =
    GoalRunnerChildWedgeDiagnosis(
      subtaskId = subtaskId,
      workflowId = workflowId,
      passedChecks = emptyList(),
    )

  override fun apply(request: GoalRunnerChildRepairApplyRequest): GoalRunnerChildRepairApplyResult =
    GoalRunnerChildRepairApplyResult()
}
