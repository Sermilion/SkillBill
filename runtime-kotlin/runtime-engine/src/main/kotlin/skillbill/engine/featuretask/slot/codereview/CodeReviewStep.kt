package skillbill.engine.featuretask.slot.codereview

import skillbill.application.review.model.ParallelCodeReviewPlanningFailure
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelCodeReviewRunOutcome
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseLaunchReviewTier
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.state.PhaseReviewExecutionContext
import skillbill.engine.featuretask.slot.state.PhaseReviewPassState
import skillbill.engine.featuretask.slot.state.PhaseReviewStepBinding
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.failureCodeLabel
import skillbill.error.featuretask.RuntimeOwnedPersistenceFailureCode
import skillbill.error.featuretask.UnknownPhaseReviewTargetError
import skillbill.error.shellcontent.InvalidReviewContextSchemaError
import skillbill.error.shellcontent.UnreadableSpecIntentProjectionError
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.goalrunner.subtaskreview.model.UnaddressedFindingLedgerScope
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.coroutines.cancellation.CancellationException

internal class CodeReviewStep(
  private val runner: PhaseRunner,
  private val reviewPass: CodeReviewPass,
) : PhaseStepHooks {
  fun run(
    requestedRun: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
    prompt: PhaseStepPromptSource,
  ): PhaseOutcome {
    val iteration = state.nextStepIteration()
    return state.startReviewStep(requestedRun, iteration)
      ?: runAfterStart(requestedRun, context, state, prompt)
  }

  private fun runAfterStart(
    requestedRun: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
    prompt: PhaseStepPromptSource,
  ): PhaseOutcome {
    val run =
      when (val phaseRun = phaseReviewRun(requestedRun, context, state)) {
        is PhaseReviewRun.Resolved -> phaseRun.run
        is PhaseReviewRun.Unresolved -> {
          state.blockReviewPreparation(1, phaseRun.reason, FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION)
          return PhaseOutcome.blocked(phaseRun.reason)
        }
      }
    val input =
      when (val prepared = InlineReviewPreparation.prepare(run, context, state)) {
        is InlineReviewPrepared.Ready -> prepared.input
        is InlineReviewPrepared.Settled -> return prepared.outcome
      }
    return startReviewPass(run, context, state, prompt, input)
  }

  private fun startReviewPass(
    run: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
    prompt: PhaseStepPromptSource,
    input: GoalSubtaskReviewInput,
  ): PhaseOutcome {
    val iteration = state.nextStepIteration()
    val passNumber = state.reviewPassNumber
    val resolution =
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(run.request.runInvariants.codeReviewMode, passNumber)
    val reviewRunId =
      state.recordedReviewRunId(passNumber)
        ?: run.request.reviewInvocation
          ?.reviewRunId
          ?.takeIf { passNumber == 1 }
        ?: InlineReviewEnvelope.mintReviewRunId(context.clock)
    (state.startReview(iteration, reviewRunId) as? RequiredPhaseWrite.Rejected)?.let {
      return state.blockRequiredReviewWrite(it)
    }
    val fingerprint =
      repositoryFingerprint(run, context)
        ?: return PhaseOutcome.blocked("Runtime-owned review could not resolve a repository checkpoint fingerprint.")
    (state.prepareReviewBriefing(iteration, prompt, input) as? RequiredPhaseWrite.Rejected)?.let {
      return state.blockRequiredReviewWrite(it)
    }
    state.reviewLaunched(iteration)
    val pass =
      ReviewPassRun(
        iteration,
        reviewRunId,
        InlineReviewCycle(passNumber, reviewPass.executedTier(resolution.resolvedTier), fingerprint),
      )
    return launchAndSettle(run, context, state, input, pass)
  }

  override fun expectedLaunchCheckpoint(
    run: PhaseRun,
    current: String?,
  ): String? =
    if (run.reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
      current
    } else {
      run.reentry?.expectedRepositoryCheckpoint ?: current
    }

  override fun launchReviewTier(
    run: PhaseRun,
    state: PhaseReviewPassState,
  ): PhaseLaunchReviewTier {
    val reviewState = state as PhaseReviewStepBinding
    val passNumber = reviewState.reviewPassNumber
    val resolution =
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(run.request.runInvariants.codeReviewMode, passNumber)
    reviewState.persistResolvedReviewTier(resolution)
    return PhaseLaunchReviewTier(passNumber, resolution, reviewPass.executedTier(resolution.resolvedTier))
  }

  private fun launchAndSettle(
    run: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
    input: GoalSubtaskReviewInput,
    pass: ReviewPassRun,
  ): PhaseOutcome {
    val gitOperations = context.gitOperations
    val repoRoot = run.request.repoRoot
    val before = gitOperations.worktreeStatus(repoRoot)
    if (before !is WorkflowGitOperationResult.Ok) {
      return blockStep(state, pass.iteration, worktreeFailure("before", before.error))
    }
    val result =
      when (val launched = launch(run, state, input, pass.reviewRunId)) {
        is ReviewPassLaunch.Failed -> return blockStep(state, pass.iteration, launched.reason, launched.disposition)
        is ReviewPassLaunch.Reviewed -> launched.result
      }
    val after = gitOperations.worktreeStatus(repoRoot)
    if (after !is WorkflowGitOperationResult.Ok) {
      return blockStep(state, pass.iteration, worktreeFailure("after", after.error))
    }
    val manifest =
      PhaseStepFileManifest(
        before = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(before.value.orEmpty()),
        after = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(after.value.orEmpty()),
      )
    return recordAndSettle(run, context, state, ReviewedPass(pass, result, manifest))
  }

  private fun recordAndSettle(
    run: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
    reviewed: ReviewedPass,
  ): PhaseOutcome {
    val pass = reviewed.pass
    state.recordReviewContentIdentities()
    state.recordReviewRun(pass.reviewRunId, reviewed.result, reviewPass.recordsLaneTelemetry)
    failedLaneReason(reviewed.result)?.let { reason ->
      return blockStep(state, pass.iteration, reason, FeatureTaskRuntimeFailureDisposition.RETRYABLE)
    }
    val dispositions = blockerDispositions(run, state, reviewed.result, pass)
    val settled = pass.copy(cycle = pass.cycle.copy(blockerDispositions = dispositions))
    return settle(run, context, state, reviewed.copy(pass = settled))
  }

  private fun launch(
    run: PhaseRun,
    state: PhaseReviewStepBinding,
    input: GoalSubtaskReviewInput,
    reviewRunId: String,
  ): ReviewPassLaunch {
    val outcome = runCatching { reviewPass.review(run, input, reviewRunId, runner, state) }
    outcome.exceptionOrNull()?.let { error -> return launchFailure(error) ?: throw error }
    return when (val review = outcome.getOrThrow()) {
      is ParallelCodeReviewRunOutcome.Reviewed -> ReviewPassLaunch.Reviewed(review.result)
      is ParallelCodeReviewRunOutcome.PlanningFailed -> planningFailureLaunch(review.failure)
    }
  }

  private fun settle(
    run: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
    reviewed: ReviewedPass,
  ): PhaseOutcome {
    val pass = reviewed.pass
    val manifest = reviewed.manifest
    val initialText = InlineReviewEnvelope.assemble(reviewed.result, pass.reviewRunId, pass.cycle)
    val accepted = measuredReviewOutput(initialText)
    if (manifest.before == manifest.after) {
      return complete(run, state, reviewed, initialText, accepted)
    }
    if (!reviewPass.policy.fileMutating) {
      return blockStep(
        state,
        pass.iteration,
        "Feature-task-runtime phase 'review' is read-only but the worktree changed during the review: " +
          ((manifest.after union manifest.before) - (manifest.after intersect manifest.before)).joinToString(", "),
        fileManifest = manifest,
      )
    }
    return settleAmended(run, context, state, reviewed)
  }

  private fun settleAmended(
    run: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
    reviewed: ReviewedPass,
  ): PhaseOutcome {
    val pass = reviewed.pass
    if (!state.amendReviewRemediationCheckpoint()) {
      return blockStep(
        state,
        pass.iteration,
        "Runtime-owned review changes could not be committed before review settlement.",
        FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
    }
    val refreshed =
      repositoryFingerprint(run, context)
        ?: return blockStep(
          state,
          pass.iteration,
          "Runtime-owned review could not resolve the post-amend repository checkpoint fingerprint.",
          FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        )
    val outputText =
      InlineReviewEnvelope.assemble(
        reviewed.result,
        pass.reviewRunId,
        pass.cycle.copy(repositoryFingerprint = refreshed),
      )
    return complete(run, state, reviewed, outputText, measuredReviewOutput(outputText))
  }

  private fun complete(
    run: PhaseRun,
    state: PhaseReviewStepBinding,
    reviewed: ReviewedPass,
    outputText: String,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): PhaseOutcome {
    val iteration = reviewed.pass.iteration
    state.retainReviewOutput(iteration, outputText)
    state.completeReview(iteration, outputText, output, reviewed.manifest)?.let { reason ->
      return PhaseOutcome.blocked(reason)
    }
    state.stepCompleted(iteration)
    return PhaseOutcome.completed(completedOutput(run, iteration, output))
  }

  private fun blockerDispositions(
    run: PhaseRun,
    state: PhaseReviewStepBinding,
    result: ParallelCodeReviewResult,
    pass: ReviewPassRun,
  ): List<GoalSubtaskBlockerDisposition> {
    val passNumber = pass.cycle.passNumber
    if (passNumber < 2) return emptyList()
    val prior = state.unaddressedReviewFindings()
    if (prior.isEmpty()) return emptyList()
    val continuation = run.request.goalContinuation
    val envelope =
      InlineReviewEnvelope.envelopeMap(
        InlineReviewEnvelope.assemble(
          result = result,
          reviewRunId = pass.reviewRunId,
          cycle = InlineReviewCycle(passNumber, pass.cycle.resolvedTier, repositoryFingerprint = "disposition-preview"),
        ),
      )
    val verdicts = state.recordedFindingVerdicts(envelope)
    val current =
      GoalSubtaskReviewSummaryReducer.unaddressedFindings(
        output = envelope,
        scope =
          UnaddressedFindingLedgerScope(
            issueKey = continuation?.parentIssueKey ?: run.request.issueKey,
            subtaskId = continuation?.subtaskId ?: 0,
            workflowId = run.request.workflowId,
            reviewPassNumber = passNumber,
          ),
        recordedVerdicts = verdicts,
      )
    return GoalSubtaskReviewSummaryReducer.refutedBlockerSupersedes(prior, current, verdicts)
  }

  private fun phaseReviewRun(
    run: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
  ): PhaseReviewRun {
    val invocation = run.request.reviewInvocation ?: return PhaseReviewRun.Resolved(run)
    val gitOperations = context.gitOperations
    val repoRoot = run.request.repoRoot
    val status = gitOperations.worktreeStatus(repoRoot)
    if (status !is WorkflowGitOperationResult.Ok) {
      return PhaseReviewRun.Unresolved(
        "Feature-task-runtime phase 'review' could not read the worktree status to resolve its target: " +
          status.error,
      )
    }
    val target =
      state.pinnedReviewTarget {
        ReviewTargetResolver.resolve(invocation.target, status.value.orEmpty()).also { target ->
          if (target is ReviewTarget.Commit) {
            val resolved = gitOperations.resolveCommit(repoRoot, target.sha)
            if (resolved !is WorkflowGitOperationResult.Ok) throw UnknownPhaseReviewTargetError(target.sha)
          }
        }
      }
    return PhaseReviewRun.Resolved(run.copy(reviewTarget = target))
  }

  private fun repositoryFingerprint(
    run: PhaseRun,
    context: PhaseReviewExecutionContext,
  ): String? =
    context.gitOperations
      .repositoryFingerprint(run.request.repoRoot)
      .value
      .takeIf(String::isNotBlank)

  private fun blockStep(
    state: PhaseReviewStepBinding,
    iteration: Int,
    reason: String,
    disposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    fileManifest: PhaseStepFileManifest? = null,
  ): PhaseOutcome {
    state.blockReviewStep(iteration, reason, disposition, fileManifest)
    return PhaseOutcome.blocked(reason)
  }

  private fun worktreeFailure(
    boundary: String,
    error: String,
  ) = "Feature-task-runtime phase 'review' could not capture its $boundary-file manifest: $error"
}

private data class ReviewPassRun(
  val iteration: Int,
  val reviewRunId: String,
  val cycle: InlineReviewCycle,
)

private data class ReviewedPass(
  val pass: ReviewPassRun,
  val result: ParallelCodeReviewResult,
  val manifest: PhaseStepFileManifest,
)

private sealed interface PhaseReviewRun {
  data class Resolved(
    val run: PhaseRun,
  ) : PhaseReviewRun

  data class Unresolved(
    val reason: String,
  ) : PhaseReviewRun
}

internal sealed interface ReviewPassLaunch {
  data class Reviewed(
    val result: ParallelCodeReviewResult,
  ) : ReviewPassLaunch

  data class Failed(
    val reason: String,
    val disposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
  ) : ReviewPassLaunch
}

internal fun failedLaneReason(result: ParallelCodeReviewResult): String? {
  val parent = result.lane1
  if (parent.agentId.isBlank() || parent.success) return null
  val detail = parent.failureReason?.takeIf(String::isNotBlank) ?: "lane failed"
  return "Feature-task-runtime phase 'review' $detail"
}

internal fun planningFailureLaunch(failure: ParallelCodeReviewPlanningFailure): ReviewPassLaunch.Failed =
  when (failure) {
    is ParallelCodeReviewPlanningFailure.DiffUnresolved ->
      ReviewPassLaunch.Failed("Runtime-owned review could not resolve the child-owned diff: ${failure.message}")
    is ParallelCodeReviewPlanningFailure.UsageInvalid, is ParallelCodeReviewPlanningFailure.StackUndetected ->
      ReviewPassLaunch.Failed(
        "Runtime-owned review failed: ${failure.message}",
        FeatureTaskRuntimeFailureDisposition.RETRYABLE,
      )
  }

private fun launchFailure(error: Throwable): ReviewPassLaunch.Failed? {
  val message = error.message.orEmpty()
  return when {
    error is CancellationException -> null
    error is UnreadableSpecIntentProjectionError ->
      ReviewPassLaunch.Failed("Runtime-owned review could not read the spec intent projection: $message")
    error is InvalidReviewContextSchemaError ->
      ReviewPassLaunch.Failed("Runtime-owned review produced an invalid review-context envelope: $message")
    error is SkillBillRuntimeException && error.code == RuntimeOwnedPersistenceFailureCode.FACT_UNAVAILABLE ->
      ReviewPassLaunch.Failed(
        "Runtime-owned review could not establish a required persistence fact: $message",
        FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
    error is Exception ->
      ReviewPassLaunch.Failed(
        "Runtime-owned review failed: ${error.failureCodeLabel() ?: error::class.simpleName}: $message",
        FeatureTaskRuntimeFailureDisposition.RETRYABLE,
      )
    else -> null
  }
}

private fun measuredReviewOutput(outputText: String): NormalizedFeatureTaskRuntimePhaseOutput =
  NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
    FeatureTaskRuntimeWorkflowArtifactMap.from(InlineReviewEnvelope.envelopeMap(outputText)),
  )
