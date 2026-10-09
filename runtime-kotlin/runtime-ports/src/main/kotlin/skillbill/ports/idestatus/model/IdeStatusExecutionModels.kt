package skillbill.ports.idestatus.model

enum class IdeStatusExecutionScope(val wireValue: String) {
  WORKFLOW("workflow"),
  STANDALONE_PHASE("standalone_phase"),
}

data class IdeStatusExecutionIdentity(
  val scope: IdeStatusExecutionScope,
  val executionId: String,
  val statusStoreId: String,
  val runSequence: String,
  val statusRevision: String,
  val invocationId: String? = null,
  val phaseId: String? = null,
) {
  init {
    require(executionId.isNotBlank()) { "executionId must not be blank." }
    require(statusStoreId.isNotBlank()) { "statusStoreId must not be blank." }
    require(isPositiveDecimal(runSequence)) { "runSequence must be a positive decimal string." }
    require(isPositiveDecimal(statusRevision)) { "statusRevision must be a positive decimal string." }
    when (scope) {
      IdeStatusExecutionScope.WORKFLOW -> {
        require(invocationId == null) { "Workflow status cannot carry invocationId." }
        require(phaseId == null) { "Workflow status cannot carry phaseId." }
      }
      IdeStatusExecutionScope.STANDALONE_PHASE -> {
        require(!invocationId.isNullOrBlank()) { "Standalone phase status requires invocationId." }
        require(!phaseId.isNullOrBlank()) { "Standalone phase status requires phaseId." }
      }
    }
  }

  companion object {
    private val POSITIVE_DECIMAL = Regex("[1-9][0-9]*")

    fun isPositiveDecimal(value: String): Boolean = POSITIVE_DECIMAL.matches(value)
  }
}
