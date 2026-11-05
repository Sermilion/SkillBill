package skillbill.engine.operation.prreviewfix

import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.operation.core.ConfirmAdmission
import skillbill.engine.operation.core.ConfirmableOperation
import skillbill.engine.operation.core.ConfirmedOperationProposal
import skillbill.engine.operation.core.CurrentOperationAnchors
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationPrecheck
import skillbill.engine.operation.core.OperationRefusal
import skillbill.engine.operation.core.OperationRunResult
import skillbill.engine.operation.core.OperationStepResult
import skillbill.engine.operation.core.anchorUnreadable
import skillbill.engine.operation.core.gitValueOr
import skillbill.engine.operation.core.invalidArgument
import skillbill.engine.operation.core.invalidSelection
import skillbill.engine.operation.core.pullRequestNotFound
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.pullrequest.PullRequestReviewThreadOperations
import skillbill.ports.review.pullrequest.model.ReviewPullRequest
import skillbill.ports.review.pullrequest.model.ReviewPullRequestResolution
import skillbill.ports.review.pullrequest.model.ReviewThread
import skillbill.ports.review.pullrequest.model.ReviewThreadListing
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.gitops.ProtectedBranches

public class PrReviewFixOperation(
  private val reviewThreads: PullRequestReviewThreadOperations,
  private val diffComments: DiffCommentService,
  private val gitOperations: WorkflowGitOperations,
  diagnostics: RuntimeDiagnostics,
  private val runPhase: (PhaseRunRequest) -> PhaseRunResult,
  private val modelAssignmentSource: () -> FeatureTaskRuntimeModelAssignment,
) : ConfirmableOperation,
  OperationPrecheck,
  ConfirmAdmission {
  public constructor(
    reviewThreads: PullRequestReviewThreadOperations,
    diffComments: DiffCommentService,
    gitOperations: WorkflowGitOperations,
    diagnostics: RuntimeDiagnostics,
    runPhase: (PhaseRunRequest) -> PhaseRunResult,
    modelAssignment: FeatureTaskRuntimeModelAssignment = FeatureTaskRuntimeModelAssignment(),
  ) : this(reviewThreads, diffComments, gitOperations, diagnostics, runPhase, { modelAssignment })

  private val modelAssignment: FeatureTaskRuntimeModelAssignment by lazy(modelAssignmentSource)
  private val localFlow = LocalReviewFixFlow(diffComments, gitOperations, runPhase, { modelAssignment }, diagnostics)
  override val id: String = "pr-review-fix"

  override fun pre(context: OperationContext): OperationRefusal? = usageError(context)

  private fun usageError(context: OperationContext): OperationOutcome.Usage? =
    modeUsageError(context) ?: argumentUsageError(context)

  private fun modeUsageError(context: OperationContext): OperationOutcome.Usage? {
    val parsedMode = PrReviewFixMode.from(context.arguments.mode)
    val reference = prReviewFixTarget(context.instructions).reference
    val displayedReference = context.instructions.orEmpty().trim().substringBefore(' ')
    return with(context.arguments) {
      when {
        mode != null && parsedMode == null -> invalidArgument("mode", mode, "local|remote")
        parsedMode == PrReviewFixMode.LOCAL && (push != null || replies != null) ->
          OperationOutcome.Usage("push: and replies: apply only to mode:remote")
        parsedMode == PrReviewFixMode.LOCAL && reference != null ->
          OperationOutcome.Usage(
            "mode:local fixes local diff comments; pass mode:remote to fix PR $displayedReference",
          )
        else -> null
      }
    }
  }

  private fun argumentUsageError(context: OperationContext): OperationOutcome.Usage? =
    with(context.arguments) {
      when {
        scope != null && scope != ANALYZE_ONLY -> invalidArgument("scope", scope, "scope:$ANALYZE_ONLY")
        push != null && push !in PUSH_VALUES -> invalidArgument("push", push, "on|off")
        replies != null && replies !in REPLY_VALUES -> invalidArgument("replies", replies, "post|draft")
        context.confirming && select.isNullOrBlank() ->
          OperationOutcome.Usage("Operation '$id' needs a selection with confirm:; pass $SELECTION_FORMS.")
        !context.confirming && select != null ->
          invalidSelection(select, "select: goes with the confirm:<token> an analysis printed.")
        else -> null
      }
    }

  override fun run(context: OperationContext): OperationRunResult {
    if (mode(context) == PrReviewFixMode.LOCAL) return localFlow.run(context)
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
    return when (val extraction = PrReviewFixMatrix.extract(matrix, anchors.ordinals)) {
      is PrReviewFixMatrixExtraction.Malformed ->
        OperationRunResult.Finished(PrReviewFixMatrix.malformed(summary, extraction.problems))
      is PrReviewFixMatrixExtraction.Extracted ->
        OperationRunResult.Proposed(
          value = matrix,
          summary = "$summary\nSelect with $SELECTION_FORMS.\n",
          operationValues = anchors.toOperationValues(),
          details =
            PrReviewFixMatrix.details(
              context.arguments,
              PrReviewFixMode.REMOTE.wireValue,
              pullRequest.url,
              extraction.threads,
            ),
        )
    }
  }

  override fun currentAnchors(context: OperationContext): CurrentOperationAnchors {
    if (mode(context) == PrReviewFixMode.LOCAL) return localFlow.currentAnchors(context)
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
    return if (mode(context) == PrReviewFixMode.LOCAL) {
      localFlow.admit(context, proposal)
    } else {
      admitRemote(context, proposal)
    }
  }

  private fun admitRemote(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ): OperationRefusal? {
    val anchors = storedAnchors(proposal) { return it }
    selectionRefusal(proposal, requireNotNull(context.arguments.select), anchors.ordinals)?.let { return it }
    val branch = requirePullRequestBranch(context, anchors.pullRequest) { return it }
    return if (context.arguments.push == PUSH_ON) pushRefusal(context, branch) else null
  }

  private fun selectionRefusal(
    proposal: ConfirmedOperationProposal,
    select: String,
    ordinals: Map<String, String>,
  ): OperationRefusal? =
    when (val selection = parsePrReviewFixSelection(select, ordinals)) {
      is PrReviewFixSelection.Invalid -> selection.usage
      is PrReviewFixSelection.Selected ->
        PrReviewFixMatrix.offeredOptionRefusal(proposal.token, select, proposal.value, ordinals, selection.threads)
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
    return if (mode(context) == PrReviewFixMode.LOCAL) {
      localFlow.execute(context, proposal)
    } else {
      executeRemote(context, proposal)
    }
  }

  private fun executeRemote(
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
      modelAssignment = modelAssignment,
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

  private fun mode(context: OperationContext): PrReviewFixMode =
    requireNotNull(PrReviewFixMode.from(context.arguments.mode))
}

internal const val ANALYSIS_STEP: String = "operation.pr-review-fix.analysis"
internal const val THREAD_STEP: String = "operation.pr-review-fix.thread"
internal const val PUSH_ON: String = "on"
internal const val REPLIES_DRAFT: String = "draft"
private const val ANALYZE_ONLY = "analyze-only"
private val PUSH_VALUES = setOf(PUSH_ON, "off")
private val REPLY_VALUES = setOf("post", REPLIES_DRAFT)
