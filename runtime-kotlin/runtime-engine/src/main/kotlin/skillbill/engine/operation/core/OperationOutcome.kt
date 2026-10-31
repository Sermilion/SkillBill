package skillbill.engine.operation.core

public sealed interface OperationOutcome {
  public data class Completed(val text: String) : OperationOutcome

  public data class Blocked(override val reason: String) : OperationRefusal

  public data class Usage(override val reason: String) : OperationRefusal

  public data class Failed(val reason: String) : OperationOutcome

  public data class AwaitingConfirmation(
    val token: String,
    val proposalSummary: String,
  ) : OperationOutcome
}

/** An operation outcome that refuses the invocation: blocked with nothing changed, or a usage problem. */
public sealed interface OperationRefusal : OperationOutcome {
  public val reason: String
}
