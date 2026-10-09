package skillbill.contracts.workflow.identity.status

const val IDE_STATUS_CONTRACT_VERSION: String = "0.3"

object IdeStatusPayloadKeys {
  const val BRANCH_CORRELATION = "branch_correlation"
  const val EXECUTION_SCOPE = "execution_scope"
  const val EXECUTION_ID = "execution_id"
  const val STATUS_STORE_ID = "status_store_id"
  const val RUN_SEQUENCE = "run_sequence"
  const val STATUS_REVISION = "status_revision"
  const val INVOCATION_ID = "invocation_id"
  const val CURRENT_ACTIVITY = "current_activity"
}

const val GOAL_PLANNING_WAVE_CAP: Int = 5
