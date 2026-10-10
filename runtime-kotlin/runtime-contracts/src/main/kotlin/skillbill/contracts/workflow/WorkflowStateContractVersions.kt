package skillbill.contracts.workflow

const val WORKFLOW_STATE_CONTRACT_VERSION: String = "0.3"

val WORKFLOW_STATE_READABLE_CONTRACT_VERSIONS: Set<String> =
  setOf(
    "0.1",
    "0.2",
    WORKFLOW_STATE_CONTRACT_VERSION,
  )
