package skillbill.engine.operation.core

import me.tatarka.inject.annotations.Inject
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.operation.model.OperationAnchors
import skillbill.ports.operation.model.OperationProposal
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.time.Clock
import java.util.UUID

@Inject
class OperationConfirmationGate(
  private val proposals: OperationProposalRepository,
  private val gitOperations: WorkflowGitOperations,
  private val clock: Clock,
) {
  fun propose(
    operation: ConfirmableOperation,
    context: OperationContext,
    proposed: OperationRunResult.Proposed,
  ): OperationOutcome.AwaitingConfirmation {
    val token = "$TOKEN_PREFIX${UUID.randomUUID()}"
    proposals.createSupersedingPrior(
      OperationProposal(
        token = token,
        operationId = operation.id,
        repoRoot = context.repoRoot.toString(),
        anchors = repositoryAnchors(context, proposed.operationValues),
        proposalValue = proposed.value,
        createdAt = clock.instant().toString(),
      ),
    )
    return OperationOutcome.AwaitingConfirmation(token, proposed.summary)
  }

  fun confirm(
    operation: ConfirmableOperation,
    context: OperationContext,
    token: String,
  ): OperationOutcome {
    val proposal = admissibleProposal(operation, context, token)
    val confirmed = ConfirmedOperationProposal(token, proposal.proposalValue, proposal.anchors.operationValues)
    operation.admit(context, confirmed)
    if (!proposals.markConsumed(token, clock.instant().toString())) throw ConsumedOperationTokenError(token)
    return operation.execute(context, confirmed)
  }

  private fun admissibleProposal(
    operation: ConfirmableOperation,
    context: OperationContext,
    token: String,
  ): OperationProposal {
    val proposal = proposals.find(token) ?: throw UnknownOperationTokenError(token)
    refusal(operation, context, proposal)?.let { refusal -> throw refusal }
    return proposal
  }

  private fun refusal(
    operation: ConfirmableOperation,
    context: OperationContext,
    proposal: OperationProposal,
  ): OperationRefusalError? {
    val token = proposal.token
    val repoRoot = context.repoRoot.toString()
    return when {
      proposal.consumedAt != null -> ConsumedOperationTokenError(token)
      proposal.supersededAt != null -> SupersededOperationTokenError(token)
      proposal.operationId != operation.id || proposal.repoRoot != repoRoot ->
        ForeignOperationTokenError(token, operation.id, repoRoot)
      else ->
        movedAnchors(proposal.anchors, repositoryAnchors(context, operation.currentAnchors(context)))
          .takeIf(List<String>::isNotEmpty)
          ?.let { moved -> MovedOperationAnchorsError(token, moved) }
    }
  }

  private fun repositoryAnchors(
    context: OperationContext,
    operationValues: Map<String, String>,
  ): OperationAnchors =
    OperationAnchors(
      headSha = gitOperations.runtimePhaseHeadCommit(context.repoRoot).requireGitValue(HEAD_SHA_ANCHOR),
      branch = gitOperations.currentBranch(context.repoRoot).requireGitValue(BRANCH_ANCHOR),
      operationValues = operationValues,
    )

  private fun movedAnchors(
    stored: OperationAnchors,
    current: OperationAnchors,
  ): List<String> =
    buildList {
      if (stored.headSha != current.headSha) add(HEAD_SHA_ANCHOR)
      if (stored.branch != current.branch) add(BRANCH_ANCHOR)
      current.operationValues.forEach { (anchor, value) -> if (stored.operationValues[anchor] != value) add(anchor) }
    }
}

internal fun WorkflowGitOperationResult.requireGitValue(what: String): String =
  (this as? WorkflowGitOperationResult.Ok)?.value?.trim() ?: throw OperationAnchorUnreadableError(what, error)

private const val TOKEN_PREFIX = "opt-"
private const val HEAD_SHA_ANCHOR = "HEAD"
private const val BRANCH_ANCHOR = "branch"
