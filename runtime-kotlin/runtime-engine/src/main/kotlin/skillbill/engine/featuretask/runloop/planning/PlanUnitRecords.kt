package skillbill.engine.featuretask.runloop.planning

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecordFor
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Instant

internal object PlanUnitRecords {
  const val EXISTING_DETAILS_AGENT_ID = "plan-bundle-existing"

  fun settled(
    workflowId: String,
    subtaskId: Int,
    attemptCount: Int,
    resolvedAgentId: String,
    prose: String,
    now: Instant,
  ): FeatureTaskRuntimePhaseRecord =
    record(
      workflowId = workflowId,
      subtaskId = subtaskId,
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      payload =
        PlanDecompositionStop.settledOutput(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
          "Planned subtask $subtaskId.",
          prose,
        ).canonicalJson,
      now = now,
    )

  fun keptDetails(
    workflowId: String,
    subtaskId: Int,
    details: String,
    now: Instant,
  ): FeatureTaskRuntimePhaseRecord =
    record(
      workflowId = workflowId,
      subtaskId = subtaskId,
      attemptCount = 1,
      resolvedAgentId = EXISTING_DETAILS_AGENT_ID,
      payload =
        PlanDecompositionStop.settledOutput(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
          "Kept the Implementation Details of subtask $subtaskId from its spec.",
          details,
        ).canonicalJson,
      now = now,
    )

  private fun record(
    workflowId: String,
    subtaskId: Int,
    attemptCount: Int,
    resolvedAgentId: String,
    payload: String,
    now: Instant,
  ): FeatureTaskRuntimePhaseRecord =
    featureTaskRuntimePhaseRecordFor(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = workflowId,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(subtaskId),
        status = STATUS_COMPLETED,
        attemptCount = attemptCount,
        resolvedAgentId = resolvedAgentId,
        finished = true,
        outputArtifact = payload,
      ),
      null,
      now,
    )
}
