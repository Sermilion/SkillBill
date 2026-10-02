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
  ): OperationOutcome {
    val token = "$TOKEN_PREFIX${UUID.randomUUID()}"
    val anchors = repositoryAnchors(context, proposed.operationValues) { return it }
    proposals.createSupersedingPrior(
      OperationProposal(
        token = token,
        operationId = operation.id,
        repoRoot = context.repoRoot.toString(),
        anchors = anchors,
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
    val proposal = proposals.find(token) ?: return unknownToken(token)
    val confirmed = ConfirmedOperationProposal(token, proposal.proposalValue, proposal.anchors.operationValues)
    return refusal(operation, context, proposal)
      ?: operation.admit(context, confirmed)
      ?: consumeAndExecute(operation, context, confirmed)
  }

  private fun consumeAndExecute(
    operation: ConfirmableOperation,
    context: OperationContext,
    confirmed: ConfirmedOperationProposal,
  ): OperationOutcome =
    if (proposals.markConsumed(confirmed.token, clock.instant().toString())) {
      operation.execute(context, confirmed)
    } else {
      consumedToken(confirmed.token)
    }

  private fun refusal(
    operation: ConfirmableOperation,
    context: OperationContext,
    proposal: OperationProposal,
  ): OperationRefusal? {
    val token = proposal.token
    val repoRoot = context.repoRoot.toString()
    return when {
      proposal.consumedAt != null -> consumedToken(token)
      proposal.supersededAt != null -> supersededToken(token)
      proposal.operationId != operation.id || proposal.repoRoot != repoRoot ->
        foreignToken(token, operation.id, repoRoot)
      else -> movedAnchorsRefusal(operation, context, proposal)
    }
  }

  private fun movedAnchorsRefusal(
    operation: ConfirmableOperation,
    context: OperationContext,
    proposal: OperationProposal,
  ): OperationRefusal? {
    val operationValues =
      when (val current = operation.currentAnchors(context)) {
        is CurrentOperationAnchors.Read -> current.values
        is CurrentOperationAnchors.Unreadable -> return current.refusal
      }
    val currentAnchors = repositoryAnchors(context, operationValues) { return it }
    return movedAnchors(proposal.anchors, currentAnchors)
      .takeIf(List<String>::isNotEmpty)
      ?.let { moved -> anchorsMoved(proposal.token, moved) }
  }

  private inline fun repositoryAnchors(
    context: OperationContext,
    operationValues: Map<String, String>,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): OperationAnchors =
    OperationAnchors(
      headSha = gitOperations.runtimePhaseHeadCommit(context.repoRoot).gitValueOr(HEAD_SHA_ANCHOR, refuse),
      branch = gitOperations.currentBranch(context.repoRoot).gitValueOr(BRANCH_ANCHOR, refuse),
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

internal inline fun WorkflowGitOperationResult.gitValueOr(
  what: String,
  refuse: (OperationOutcome.Blocked) -> Nothing,
): String = (this as? WorkflowGitOperationResult.Ok)?.value?.trim() ?: refuse(anchorUnreadable(what, error))

private const val TOKEN_PREFIX = "opt-"
private const val HEAD_SHA_ANCHOR = "HEAD"
private const val BRANCH_ANCHOR = "branch"
