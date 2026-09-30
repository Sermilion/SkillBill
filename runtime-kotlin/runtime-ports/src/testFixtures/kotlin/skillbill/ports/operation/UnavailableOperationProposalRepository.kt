package skillbill.ports.operation

import skillbill.ports.operation.model.OperationProposal

object UnavailableOperationProposalRepository : OperationProposalRepository {
  override fun createSupersedingPrior(proposal: OperationProposal): Unit =
    error("This unit of work has no operation proposal store.")

  override fun find(token: String): OperationProposal? = error("This unit of work has no operation proposal store.")

  override fun markConsumed(
    token: String,
    consumedAt: String,
  ): Boolean = error("This unit of work has no operation proposal store.")
}
