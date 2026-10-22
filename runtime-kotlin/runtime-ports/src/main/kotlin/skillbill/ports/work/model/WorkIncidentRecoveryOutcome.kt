package skillbill.ports.work.model

import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFailure

sealed interface WorkIncidentRecoveryOutcome {
  data object Accepted : WorkIncidentRecoveryOutcome

  data class LaunchFailed(val reason: String) : WorkIncidentRecoveryOutcome

  data class Rejected(val reason: String, val detail: String?) : WorkIncidentRecoveryOutcome

  data class Recovered(
    val attemptId: String,
    val status: BlockIncidentRecoveryAttemptStatus,
    val failure: BlockIncidentRecoveryFailure?,
    val launch: WorkIncidentRecoveryOutcome?,
  ) : WorkIncidentRecoveryOutcome
}
