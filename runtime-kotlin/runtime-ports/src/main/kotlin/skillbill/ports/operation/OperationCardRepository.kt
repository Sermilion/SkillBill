package skillbill.ports.operation

import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.contracts.operationcard.OperationLaunchContext
import skillbill.ports.operation.model.OperationProposal

interface OperationCardRepository : OperationConfirmationLedger {
  /** Identity of the selected database store; the launch context and every card it publishes name it. */
  fun databaseIdentity(): String

  /** Saves the launch context before the child process starts. A context alone is never consent. */
  fun saveLaunchContext(context: OperationLaunchContext)

  fun findLaunchContext(launchId: String): OperationLaunchContext?

  /**
   * In one transaction, stores [authority] when the producer has no authoritative proposal of its own (verify binds
   * its workflow id as the token), marks every earlier awaiting card for the same operation and repository as
   * superseded, and stores [card]. Returns the launch contexts of the superseded cards so their holders can be
   * released after the replacement holds the checkout.
   */
  fun publish(
    card: OperationCardProjection,
    authority: OperationProposal?,
  ): List<OperationLaunchContext>

  fun find(token: String): OperationCardProjection?

  fun findByLaunch(launchId: String): OperationCardProjection?

  /**
   * In one transaction, supersedes the authoritative proposal for [token] and marks an awaiting card cancelled, so a
   * dismissed card cannot reuse its token. Returns false when the card is not awaiting confirmation.
   */
  fun dismiss(
    token: String,
    at: String,
  ): Boolean
}
