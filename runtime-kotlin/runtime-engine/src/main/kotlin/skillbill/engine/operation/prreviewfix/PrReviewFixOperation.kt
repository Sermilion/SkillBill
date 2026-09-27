package skillbill.engine.operation.prreviewfix

import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.operation.core.ConfirmableOperation
import skillbill.engine.operation.core.ConfirmedOperationProposal
import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRunResult
import skillbill.engine.operation.core.OperationStepResult
import skillbill.engine.operation.core.requireGitValue
import skillbill.error.operation.InvalidOperationArgumentError
import skillbill.error.operation.InvalidOperationSelectionError
import skillbill.error.operation.MissingOperationSelectionError
import skillbill.error.operation.OperationAnchorUnreadableError
import skillbill.error.operation.OperationUsageError
import skillbill.error.operation.ProtectedBranchPushError
import skillbill.error.operation.PullRequestBranchNotCheckedOutError
import skillbill.error.operation.PullRequestNotFoundError
import skillbill.error.operation.PushWorktreeDirtyError
import skillbill.ports.review.pullrequest.PullRequestReviewThreadOperations
import skillbill.ports.review.pullrequest.model.ReviewPullRequest
import skillbill.ports.review.pullrequest.model.ReviewPullRequestResolution
import skillbill.ports.review.pullrequest.model.ReviewThread
import skillbill.ports.review.pullrequest.model.ReviewThreadListing
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.gitops.ProtectedBranches

