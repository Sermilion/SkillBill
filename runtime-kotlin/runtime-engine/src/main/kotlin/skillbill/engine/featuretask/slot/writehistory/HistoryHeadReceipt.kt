package skillbill.engine.featuretask.slot.writehistory

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

private const val HEAD_SETTLED_HISTORY_SUMMARY = "Boundary history settled from repository HEAD."

internal object HistoryHeadReceipt {
  fun syntheticOutput(
    phaseId: String,
    attemptCount: Int,
  ): FeatureTaskRuntimePhaseOutput? =
    when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY -> {
        val payload =
          JsonCodec.mapToJsonString(
            mapOf(
              SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
              SharedPayloadKeys.PHASE_ID to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
              SharedPayloadKeys.STATUS to "completed",
              SharedPayloadKeys.SUMMARY to HEAD_SETTLED_HISTORY_SUMMARY,
              SharedPayloadKeys.PRODUCED_OUTPUTS to
                mapOf(
                  SharedPayloadKeys.VALUE to HEAD_SETTLED_HISTORY_SUMMARY,
                  FeatureTaskRuntimeMeasuredFactKeys.MEASURED_FACTS to
                    mapOf(
                      FeatureTaskRuntimeMeasuredFactKeys.CHANGED_PATHS to FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN,
                      FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN to FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN,
                      FeatureTaskRuntimeMeasuredFactKeys.DECISIONS_RECORDED to
                        FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN,
                    ),
                ),
            ),
          )
        FeatureTaskRuntimePhaseOutput(
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
          iteration = attemptCount.coerceAtLeast(1),
          payload = payload,
        )
      }
      else -> null
    }
}
