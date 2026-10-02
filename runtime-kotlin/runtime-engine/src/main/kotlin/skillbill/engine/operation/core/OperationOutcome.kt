package skillbill.engine.operation.core

sealed interface OperationOutcome {
  data class Completed(val text: String) : OperationOutcome

  data class Blocked(override val reason: String) : OperationRefusal

  data class Usage(override val reason: String) : OperationRefusal

  data class Failed(val reason: String) : OperationOutcome

  data class AwaitingConfirmation(
    val token: String,
    val proposalSummary: String,
  ) : OperationOutcome
}

/** An operation outcome that refuses the invocation: blocked with nothing changed, or a usage problem. */
sealed interface OperationRefusal : OperationOutcome {
  val reason: String
}
