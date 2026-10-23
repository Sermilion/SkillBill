package skillbill.contracts.workflow.identity.blockincident

object BlockIncidentRecoveryKeys {
  const val STATUS_REQUESTED: String = "requested"
  const val STATUS_APPLYING: String = "applying"
  const val STATUS_APPLIED: String = "applied"
  const val STATUS_RESUMING: String = "resuming"
  const val STATUS_RESUMED: String = "resumed"
  const val STATUS_FAILED: String = "failed"
  const val FAILURE_STALE_INCIDENT: String = "stale_incident"
  const val FAILURE_PREREQUISITE_UNMET: String = "prerequisite_unmet"
  const val FAILURE_OWNERSHIP_MISMATCH: String = "ownership_mismatch"
  const val FAILURE_ACTION_FAILED: String = "action_failed"
  const val FAILURE_BLOCK_NOT_CLEARED: String = "block_not_cleared"
  const val FAILURE_RESUME_FAILED: String = "resume_failed"
  const val FAILURE_INTERRUPTED: String = "interrupted"
}
