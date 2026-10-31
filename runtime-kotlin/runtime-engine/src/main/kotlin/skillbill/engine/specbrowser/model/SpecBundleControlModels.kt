package skillbill.engine.specbrowser.model

public enum class SpecBundleRunState {
  NOT_STARTED,
  RUNNING,
  PAUSE_REQUESTED,
  PAUSED,
  BLOCKED,
  CANCELED,
  COMPLETE,
  UNAVAILABLE,
}

public data class SpecBundleControl(
  val issueKey: String?,
  val runState: SpecBundleRunState,
  val everStarted: Boolean,
  val workflowId: String?,
  val repositoryIdentity: String?,
  val canStart: Boolean,
  val startLabel: String,
  val canPause: Boolean,
  val pauseRequested: Boolean,
  val canCancel: Boolean,
  val purgeVisible: Boolean,
  val canPurge: Boolean,
  val agentId: String?,
  val agentChoices: List<String>,
  val unavailableReason: String?,
)
