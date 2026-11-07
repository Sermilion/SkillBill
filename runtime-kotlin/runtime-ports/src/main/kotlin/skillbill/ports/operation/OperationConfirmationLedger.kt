package skillbill.ports.operation

import skillbill.contracts.operationcard.OperationCardStatus
import skillbill.ports.operation.model.ConfirmationExecution
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity

/** The executions an operator admitted for operation cards, from admission through settlement and holder cleanup. */
interface OperationConfirmationLedger {
  /**
   * In one transaction, moves an awaiting card to confirming with the operator's [selection] and records the
   * admitting [owner] as the single active execution. Returns null when the card is no longer awaiting.
   */
  fun admitConfirmation(
    token: String,
    selection: String?,
    owner: FeatureTaskRuntimeProcessIdentity,
  ): ConfirmationExecution?

  /** The admitted or claimed execution of a confirming card, or null when none is active. */
  fun activeExecution(token: String): ConfirmationExecution?

  /**
   * Claims the admitted, still unclaimed execution of [launchId] for its actual child [invocationId] and process
   * before operation effects begin. At most one claim succeeds per admission.
   */
  fun claimConfirmation(
    token: String,
    launchId: String,
    invocationId: String,
    owner: FeatureTaskRuntimeProcessIdentity,
  ): Boolean

  /** Atomically verifies the claimed invocation and consumes its proposal before operation effects. */
  fun consumeClaimedConfirmation(
    token: String,
    launchId: String,
    invocationId: String,
    at: String,
  ): Boolean

  /**
   * In one transaction, retires the active execution [executionId] and moves the confirming card to [to], keeping
   * the execution as history. Terminal targets record that the launch holder still needs cleanup; a refused target
   * also supersedes the unconsumed authority. Returns false when [executionId] is not the active execution.
   */
  fun settleConfirmation(
    token: String,
    executionId: Long,
    to: OperationCardStatus,
    evidence: String?,
    at: String,
  ): Boolean

  /**
   * Settles a confirming card that has no recorded execution, which only a card admitted before executions were
   * recorded can be. Returns false when an execution exists or the card is not confirming.
   */
  fun settleUnrecordedConfirmation(
    token: String,
    to: OperationCardStatus,
    evidence: String?,
    at: String,
  ): Boolean

  /** A retired execution of [token] whose launch holder has not yet been released, or null. */
  fun pendingCleanup(token: String): ConfirmationExecution?

  fun completeCleanup(executionId: Long)
}
