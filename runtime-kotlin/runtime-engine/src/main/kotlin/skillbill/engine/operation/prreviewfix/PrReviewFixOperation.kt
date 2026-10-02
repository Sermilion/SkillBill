package skillbill.engine.operation.prreviewfix

import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.operation.core.ConfirmableOperation
import skillbill.engine.operation.core.ConfirmedOperationProposal
import skillbill.engine.operation.core.CurrentOperationAnchors
import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRefusal
import skillbill.engine.operation.core.OperationRunResult
import skillbill.engine.operation.core.OperationStepResult
import skillbill.engine.operation.core.anchorUnreadable
import skillbill.engine.operation.core.gitValueOr
import skillbill.engine.operation.core.invalidArgument
import skillbill.engine.operation.core.invalidSelection
import skillbill.engine.operation.core.pullRequestNotFound
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

  override fun pre(context: OperationContext): OperationRefusal? = usageError(context.arguments, context.confirming)

  private fun usageError(
    arguments: OperationArguments,
    confirming: Boolean,
  ): OperationOutcome.Usage? =
    with(arguments) {
      when {
        scope != null && scope != ANALYZE_ONLY -> invalidArgument("scope", scope, "scope:$ANALYZE_ONLY")
        push != null && push !in PUSH_VALUES -> invalidArgument("push", push, "on|off")
        replies != null && replies !in REPLY_VALUES -> invalidArgument("replies", replies, "post|draft")
        confirming && select.isNullOrBlank() ->
          OperationOutcome.Usage("Operation '$id' needs a selection with confirm:; pass $SELECTION_FORMS.")
        !confirming && select != null ->
          invalidSelection(select, "select: goes with the confirm:<token> an analysis printed.")
        else -> null
      }
    }

  override fun run(context: OperationContext): OperationRunResult {
    val target = prReviewFixTarget(context.instructions)
    val pullRequest = pullRequest(context, target.reference) { return OperationRunResult.Finished(it) }
    if (context.arguments.scope != ANALYZE_ONLY) {
      requirePullRequestBranch(context, pullRequest) { return OperationRunResult.Finished(it) }
    }
    return analyzeThreads(context.copy(instructions = target.instructions), pullRequest)
  }

  private fun analyzeThreads(
    context: OperationContext,
    pullRequest: ReviewPullRequest,
  ): OperationRunResult {
    val listed = listThreads(context, pullRequest) { return OperationRunResult.Finished(it) }
    val anchors = PrReviewFixAnchors.of(pullRequest, PrReviewFixAnchors.actionable(listed))
    if (anchors.ordinals.isEmpty()) {
      val handled = listed.joinToString("") { thread -> "- ${thread.id} — ${thread.location()}\n" }
      return OperationRunResult.Finished(
        OperationOutcome.Completed("${pullRequest.describe()} has no unresolved review threads.\n$handled"),
      )
    }
    return analyze(context, pullRequest, anchors, listed)
  }

  private fun analyze(
    context: OperationContext,
    pullRequest: ReviewPullRequest,
    anchors: PrReviewFixAnchors,
    listed: List<ReviewThread>,
  ): OperationRunResult =
    when (
      val step = context.steps.runReadOnly(context, ANALYSIS_STEP, analysisDirective(pullRequest, anchors, listed))
    ) {
      is OperationStepResult.Failed -> OperationRunResult.Finished(OperationOutcome.Failed(step.reason))
      is OperationStepResult.Refused -> OperationRunResult.Finished(step.refusal)
      is OperationStepResult.Settled -> analyzed(context, pullRequest, anchors, step.value.trim() + "\n")
    }

  private fun analyzed(
    context: OperationContext,
    pullRequest: ReviewPullRequest,
    anchors: PrReviewFixAnchors,
    matrix: String,
  ): OperationRunResult {
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

  override fun currentAnchors(context: OperationContext): CurrentOperationAnchors {
    val reference = prReviewFixTarget(context.instructions).reference
    val pullRequest = pullRequest(context, reference) { return CurrentOperationAnchors.Unreadable(it) }
    val listed = listThreads(context, pullRequest) { return CurrentOperationAnchors.Unreadable(it) }
    return CurrentOperationAnchors.Read(
      PrReviewFixAnchors.measured(pullRequest, PrReviewFixAnchors.actionable(listed).map(ReviewThread::id)),
    )
  }

  override fun admit(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ): OperationRefusal? {
    val anchors = storedAnchors(proposal) { return it }
    val selection = parsePrReviewFixSelection(requireNotNull(context.arguments.select), anchors.ordinals)
    if (selection is PrReviewFixSelection.Invalid) return selection.usage
    val branch = requirePullRequestBranch(context, anchors.pullRequest) { return it }
    return if (context.arguments.push == PUSH_ON) pushRefusal(context, branch) else null
  }

  private fun pushRefusal(
    context: OperationContext,
    branch: String,
  ): OperationRefusal? {
    ProtectedBranches.protectedName(branch)?.let { protectedBranch ->
      return OperationOutcome.Blocked("Refusing to push protected branch '$protectedBranch'; re-run with push:off.")
    }
    val status = gitOperations.worktreeStatus(context.repoRoot).gitValueOr("worktree status") { return it }
    if (status.isBlank()) return null
    return OperationOutcome.Blocked(
      "push:on commits every change in the worktree, and '${context.repoRoot}' already has uncommitted changes; " +
        "commit or stash them, or re-run with push:off.",
    )
  }

  override fun execute(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ): OperationOutcome {
    val anchors = storedAnchors(proposal) { return it }
    val selected =
      when (val selection = parsePrReviewFixSelection(requireNotNull(context.arguments.select), anchors.ordinals)) {
        is PrReviewFixSelection.Selected -> selection.threads
        is PrReviewFixSelection.Invalid -> return selection.usage
      }
    return PrReviewFixExecution(
      context = context,
      pullRequest = anchors.pullRequest,
      selected = selected,
      threads = listThreads(context, anchors.pullRequest) { return it }.associateBy(ReviewThread::id),
      matrix = proposal.value,
      reviewThreads = reviewThreads,
      gitOperations = gitOperations,
      runPhase = runPhase,
    ).run()
  }

  private inline fun requirePullRequestBranch(
    context: OperationContext,
    pullRequest: ReviewPullRequest,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): String {
    val branch = gitOperations.currentBranch(context.repoRoot).gitValueOr("branch", refuse)
    if (branch != pullRequest.headRefName) {
      refuse(
        OperationOutcome.Blocked(
          "The pull request's branch '${pullRequest.headRefName}' is not checked out (current: '$branch'); check " +
            "it out before confirming fixes.",
        ),
      )
    }
    return branch
  }

  private inline fun pullRequest(
    context: OperationContext,
    reference: String?,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): ReviewPullRequest =
    when (val resolved = reviewThreads.resolvePullRequest(context.repoRoot, reference)) {
      is ReviewPullRequestResolution.Found -> resolved.pullRequest
      ReviewPullRequestResolution.Absent -> refuse(pullRequestNotFound(reference))
      is ReviewPullRequestResolution.Unavailable -> refuse(anchorUnreadable("pull request", resolved.reason))
    }

  private inline fun listThreads(
    context: OperationContext,
    pullRequest: ReviewPullRequest,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): List<ReviewThread> =
    when (val listing = reviewThreads.reviewThreads(context.repoRoot, pullRequest)) {
      is ReviewThreadListing.Ok -> listing.threads
      is ReviewThreadListing.Unavailable -> refuse(anchorUnreadable("review threads", listing.reason))
    }

  private inline fun storedAnchors(
    proposal: ConfirmedOperationProposal,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): PrReviewFixAnchors =
    PrReviewFixAnchors.from(proposal.operationValues)
      ?: refuse(anchorUnreadable("pr-review-fix proposal", "proposal '${proposal.token}' carries no PR."))
}

internal const val ANALYSIS_STEP: String = "operation.pr-review-fix.analysis"
internal const val THREAD_STEP: String = "operation.pr-review-fix.thread"
internal const val PUSH_ON: String = "on"
internal const val REPLIES_DRAFT: String = "draft"
private const val ANALYZE_ONLY = "analyze-only"
private val PUSH_VALUES = setOf(PUSH_ON, "off")
private val REPLY_VALUES = setOf("post", REPLIES_DRAFT)
