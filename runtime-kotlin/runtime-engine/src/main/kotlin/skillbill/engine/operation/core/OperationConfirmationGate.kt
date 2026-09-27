package skillbill.engine.operation.core

import me.tatarka.inject.annotations.Inject
import skillbill.error.operation.ConsumedOperationTokenError
import skillbill.error.operation.ForeignOperationTokenError
import skillbill.error.operation.MovedOperationAnchorsError
import skillbill.error.operation.OperationAnchorUnreadableError
import skillbill.error.operation.OperationRefusalError
import skillbill.error.operation.SupersededOperationTokenError
import skillbill.error.operation.UnknownOperationTokenError
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.operation.model.OperationAnchors
import skillbill.ports.operation.model.OperationProposal
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.time.Clock
import java.util.UUID

/**
 * Two-invocation confirmation: [propose] stores the proposal and returns its token; [confirm] executes exactly the
 * stored proposal once, refusing unknown, consumed, superseded, foreign, or stale tokens before anything changes.
 */
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
    val proposal = proposals.find(token) ?: throw UnknownOperationTokenError(token)
    // Consume before executing: a crash after the consume refuses a retry instead of executing twice.
    val refusal =
      refusal(operation, context, proposal)
        ?: ConsumedOperationTokenError(token).takeUnless { proposals.markConsumed(token, clock.instant().toString()) }
    if (refusal != null) throw refusal
    return operation.execute(
      context,
      ConfirmedOperationProposal(token, proposal.proposalValue, proposal.anchors.operationValues),
    )
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

  /** Compares HEAD, the branch, and every anchor the operation reports now; pinned values it does not report stay. */
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

/** The trimmed value of a successful git read; a failed read refuses the operation, naming [what]. */
internal fun WorkflowGitOperationResult.requireGitValue(what: String): String =
  (this as? WorkflowGitOperationResult.Ok)?.value?.trim() ?: throw OperationAnchorUnreadableError(what, error)

private const val TOKEN_PREFIX = "opt-"
private const val HEAD_SHA_ANCHOR = "HEAD"
private const val BRANCH_ANCHOR = "branch"
