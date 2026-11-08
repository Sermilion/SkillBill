package skillbill.engine.operation.core

import me.tatarka.inject.annotations.Inject
import skillbill.ports.operation.OperationCardRepository
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.operation.model.OperationAnchors
import skillbill.ports.operation.model.OperationProposal
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.time.Clock
import java.util.UUID

@Inject
public class OperationConfirmationGate(
  private val proposals: OperationProposalRepository,
  private val cards: OperationCardRepository,
  private val gitOperations: WorkflowGitOperations,
  private val clock: Clock,
) {
  internal fun propose(
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
    return OperationOutcome.AwaitingConfirmation(
      token,
      proposed.summary,
      proposed.details?.let { details -> OperationCardFacts(details, sourceAnchors(anchors)) },
    )
  }

  private fun sourceAnchors(anchors: OperationAnchors): Map<String, String> =
    mapOf(HEAD_SHA_ANCHOR to anchors.headSha, BRANCH_ANCHOR to anchors.branch) + anchors.operationValues

  internal fun bind(
    operationId: String,
    context: OperationContext,
    token: String,
  ): OperationProposal? {
    val anchors = repositoryAnchors(context, emptyMap()) { return null }
    return OperationProposal(
      token = token,
      operationId = operationId,
      repoRoot = context.repoRoot.toString(),
      anchors = anchors,
      proposalValue = token,
      createdAt = clock.instant().toString(),
    )
  }

  internal fun confirmBound(
    operation: SelfConfirmingOperation,
    context: OperationContext,
    token: String,
    execute: () -> OperationOutcome,
  ): OperationOutcome {
    val proposal =
      proposals.find(token) ?: return if (context.desktopLaunchId == null) execute() else unknownToken(token)
    val refused = boundRefusal(operation.id, context, proposal)
    return when {
      refused != null -> refused
      consume(token, context) -> execute()
      else -> consumedToken(token)
    }
  }

  internal fun revalidateAuthority(
    token: String,
    operationId: String,
    repoRoot: String,
    head: String,
    branch: String,
  ): OperationRefusal? {
    val proposal = proposals.find(token) ?: return unknownToken(token)
    return when {
      proposal.consumedAt != null -> consumedToken(token)
      proposal.supersededAt != null -> supersededToken(token)
      proposal.operationId != operationId || proposal.repoRoot != repoRoot -> foreignToken(token, operationId, repoRoot)
      proposal.anchors.headSha != head -> anchorsMoved(token, listOf(HEAD_SHA_ANCHOR))
      proposal.anchors.branch != branch -> anchorsMoved(token, listOf(BRANCH_ANCHOR))
      else -> null
    }
  }

  private fun boundRefusal(
    operationId: String,
    context: OperationContext,
    proposal: OperationProposal,
  ): OperationRefusal? {
    val token = proposal.token
    val repoRoot = context.repoRoot.toString()
    return when {
      proposal.consumedAt != null -> consumedToken(token)
      proposal.supersededAt != null -> supersededToken(token)
      proposal.operationId != operationId || proposal.repoRoot != repoRoot ->
        foreignToken(token, operationId, repoRoot)
      else ->
        movedAnchors(proposal.anchors, repositoryAnchors(context, emptyMap()) { return it })
          .takeIf(List<String>::isNotEmpty)
          ?.let { moved -> anchorsMoved(token, moved) }
    }
  }

  internal fun confirm(
    operation: ConfirmableOperation,
    context: OperationContext,
    token: String,
  ): OperationOutcome {
    val proposal = proposals.find(token) ?: return unknownToken(token)
    val confirmed = ConfirmedOperationProposal(token, proposal.proposalValue, proposal.anchors.operationValues)
    return refusal(operation, context, proposal)
      ?: (operation as? ConfirmAdmission)?.admit(context, confirmed)
      ?: consumeAndExecute(operation, context, confirmed)
  }

  private fun consumeAndExecute(
    operation: ConfirmableOperation,
    context: OperationContext,
    confirmed: ConfirmedOperationProposal,
  ): OperationOutcome =
    if (consume(confirmed.token, context)) {
      operation.execute(context, confirmed)
    } else {
      consumedToken(confirmed.token)
    }

  private fun consume(
    token: String,
    context: OperationContext,
  ): Boolean =
    context.desktopLaunchId?.let { launchId ->
      cards.consumeClaimedConfirmation(token, launchId, context.invocationId, clock.instant().toString())
    } ?: proposals.markConsumed(token, clock.instant().toString())

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
internal const val HEAD_SHA_ANCHOR = "HEAD"
internal const val BRANCH_ANCHOR = "branch"
