package skillbill.engine.featuretask.runloop.planning

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecordFor
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Instant

internal data class PlanUnit(
  val workflowId: String,
  val stepId: String,
  val subtaskId: Int,
)

internal data class PlanUnitAttempt(
  val attemptCount: Int,
  val resolvedAgentId: String,
)

internal object PlanUnitRecords {
  const val EXISTING_DETAILS_AGENT_ID = "plan-bundle-existing"

  fun settled(
    unit: PlanUnit,
    attempt: PlanUnitAttempt,
    prose: String,
    now: Instant,
  ): FeatureTaskRuntimePhaseRecord =
    record(
      unit = unit,
      attempt = attempt,
      payload =
        PlanDecompositionStop.settledOutput(unit.stepId, "Planned subtask ${unit.subtaskId}.", prose).canonicalJson,
      now = now,
    )

  fun keptDetails(
    unit: PlanUnit,
    details: String,
    now: Instant,
  ): FeatureTaskRuntimePhaseRecord =
    record(
      unit = unit,
      attempt = PlanUnitAttempt(attemptCount = 1, resolvedAgentId = EXISTING_DETAILS_AGENT_ID),
      payload =
        PlanDecompositionStop.settledOutput(
          unit.stepId,
          "Kept the Implementation Details of subtask ${unit.subtaskId} from its spec.",
          details,
        ).canonicalJson,
      now = now,
    )

  private fun record(
    unit: PlanUnit,
    attempt: PlanUnitAttempt,
    payload: String,
    now: Instant,
  ): FeatureTaskRuntimePhaseRecord =
    featureTaskRuntimePhaseRecordFor(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = unit.workflowId,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(unit.subtaskId),
        status = STATUS_COMPLETED,
        attemptCount = attempt.attemptCount,
        resolvedAgentId = attempt.resolvedAgentId,
        finished = true,
        outputArtifact = payload,
      ),
      null,
      now,
    )
}
