package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.remediation.buildCompletedUpstreamMissingOutputRepair
import skillbill.engine.featuretask.lifecycle.remediation.featureSizeFromArtifacts
import skillbill.engine.featuretask.model.subtask.CompletedUpstreamRepairRequest
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.planning.PlanBundleSnapshots
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import java.time.Clock

data class PlanSubtasksReplan(
  val issueKey: String,
  val planWorkflowId: String,
  val repoRoot: Path,
  val settledSubtaskIds: Set<Int>,
  val agentId: String? = null,
  val forcedSubtaskIds: Set<Int> = emptySet(),
)

@Inject
class PlanSubtasksReopening(
  private val database: DatabaseSessionFactory,
  private val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner,
  private val planBundles: FeatureTaskRuntimeDecomposeTerminalRecorder,
  private val clock: Clock,
) {
  private val engine = WorkflowEngine()

  fun reopenWithRebasedBundle(replan: PlanSubtasksReplan): Boolean {
    if (!reopenAtPlanSubtasks(replan.planWorkflowId)) return false
    rebasePlanBundle(replan)
    return true
  }

  private fun rebasePlanBundle(replan: PlanSubtasksReplan) {
    val bundle = planBundles.loadPlanBundle(replan.planWorkflowId) ?: return
    PlanBundleSnapshots(decompositionPlanner)
      .rebase(replan.repoRoot, replan.issueKey, bundle, replan.settledSubtaskIds)
      ?.let { rebased ->
        planBundles.recordPlanBundle(replan.planWorkflowId, rebased.withoutPlannedUnits(replan.forcedSubtaskIds))
      }
  }

  fun reopenAtPlanSubtasks(planWorkflowId: String): Boolean =
    database.transaction { unitOfWork ->
      val snapshot = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, planWorkflowId)
      if (snapshot == null || !snapshot.hasPlanSubtasksStep()) {
        false
      } else {
        val update =
          buildCompletedUpstreamMissingOutputRepair(
            CompletedUpstreamRepairRequest(
              phaseRecords = decodePhaseRecords(snapshot.artifacts),
              ledger = decodePhaseLedger(snapshot.artifacts),
              featureSize = featureSizeFromArtifacts(snapshot.artifacts),
              resumePhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
              reason = "Spec drift recovery re-plans the subtask implementation details.",
              clock = clock,
            ),
          )
        // Drop the earlier decompose terminal so the re-planned bundle is verified and the plan completes again.
        val updated =
          engine.updateRecord(
            WorkflowFamily.TASK_RUNTIME.definition,
            snapshot,
            update.copy(
              workflowStatus = WorkflowStatus.PENDING,
              artifactsPatch =
                WorkflowArtifactPatch.from(
                  update.artifactsPatch.orEmpty() +
                    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL.entry(null),
                ),
            ),
          )
        unitOfWork.workflowStates.saveAcceptedSnapshotMutation(WorkflowFamily.TASK_RUNTIME, updated)
        true
      }
    }
}

internal fun WorkflowStateSnapshot.hasPlanSubtasksStep(): Boolean =
  steps.any { it.stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS }
