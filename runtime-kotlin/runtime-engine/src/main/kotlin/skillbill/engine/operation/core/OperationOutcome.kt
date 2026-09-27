package skillbill.engine.operation.core

sealed interface OperationOutcome {
  data class Completed(val text: String) : OperationOutcome

  data class Blocked(val reason: String) : OperationOutcome

  data class Failed(val reason: String) : OperationOutcome

  data class AwaitingConfirmation(
    val token: String,
    val proposalSummary: String,
  ) : OperationOutcome
}
