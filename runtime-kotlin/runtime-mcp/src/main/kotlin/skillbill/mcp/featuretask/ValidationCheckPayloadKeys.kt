package skillbill.mcp.featuretask

internal object ValidationCheckPayloadKeys {
  const val EVENT = "event"
  const val CHECK_ID = "check_id"
  const val NAME = "name"
  const val COMMAND = "command"
  const val WORKING_DIR = "working_dir"
  const val OUTCOME = "outcome"
  const val EXIT_CODE = "exit_code"
  const val NOTE = "note"
  const val SEQUENCE = "sequence"
}

internal enum class ValidationCheckResultStatus(val wireValue: String) {
  RECORDED("recorded"),
  REJECTED("rejected"),
  FAILED("failed"),
}
