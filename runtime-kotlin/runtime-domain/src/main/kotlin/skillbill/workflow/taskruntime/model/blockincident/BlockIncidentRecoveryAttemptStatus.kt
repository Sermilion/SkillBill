package skillbill.workflow.taskruntime.model.blockincident

import skillbill.contracts.workflow.identity.blockincident.BlockIncidentRecoveryKeys

enum class BlockIncidentRecoveryAttemptStatus(val wireValue: String) {
  REQUESTED(BlockIncidentRecoveryKeys.STATUS_REQUESTED),
  APPLYING(BlockIncidentRecoveryKeys.STATUS_APPLYING),
  APPLIED(BlockIncidentRecoveryKeys.STATUS_APPLIED),
  RESUMING(BlockIncidentRecoveryKeys.STATUS_RESUMING),
  RESUMED(BlockIncidentRecoveryKeys.STATUS_RESUMED),
  FAILED(BlockIncidentRecoveryKeys.STATUS_FAILED),
  ;

  fun canMoveTo(next: BlockIncidentRecoveryAttemptStatus): Boolean =
    when (this) {
      REQUESTED -> next == APPLYING || next == FAILED
      APPLYING -> next == APPLIED || next == FAILED
      APPLIED -> next == RESUMING || next == FAILED
      RESUMING -> next == RESUMED || next == FAILED
      RESUMED, FAILED -> false
    }

  companion object {
    val IN_PROGRESS = setOf(REQUESTED, APPLYING, RESUMING)
    val OWNED = setOf(APPLYING, RESUMING)
  }
}
