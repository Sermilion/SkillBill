package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.decomposition.findCompletedPlanWorkflowId
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilityStateCodec
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.artifact.decomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

@Inject
public class PlanWorkflowLookup(
  private val lookupService: FeatureTaskContinuationLookupService,
  private val crashReconciler: FeatureTaskRuntimeCrashReconciler,
  private val database: DatabaseSessionFactory,
  private val feasibilityCodec: PlanBundleFeasibilityStateCodec,
) {
  internal fun completedSelection(
    issueKey: String,
    repositoryIdentities: List<String>,
  ): PlanWorkflowSelection? =
    database.read { unitOfWork ->
      repositoryIdentities.firstNotNullOfOrNull { identity ->
        unitOfWork.workflowStates.findCompletedPlanWorkflowId(issueKey, identity)
      }?.let { id ->
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, id)?.takeIf { it.hasPlanSubtasksStep() }
          ?.let { selected -> selectionOf(id, selected) }
      }
    }

  internal fun selection(workflowId: String): PlanWorkflowSelection? =
    database.read { unitOfWork ->
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)?.let { selectionOf(workflowId, it) }
    }

  private fun selectionOf(
    workflowId: String,
    selected: WorkflowStateSnapshot,
  ): PlanWorkflowSelection {
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY
    val feasibility =
      if (family.contains(selected.artifacts)) {
        feasibilityCodec.decode(family.value(selected.artifacts), "$workflowId/${family.label()}")
      } else {
        null
      }
    return PlanWorkflowSelection(
      workflowId,
      selected.artifacts.decomposeTerminal(),
      feasibility,
      decodePhaseRecords(selected.artifacts)[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS],
    )
  }

  public fun lookup(
    issueKey: String,
    repositoryIdentity: String,
    workflowId: String?,
  ): FeatureTaskContinuationLookupResult = lookupService.lookupPlan(issueKey, repositoryIdentity, workflowId)

  internal fun lookupSelected(
    issueKey: String,
    repositoryIdentities: List<String>,
    workflowId: String,
  ): FeatureTaskContinuationLookupResult =
    lookup(issueKey, lookupService.selectorIdentity(workflowId, repositoryIdentities), workflowId)

  internal fun lookupAfterCrashRecovery(
    issueKey: String,
    repositoryIdentity: String,
  ): FeatureTaskContinuationLookupResult {
    val found = lookup(issueKey, repositoryIdentity, null)
    if (found !is FeatureTaskContinuationLookupResult.AlreadyRunning) return found
    crashReconciler.reconcile(found.candidate.workflowId)
    return lookup(issueKey, repositoryIdentity, null)
  }
}

internal data class PlanWorkflowSelection(
  val workflowId: String,
  val terminal: FeatureTaskRuntimeDecomposeTerminal?,
  val feasibilityState: FeatureTaskRuntimePlanBundleFeasibilityState?,
  val phaseRecord: FeatureTaskRuntimePhaseRecord?,
)