class PrReviewFixOperation(
  private val reviewThreads: PullRequestReviewThreadOperations,
  private val gitOperations: WorkflowGitOperations,
  private val runPhase: (PhaseRunRequest) -> PhaseRunResult,
) : ConfirmableOperation {
  override val id: String = "pr-review-fix"

  override fun pre(context: OperationContext) {
    usageError(context.arguments, context.confirming)?.let { error -> throw error }
  }

  private fun usageError(
    arguments: OperationArguments,
    confirming: Boolean,
  ): OperationUsageError? =
    with(arguments) {
      when {
        scope != null && scope != ANALYZE_ONLY -> InvalidOperationArgumentError("scope", scope, "scope:$ANALYZE_ONLY")
        push != null && push !in PUSH_VALUES -> InvalidOperationArgumentError("push", push, "on|off")
        replies != null && replies !in REPLY_VALUES -> InvalidOperationArgumentError("replies", replies, "post|draft")
        confirming && select.isNullOrBlank() -> MissingOperationSelectionError(id, SELECTION_FORMS)
        !confirming && select != null ->
          InvalidOperationSelectionError(select, "select: goes with the confirm:<token> an analysis printed.")
        else -> null
      }
    }

  override fun run(context: OperationContext): OperationRunResult {
    val target = prReviewFixTarget(context.instructions)
    val pullRequest = pullRequest(context, target.reference)
    if (context.arguments.scope != ANALYZE_ONLY) requirePullRequestBranch(context, pullRequest)
    val listed = listThreads(context, pullRequest)
    val anchors = PrReviewFixAnchors.of(pullRequest, PrReviewFixAnchors.actionable(listed))
    if (anchors.ordinals.isEmpty()) {
      val handled = listed.joinToString("") { thread -> "- ${thread.id} — ${thread.location()}\n" }
      return OperationRunResult.Finished(
        OperationOutcome.Completed("${pullRequest.describe()} has no unresolved review threads.\n$handled"),
      )
    }
    val step =
      context.steps.runReadOnly(
        context.copy(instructions = target.instructions),
        ANALYSIS_STEP,
        analysisDirective(pullRequest, anchors, listed),
      )
    val matrix =
      when (step) {
        is OperationStepResult.Failed -> return OperationRunResult.Finished(OperationOutcome.Failed(step.reason))
        is OperationStepResult.Settled -> step.value.trim() + "\n"
      }
    val summary = "PR review fix analysis for ${pullRequest.describe()}\n\n$matrix"
    if (context.arguments.scope == ANALYZE_ONLY) {
      return OperationRunResult.Finished(OperationOutcome.Completed(summary))
    }
    return OperationRunResult.Proposed(
      value = matrix,
      summary = "$summary\nSelect with $SELECTION_FORMS.\n",
      operationValues = anchors.toOperationValues(),
    )
  }

  override fun currentAnchors(context: OperationContext): Map<String, String> {
    val pullRequest = pullRequest(context, prReviewFixTarget(context.instructions).reference)
    return PrReviewFixAnchors.measured(
      pullRequest,
      PrReviewFixAnchors.actionable(listThreads(context, pullRequest)).map(ReviewThread::id),
    )
  }

  override fun admit(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ) {
    val anchors = storedAnchors(proposal)
    parsePrReviewFixSelection(requireNotNull(context.arguments.select), anchors.ordinals)
    val branch = requirePullRequestBranch(context, anchors.pullRequest)
    if (context.arguments.push != PUSH_ON) return
    ProtectedBranches.protectedName(branch)?.let { protectedBranch -> throw ProtectedBranchPushError(protectedBranch) }
    if (gitOperations.worktreeStatus(context.repoRoot).requireGitValue("worktree status").isNotBlank()) {
      throw PushWorktreeDirtyError(context.repoRoot.toString())
    }
  }

  override fun execute(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ): OperationOutcome {
    val anchors = storedAnchors(proposal)
    return PrReviewFixExecution(
      context = context,
      pullRequest = anchors.pullRequest,
      selected = parsePrReviewFixSelection(requireNotNull(context.arguments.select), anchors.ordinals),
      threads = listThreads(context, anchors.pullRequest).associateBy(ReviewThread::id),
      matrix = proposal.value,
      reviewThreads = reviewThreads,
      gitOperations = gitOperations,
      runPhase = runPhase,
    ).run()
  }

  private fun requirePullRequestBranch(
    context: OperationContext,
    pullRequest: ReviewPullRequest,
  ): String {
    val branch = gitOperations.currentBranch(context.repoRoot).requireGitValue("branch")
    if (branch != pullRequest.headRefName) throw PullRequestBranchNotCheckedOutError(pullRequest.headRefName, branch)
    return branch
  }

  private fun pullRequest(
    context: OperationContext,
    reference: String?,
  ): ReviewPullRequest =
    when (val resolved = reviewThreads.resolvePullRequest(context.repoRoot, reference)) {
      is ReviewPullRequestResolution.Found -> resolved.pullRequest
      ReviewPullRequestResolution.Absent -> throw PullRequestNotFoundError(reference)
      is ReviewPullRequestResolution.Unavailable -> throw OperationAnchorUnreadableError(
        "pull request",
        resolved.reason,
      )
    }

  private fun listThreads(
    context: OperationContext,
    pullRequest: ReviewPullRequest,
  ): List<ReviewThread> =
    when (val listing = reviewThreads.reviewThreads(context.repoRoot, pullRequest)) {
      is ReviewThreadListing.Ok -> listing.threads
      is ReviewThreadListing.Unavailable -> throw OperationAnchorUnreadableError("review threads", listing.reason)
    }

  private fun storedAnchors(proposal: ConfirmedOperationProposal): PrReviewFixAnchors =
    PrReviewFixAnchors.from(proposal.operationValues)
      ?: throw OperationAnchorUnreadableError("pr-review-fix proposal", "proposal '${proposal.token}' carries no PR.")
}

internal const val ANALYSIS_STEP: String = "operation.pr-review-fix.analysis"
internal const val THREAD_STEP: String = "operation.pr-review-fix.thread"
internal const val PUSH_ON: String = "on"
internal const val REPLIES_DRAFT: String = "draft"
private const val ANALYZE_ONLY = "analyze-only"
private val PUSH_VALUES = setOf(PUSH_ON, "off")
private val REPLY_VALUES = setOf("post", REPLIES_DRAFT)
