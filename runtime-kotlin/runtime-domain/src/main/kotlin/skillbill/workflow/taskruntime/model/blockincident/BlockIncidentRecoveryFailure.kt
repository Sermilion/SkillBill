package skillbill.workflow.taskruntime.model.blockincident

import skillbill.contracts.workflow.identity.blockincident.BlockIncidentRecoveryKeys

enum class BlockIncidentRecoveryFailure(val wireValue: String) {
  STALE_INCIDENT(BlockIncidentRecoveryKeys.FAILURE_STALE_INCIDENT),
  PREREQUISITE_UNMET(BlockIncidentRecoveryKeys.FAILURE_PREREQUISITE_UNMET),
  OWNERSHIP_MISMATCH(BlockIncidentRecoveryKeys.FAILURE_OWNERSHIP_MISMATCH),
  ACTION_FAILED(BlockIncidentRecoveryKeys.FAILURE_ACTION_FAILED),
  BLOCK_NOT_CLEARED(BlockIncidentRecoveryKeys.FAILURE_BLOCK_NOT_CLEARED),
  RESUME_FAILED(BlockIncidentRecoveryKeys.FAILURE_RESUME_FAILED),
  INTERRUPTED(BlockIncidentRecoveryKeys.FAILURE_INTERRUPTED),
}
