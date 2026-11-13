package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.decomposition.findCompletedPlanWorkflowId
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilityStateCodec
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.decomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause

@Inject
public class PlanWorkflowLookup(
  private val lookupService: FeatureTaskContinuationLookupService,
  private val crashReconciler: FeatureTaskRuntimeCrashReconciler,
  private val database: DatabaseSessionFactory,
  private val feasibilityCodec: PlanBundleFeasibilityStateCodec,
  private val diagnostics: RuntimeDiagnostics,
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
    lookupAfterCrashRecovery(issueKey, lookupService.selectorIdentity(workflowId, repositoryIdentities), workflowId)

  internal fun lookupAfterCrashRecovery(
    issueKey: String,
    repositoryIdentity: String,
    workflowId: String? = null,
  ): FeatureTaskContinuationLookupResult {
    val found = lookup(issueKey, repositoryIdentity, workflowId)
    if (found !is FeatureTaskContinuationLookupResult.AlreadyRunning) return found
    crashReconciler.reconcile(found.candidate.workflowId)
    recoverReleasedProviderPause(found.candidate.workflowId)
    return lookup(issueKey, repositoryIdentity, workflowId)
  }

  private fun recoverReleasedProviderPause(workflowId: String) {
    val recovered =
      database.transaction { unit ->
        val states = unit.workflowStates
        if (states.getFeatureTaskRuntimeWorkerOwnership(workflowId) != null ||
          states.getLatestFeatureTaskRuntimeWorkerGeneration(workflowId) == null
        ) {
          return@transaction false
        }
        val snapshot = states.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@transaction false
        if (snapshot.workflowStatus != WorkflowStatus.RUNNING) return@transaction false
        val records = decodePhaseRecords(snapshot.artifacts)
        if (records.values.any { it.status == WorkflowStepStatus.RUNNING }) return@transaction false
        val providerPausedUnit =
          records.any { (phaseId, record) ->
            FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitIdOf(phaseId) != null &&
              record.status == WorkflowStepStatus.PAUSED &&
              FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(record.blockedReason.orEmpty())
          }
        if (!providerPausedUnit) return@transaction false
        states.save(WorkflowFamily.TASK_RUNTIME, snapshot.copy(workflowStatus = WorkflowStatus.PENDING))
        true
      }
    if (recovered) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Recovered released plan worker '$workflowId' after a persisted provider-limit pause; " +
          "phase records, accepted plans and workflow history were retained.",
      )
    }
  }
}

internal data class PlanWorkflowSelection(
  val workflowId: String,
  val terminal: FeatureTaskRuntimeDecomposeTerminal?,
  val feasibilityState: FeatureTaskRuntimePlanBundleFeasibilityState?,
  val phaseRecord: FeatureTaskRuntimePhaseRecord?,
)
