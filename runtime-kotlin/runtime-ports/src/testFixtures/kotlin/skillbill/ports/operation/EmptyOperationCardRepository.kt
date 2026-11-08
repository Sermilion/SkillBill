package skillbill.ports.operation

import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.contracts.operationcard.OperationCardStatus
import skillbill.contracts.operationcard.OperationLaunchContext
import skillbill.ports.operation.model.ConfirmationExecution
import skillbill.ports.operation.model.OperationProposal
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity

object EmptyOperationCardRepository : OperationCardRepository {
  override fun databaseIdentity(): String = ""

  override fun saveLaunchContext(context: OperationLaunchContext) = Unit

  override fun findLaunchContext(launchId: String): OperationLaunchContext? = null

  override fun publish(
    card: OperationCardProjection,
    authority: OperationProposal?,
  ): List<OperationLaunchContext> = emptyList()

  override fun find(token: String): OperationCardProjection? = null

  override fun findByLaunch(launchId: String): OperationCardProjection? = null

  override fun admitConfirmation(
    token: String,
    selection: String?,
    owner: FeatureTaskRuntimeProcessIdentity,
  ): ConfirmationExecution? = null

  override fun activeExecution(token: String): ConfirmationExecution? = null

  override fun claimConfirmation(
    token: String,
    launchId: String,
    invocationId: String,
    owner: FeatureTaskRuntimeProcessIdentity,
  ): Boolean = false

  override fun consumeClaimedConfirmation(
    token: String,
    launchId: String,
    invocationId: String,
    at: String,
  ): Boolean = false

  override fun settleConfirmation(
    token: String,
    executionId: Long,
    to: OperationCardStatus,
    evidence: String?,
    at: String,
  ): Boolean = false

  override fun settleUnrecordedConfirmation(
    token: String,
    to: OperationCardStatus,
    evidence: String?,
    at: String,
  ): Boolean = false

  override fun pendingCleanup(token: String): ConfirmationExecution? = null

  override fun completeCleanup(executionId: Long) = Unit

  override fun dismiss(
    token: String,
    at: String,
  ): Boolean = false
}
