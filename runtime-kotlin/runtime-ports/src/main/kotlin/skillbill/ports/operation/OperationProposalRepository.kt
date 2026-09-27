package skillbill.ports.operation

import skillbill.ports.operation.model.OperationProposal

interface OperationProposalRepository {
  /**
   * In one transaction, marks every unconsumed, unsuperseded proposal for the same operation id and repo root as
   * superseded at the new proposal's creation time, then stores [proposal].
   */
  fun createSupersedingPrior(proposal: OperationProposal)

  fun find(token: String): OperationProposal?

  /**
   * Compare-and-set consume: marks [token] consumed only while it is neither consumed nor superseded. Returns false
   * when another invocation consumed it first or it was superseded.
   */
  fun markConsumed(
    token: String,
    consumedAt: String,
  ): Boolean
}
