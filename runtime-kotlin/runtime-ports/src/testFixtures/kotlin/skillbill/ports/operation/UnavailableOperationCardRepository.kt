package skillbill.ports.operation

import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.contracts.operationcard.OperationCardStatus
import skillbill.contracts.operationcard.OperationLaunchContext
import skillbill.ports.operation.model.ConfirmationExecution
import skillbill.ports.operation.model.OperationProposal
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity

object UnavailableOperationCardRepository : OperationCardRepository {
  override fun databaseIdentity(): String = error(UNAVAILABLE)

  override fun saveLaunchContext(context: OperationLaunchContext): Unit = error(UNAVAILABLE)

  override fun findLaunchContext(launchId: String): OperationLaunchContext? = error(UNAVAILABLE)

  override fun publish(
    card: OperationCardProjection,
    authority: OperationProposal?,
  ): List<OperationLaunchContext> = error(UNAVAILABLE)

  override fun find(token: String): OperationCardProjection? = error(UNAVAILABLE)

  override fun findByLaunch(launchId: String): OperationCardProjection? = error(UNAVAILABLE)

  override fun admitConfirmation(
    token: String,
    selection: String?,
    owner: FeatureTaskRuntimeProcessIdentity,
  ): ConfirmationExecution? = error(UNAVAILABLE)

  override fun activeExecution(token: String): ConfirmationExecution? = error(UNAVAILABLE)

  override fun claimConfirmation(
    token: String,
    launchId: String,
    invocationId: String,
    owner: FeatureTaskRuntimeProcessIdentity,
  ): Boolean = error(UNAVAILABLE)

  override fun settleConfirmation(
    token: String,
    executionId: Long,
    to: OperationCardStatus,
    evidence: String?,
    at: String,
  ): Boolean = error(UNAVAILABLE)

  override fun settleUnrecordedConfirmation(
    token: String,
    to: OperationCardStatus,
    evidence: String?,
    at: String,
  ): Boolean = error(UNAVAILABLE)

  override fun pendingCleanup(token: String): ConfirmationExecution? = error(UNAVAILABLE)

  override fun completeCleanup(executionId: Long): Unit = error(UNAVAILABLE)

  override fun consumeClaimedConfirmation(
    token: String,
    launchId: String,
    invocationId: String,
    at: String,
  ): Boolean = error(UNAVAILABLE)

  override fun dismiss(
    token: String,
    at: String,
  ): Boolean = error(UNAVAILABLE)
}

private const val UNAVAILABLE = "This unit of work has no operation card store."
