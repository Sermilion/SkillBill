package skillbill.goalrunner.model

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.identity.status.WorkDetailPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys

internal object GoalAttemptLedgerStatusKeys {
  const val ACTION = DecompositionManifestPayloadKeys.ACTION
  const val SUBTASK_ID = SharedPayloadKeys.SUBTASK_ID
  const val STOP_REASON = "stop_reason"
  const val DIAGNOSTIC_CLASS = "diagnostic_class"
  const val CURRENT_STEP = "current_step"
  const val PREVIOUS_STEP = "previous_step"
  const val LOOP_ID = FeatureTaskRuntimeExecutionPlanKeys.LOOP_ID
  const val CUMULATIVE_LOOP_COUNT = "cumulative_loop_count"
  const val RE_ATTEMPT_CAUSE = "re_attempt_cause"
  const val FINDINGS_IN_SCOPE = WorkDetailPayloadKeys.FINDINGS_IN_SCOPE
}
