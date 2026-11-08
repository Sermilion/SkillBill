package skillbill.infrastructure.launcher.process.interaction

internal interface AgentRunSessionChannel : AgentRunApprovalChannel {
  fun outcome(): AgentRunSessionOutcome?

  fun capture(): AgentRunSessionCapture
}

data class AgentRunSessionCapture(
  val text: String,
  val complete: Boolean,
  val truncated: Boolean,
  val deliveryFailed: Boolean,
  val providerDiagnostics: String? = null,
  val failureDiagnostics: String? = null,
)

data class AgentRunSessionOutcome(
  val text: String,
  val model: String?,
  val threadId: String?,
  val completed: Boolean,
  val failure: String? = null,
  val canContinue: Boolean = false,
)
