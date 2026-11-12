package skillbill.engine.goalrunner.planning.source

import skillbill.application.workflow.decomposition.findCompletedPlanWorkflowId
import skillbill.application.workflow.decomposition.isPlanWorkflow
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goal.goalRepositoryIdentity
import skillbill.engine.goal.goalRepositoryIdentityCandidates
import skillbill.engine.goalrunner.planning.outcome.canonicalStore
import skillbill.engine.goalrunner.planning.outcome.resolvedGovernedPath
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.taskruntime.artifact.goalPlanningImport
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImportSource
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

internal fun WorkflowStateRepository.selectGoalPlanWorkflowId(
  state: GoalRunnerManifestState,
  sourceRoot: Path,
  repositories: RepositoryEnclosingRootPort,
  store: ProjectStoreLocation,
): String? {
  get(WorkflowFamily.TASK_RUNTIME, state.parentWorkflowId)
    ?.step(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN)?.planWorkflowId?.let { return it }
  val identity =
    GoalPlanningIdentity(
      state.parentWorkflowId,
      normalizeRequiredIssueKey(state.manifest.issueKey),
      state.controlState.repositoryIdentity ?: goalRepositoryIdentity(sourceRoot, repositories),
    )
  return findLinkedSubtaskPlanWorkflowId(
    identity,
    state.manifest.subtasks,
    goalRepositoryIdentityCandidates(sourceRoot, repositories),
  ) { workflowId ->
    goalPlanMatches(workflowId, state, sourceRoot, repositories, store)
  }
}

internal fun WorkflowStateRepository.goalPlanMatches(
  workflowId: String,
  state: GoalRunnerManifestState,
  sourceRoot: Path,
  repositories: RepositoryEnclosingRootPort,
  store: ProjectStoreLocation,
): Boolean {
  val plan = get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return false
  val identity = getFeatureTaskExecutionIdentity(workflowId) ?: return false
  return plan.toRecord().isPlanWorkflow() &&
    identity.repositoryIdentity == goalRepositoryIdentity(sourceRoot, repositories) &&
    identity.normalizedIssueKey == normalizeRequiredIssueKey(state.manifest.issueKey) &&
    governsGoalPlanSpec(identity.governedSpecPath, state, sourceRoot, repositories, store)
}

private fun governsGoalPlanSpec(
  planSpec: String,
  state: GoalRunnerManifestState,
  sourceRoot: Path,
  repositories: RepositoryEnclosingRootPort,
  store: ProjectStoreLocation,
): Boolean {
  val canonical = canonicalStore(store, repositories)
  return resolvedGovernedPath(sourceRoot, canonical, planSpec, repositories) ==
    resolvedGovernedPath(sourceRoot, canonical, state.manifest.parentSpecPath, repositories)
}

internal fun WorkflowStateRepository.findReopenedSubtaskPlanWorkflowId(
  issueKey: String,
  repositoryIdentity: String,
): String? =
  findFeatureTaskWorkflowsForIssue(
    FeatureTaskWorkflowMode.RUNTIME,
    normalizeRequiredIssueKey(issueKey),
    repositoryIdentity,
  )
    .filter { row ->
      row.isPlanWorkflow() &&
        row.workflowStatus.workflowStatus() !in WorkflowStatus.terminalStatuses &&
        row.currentStepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS
    }
    .filter { row ->
      val snapshot = row.toSnapshot()
      val phase = decodePhaseRecords(snapshot.artifacts)[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS]
      phase?.status == WorkflowStepStatus.PENDING &&
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.value(snapshot.artifacts) != null &&
        getFeatureTaskRuntimeWorkerOwnership(row.workflowId) == null
    }
    .maxByOrNull { it.updatedAt.orEmpty() }
    ?.workflowId

internal fun WorkflowStateRepository.findImportedSubtaskPlanWorkflowIds(
  identity: GoalPlanningIdentity,
  subtasks: List<DecompositionSubtask>,
): List<String> =
  subtasks.mapNotNull { subtask ->
    val workflowId = subtask.workflowId ?: return@mapNotNull null
    val child = get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@mapNotNull null
    val imported = child.artifacts.goalPlanningImport() ?: return@mapNotNull null
    if (imported.parentGoalWorkflowId != identity.parentGoalWorkflowId ||
      imported.normalizedIssueKey != identity.normalizedIssueKey ||
      imported.repositoryIdentity != identity.repositoryIdentity
    ) {
      return@mapNotNull null
    }
    if (imported.subtaskId != subtask.id) return@mapNotNull null
    imported.source?.takeIf { it.kind == FeatureTaskRuntimeGoalPlanningImportSource.PLAN_WORKFLOW }
      ?.planWorkflowId?.takeIf(String::isNotBlank)
  }.distinct()

internal fun WorkflowStateRepository.findLinkedSubtaskPlanWorkflowId(
  identity: GoalPlanningIdentity,
  subtasks: List<DecompositionSubtask>,
  fallbackPlanIdentities: List<String> = emptyList(),
  governsGoalSpec: (planWorkflowId: String) -> Boolean,
): String? =
  findImportedSubtaskPlanWorkflowIds(identity, subtasks).firstOrNull()
    ?: (listOf(identity.repositoryIdentity) + fallbackPlanIdentities).distinct().firstNotNullOfOrNull { planIdentity ->
      findCompletedPlanWorkflowId(identity.normalizedIssueKey, planIdentity)?.takeIf(governsGoalSpec)
        ?: findReopenedSubtaskPlanWorkflowId(identity.normalizedIssueKey, planIdentity)?.takeIf(governsGoalSpec)
    }
