package skillbill.infrastructure.sqlite.operation

import me.tatarka.inject.annotations.Inject
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.operation.model.OperationProposal

@Inject
class SqliteOperationProposalRepository(
  private val databaseSessionFactory: DatabaseSessionFactory,
) : OperationProposalRepository {
  override fun createSupersedingPrior(proposal: OperationProposal) {
    databaseSessionFactory.transaction { it.operationProposals.createSupersedingPrior(proposal) }
  }

  override fun find(token: String): OperationProposal? = databaseSessionFactory.read { it.operationProposals.find(token) }

  override fun markConsumed(
    token: String,
    consumedAt: String,
  ): Boolean = databaseSessionFactory.transaction { it.operationProposals.markConsumed(token, consumedAt) }
}
