package skillbill.engine.operation.prreviewfix

import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.application.review.diffcomment.model.DiffCommentListResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.operation.core.ConfirmAdmission
import skillbill.engine.operation.core.ConfirmedOperationProposal
import skillbill.engine.operation.core.CurrentOperationAnchors
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRefusal
import skillbill.engine.operation.core.OperationRunResult
import skillbill.engine.operation.core.OperationStepResult
import skillbill.engine.operation.core.anchorUnreadable
import skillbill.engine.operation.core.gitValueOr
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.review.diffcomment.model.DiffCommentThread
import skillbill.workflow.gitops.ProtectedBranches

internal class LocalReviewFixFlow(
  private val diffComments: DiffCommentService,
  private val gitOperations: WorkflowGitOperations,
  private val runPhase: (PhaseRunRequest) -> PhaseRunResult,
  private val modelAssignment: () -> FeatureTaskRuntimeModelAssignment,
  private val diagnostics: RuntimeDiagnostics,
) : ConfirmAdmission {
  fun run(context: OperationContext): OperationRunResult {
    val listed = list(context) { return OperationRunResult.Finished(it) }
    val headSha =
      gitOperations.headCommitSha(context.repoRoot).gitValueOr("HEAD") {
        return OperationRunResult.Finished(it)
      }
    val actionable = LocalReviewFixAnchors.actionable(listed.threads)
    if (actionable.isEmpty()) {
      val message = "No open local diff comments on '${listed.branch}'."
      return OperationRunResult.Finished(OperationOutcome.Completed(message))
    }
    val anchors = LocalReviewFixAnchors.of(listed.repositoryIdentity, listed.branch, headSha, actionable)
    val directive = localAnalysisDirective(listed.branch, headSha, anchors, listed.threads)
    return when (val step = context.steps.runReadOnly(context, ANALYSIS_STEP, directive)) {
      is OperationStepResult.Failed -> OperationRunResult.Finished(OperationOutcome.Failed(step.reason))
      is OperationStepResult.Refused -> OperationRunResult.Finished(step.refusal)
      is OperationStepResult.Settled -> analyzed(context, listed.branch, anchors, step.value.trim() + "\n")
    }
  }

  private fun analyzed(
    context: OperationContext,
    branch: String,
    anchors: LocalReviewFixAnchors,
    matrix: String,
  ): OperationRunResult {
    val summary = "Local review fix analysis for '$branch'\n\n$matrix"
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
            PrReviewFixMatrix.details(context.arguments, PrReviewFixMode.LOCAL.wireValue, null, extraction.threads),
        )
    }
  }

  fun currentAnchors(context: OperationContext): CurrentOperationAnchors {
    val listed = list(context) { return CurrentOperationAnchors.Unreadable(it) }
    val headSha =
      gitOperations.headCommitSha(context.repoRoot).gitValueOr("HEAD") {
        return CurrentOperationAnchors.Unreadable(it)
      }
    return CurrentOperationAnchors.Read(
      LocalReviewFixAnchors.measured(
        headSha,
        LocalReviewFixAnchors.actionable(listed.threads).map(DiffCommentThread::id),
      ),
    )
  }

  override fun admit(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ): OperationRefusal? {
    val anchors = storedAnchors(proposal) { return it }
    val select = requireNotNull(context.arguments.select)
    return when (val selection = parsePrReviewFixSelection(select, anchors.ordinals)) {
      is PrReviewFixSelection.Invalid -> selection.usage
      is PrReviewFixSelection.Selected ->
        PrReviewFixMatrix.offeredOptionRefusal(
          proposal.token,
          select,
          proposal.value,
          anchors.ordinals,
          selection.threads,
        ) ?: branchRefusal(context, anchors.headBranch)
    }
  }

  private fun branchRefusal(
    context: OperationContext,
    headBranch: String,
  ): OperationRefusal? {
    val branch = gitOperations.currentBranch(context.repoRoot).gitValueOr("branch") { return it }
    if (branch != headBranch) {
      return OperationOutcome.Blocked(
        "Local comments belong to '$headBranch'; check it out before confirming fixes.",
      )
    }
    ProtectedBranches.protectedName(branch)?.let { protected ->
      return OperationOutcome.Blocked("Refusing to commit local fixes on protected branch '$protected'.")
    }
    return worktreeRefusal(context)
  }

  private fun worktreeRefusal(context: OperationContext): OperationRefusal? {
    val status = gitOperations.worktreeStatus(context.repoRoot).gitValueOr("worktree status") { return it }
    return if (status.isNotBlank()) {
      OperationOutcome.Blocked(
        "mode:local commits every change in the worktree, and '${context.repoRoot}' already has uncommitted changes; " +
          "commit or stash them first.",
      )
    } else {
      null
    }
  }

  fun execute(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ): OperationOutcome {
    val anchors = storedAnchors(proposal) { return it }
    val selected =
      when (
        val selection = parsePrReviewFixSelection(requireNotNull(context.arguments.select), anchors.ordinals)
      ) {
        is PrReviewFixSelection.Selected -> selection.threads
        is PrReviewFixSelection.Invalid -> return selection.usage
      }
    val listed =
      when (val result = diffComments.list(context.repoRoot, anchors.headBranch)) {
        is DiffCommentListResult.Listed -> result
        is DiffCommentListResult.Unavailable -> return anchorUnreadable("local diff comments", result.reason)
      }
    val actionable = LocalReviewFixAnchors.actionable(listed.threads).associateBy(DiffCommentThread::id)
    val closed = selected.firstOrNull { it.threadId !in actionable }
    if (closed != null) {
      diagnostics.warning(
        "seam=local_review_fix_selection value_expected=open_thread value_used=blocked cause=thread_no_longer_open",
      )
    }
    return if (closed != null) {
      OperationOutcome.Blocked("Thread ${closed.ordinal} (${closed.threadId}) is no longer open.")
    } else {
      LocalReviewFixExecution(
        context = context,
        branch = anchors.headBranch,
        selected = selected,
        threads = actionable,
        matrix = proposal.value,
        diffComments = diffComments,
        gitOperations = gitOperations,
        runPhase = runPhase,
        modelAssignment = modelAssignment(),
        diagnostics = diagnostics,
      ).run()
    }
  }

  private inline fun list(
    context: OperationContext,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ) = when (val result = diffComments.list(context.repoRoot)) {
    is DiffCommentListResult.Listed -> result
    is DiffCommentListResult.Unavailable -> refuse(anchorUnreadable("local diff comments", result.reason))
  }

  private inline fun storedAnchors(
    proposal: ConfirmedOperationProposal,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): LocalReviewFixAnchors =
    LocalReviewFixAnchors.from(proposal.operationValues)
      ?: refuse(
        anchorUnreadable("pr-review-fix proposal", "proposal '${proposal.token}' carries no local threads."),
      )
}

private const val ANALYZE_ONLY = "analyze-only"
