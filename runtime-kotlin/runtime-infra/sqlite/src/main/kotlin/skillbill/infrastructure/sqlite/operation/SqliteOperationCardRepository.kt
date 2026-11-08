package skillbill.infrastructure.sqlite.operation

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.contracts.operationcard.OperationCardStatus
import skillbill.contracts.operationcard.OperationLaunchContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.operation.OperationCardRepository
import skillbill.ports.operation.model.ConfirmationExecution
import skillbill.ports.operation.model.OperationProposal
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity

@Inject
class SqliteOperationCardRepository(
  private val databaseSessionFactory: DatabaseSessionFactory,
) : OperationCardRepository {
  override fun databaseIdentity(): String = databaseSessionFactory.read { it.operationCards.databaseIdentity() }

  override fun saveLaunchContext(context: OperationLaunchContext) {
    databaseSessionFactory.transaction { it.operationCards.saveLaunchContext(context) }
  }

  override fun findLaunchContext(launchId: String): OperationLaunchContext? =
    databaseSessionFactory.read { it.operationCards.findLaunchContext(launchId) }

  override fun publish(
    card: OperationCardProjection,
    authority: OperationProposal?,
  ): List<OperationLaunchContext> = databaseSessionFactory.transaction { it.operationCards.publish(card, authority) }

  override fun find(token: String): OperationCardProjection? =
    databaseSessionFactory.read { it.operationCards.find(token) }

  override fun findByLaunch(launchId: String): OperationCardProjection? =
    databaseSessionFactory.read { it.operationCards.findByLaunch(launchId) }

  override fun admitConfirmation(
    token: String,
    selection: String?,
    owner: FeatureTaskRuntimeProcessIdentity,
  ): ConfirmationExecution? =
    databaseSessionFactory.transaction { it.operationCards.admitConfirmation(token, selection, owner) }

  override fun activeExecution(token: String): ConfirmationExecution? =
    databaseSessionFactory.read { it.operationCards.activeExecution(token) }

  override fun claimConfirmation(
    token: String,
    launchId: String,
    invocationId: String,
    owner: FeatureTaskRuntimeProcessIdentity,
  ): Boolean =
    databaseSessionFactory.transaction { it.operationCards.claimConfirmation(token, launchId, invocationId, owner) }

  override fun consumeClaimedConfirmation(
    token: String,
    launchId: String,
    invocationId: String,
    at: String,
  ): Boolean =
    databaseSessionFactory.transaction {
      it.operationCards.consumeClaimedConfirmation(token, launchId, invocationId, at)
    }

  override fun settleConfirmation(
    token: String,
    executionId: Long,
    to: OperationCardStatus,
    evidence: String?,
    at: String,
  ): Boolean =
    databaseSessionFactory.transaction {
      it.operationCards.settleConfirmation(token, executionId, to, evidence, at)
    }

  override fun settleUnrecordedConfirmation(
    token: String,
    to: OperationCardStatus,
    evidence: String?,
    at: String,
  ): Boolean =
    databaseSessionFactory.transaction { it.operationCards.settleUnrecordedConfirmation(token, to, evidence, at) }

  override fun pendingCleanup(token: String): ConfirmationExecution? =
    databaseSessionFactory.read { it.operationCards.pendingCleanup(token) }

  override fun completeCleanup(executionId: Long) {
    databaseSessionFactory.transaction { it.operationCards.completeCleanup(executionId) }
  }

  override fun dismiss(
    token: String,
    at: String,
  ): Boolean = databaseSessionFactory.transaction { it.operationCards.dismiss(token, at) }
}
