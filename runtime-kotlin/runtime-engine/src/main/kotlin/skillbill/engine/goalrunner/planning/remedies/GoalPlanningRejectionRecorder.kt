package skillbill.engine.goalrunner.planning.remedies

import me.tatarka.inject.annotations.Inject
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.goalrunner.planning.model.GoalPlanningRejectionRecord
import skillbill.engine.goalrunner.telemetry.GoalRunnerBestEffortEmission
import skillbill.error.core.failureCodeLabel
import skillbill.ports.diagnostics.RuntimeDiagnostics

fun interface GoalPlanningRejectionRecorder {
  fun record(record: GoalPlanningRejectionRecord)
}

@Inject
class DurableGoalPlanningRejectionRecorder(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val diagnostics: RuntimeDiagnostics,
) : GoalPlanningRejectionRecorder {
  override fun record(record: GoalPlanningRejectionRecord) {
    GoalRunnerBestEffortEmission.runCancellable {
      recorder.recordRejectedOutput(
        RejectedOutputDiagnosticRequest(
          workflowId = record.parentWorkflowId,
          phaseId = record.phaseId,
          attempt = record.attempt.coerceAtLeast(1),
          rule = record.rule,
          path = "/",
          reason = record.reason,
          agentId = record.agentId,
          model = "unspecified",
          rawResponse = record.rawEvidence.encodeToByteArray(),
        ),
      )
    }.onFailure { error ->
      GoalRunnerBestEffortEmission.rethrowIfCancellation(error)
      GoalRunnerBestEffortEmission.recordWarning(
        diagnostics,
        GoalRunnerBestEffortEmission.boundedMessage(
          "Degraded write at seam goal-planning.rejection_diagnostic for workflow " +
            "'${record.parentWorkflowId}' phase '${record.phaseId}': expected rejected_output_recorded, " +
            "used rejection_not_recorded (${error.failureCodeLabel() ?: error::class.simpleName}).",
        ),
        error,
      )
    }
  }
}
