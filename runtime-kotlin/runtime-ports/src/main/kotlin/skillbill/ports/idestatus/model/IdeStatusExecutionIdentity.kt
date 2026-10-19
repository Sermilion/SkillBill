package skillbill.ports.idestatus.model
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.idestatus.model.IdeStatusLaunchScope

data class IdeStatusExecutionIdentity(
  val scope: IdeStatusExecutionScope,
  val executionId: String,
  val statusStoreId: String,
  val runSequence: String,
  val statusRevision: String,
  val invocationId: String? = null,
  val phaseId: String? = null,
  val launchScope: IdeStatusLaunchScope = IdeStatusLaunchScope.STANDALONE,
  val qaOutcome: StandaloneQaOutcome? = null,
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
        require(launchScope == IdeStatusLaunchScope.STANDALONE) { "Workflow status cannot carry a goal launch." }
        require(qaOutcome == null) { "Workflow status cannot carry a QA outcome." }
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
