package skillbill.engine.featuretask.slot.codereview

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.StackDetectionException
import skillbill.application.review.model.UsageValidationException
import skillbill.application.reviewevidence.model.DiffResolutionException
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.persist.RuntimeOwnedFactUnavailable
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseLaunchReviewTier
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepState
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.error.featuretask.UnknownPhaseReviewTargetError
import skillbill.error.shellcontent.InvalidReviewContextSchemaError
import skillbill.error.shellcontent.UnreadableSpecIntentProjectionError
import skillbill.goalrunner.subtaskreview.FeatureTaskRuntimeVerificationSignalKeys
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.goalrunner.subtaskreview.model.UnaddressedFindingLedgerScope
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.context.model.hunk.ReviewContextBudgetExceededException
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.requireAcceptedOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.coroutines.cancellation.CancellationException

internal class CodeReviewStep(
  private val runner: PhaseRunner,
  private val reviewPass: CodeReviewPass,
) : PhaseStepHooks {
  fun run(
    requestedRun: PhaseRun,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    prompt: PhaseStepPromptSource,
  ): PhaseOutcome {
    val run =
      when (val phaseRun = phaseReviewRun(requestedRun, context)) {
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
    val iteration = state.nextStepIteration()
    val passNumber = state.reviewPassNumber()
    val resolution =
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(run.request.runInvariants.codeReviewMode, passNumber)
    val reviewRunId =
      state.recordedReviewRunId(passNumber)
        ?: run.request.reviewInvocation?.reviewRunId?.takeIf { passNumber == 1 }
        ?: InlineReviewEnvelope.mintReviewRunId(context.clock)
    state.startReview(iteration, reviewRunId)
    val fingerprint =
      repositoryFingerprint(run, context)
        ?: return PhaseOutcome.blocked("Runtime-owned review could not resolve a repository checkpoint fingerprint.")
    state.prepareReviewBriefing(prompt, input)
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
    state: PhaseStepState,
  ): PhaseLaunchReviewTier {
    val passNumber = state.reviewPassNumber()
    val resolution =
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(run.request.runInvariants.codeReviewMode, passNumber)
    state.persistResolvedReviewTier(resolution)
    return PhaseLaunchReviewTier(passNumber, resolution, reviewPass.executedTier(resolution.resolvedTier))
  }

  override fun completionRejection(
    run: PhaseRun,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    val hasVerdict = (outputMap[FeatureTaskRuntimeVerificationSignalKeys.VERDICT] as? String)?.isNotBlank() == true
    val producedOutputs = outputMap[SharedPayloadKeys.PRODUCED_OUTPUTS] as? Map<*, *>
    val findingsKey = FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS
    val hasFindingsArray = producedOutputs?.containsKey(findingsKey) == true && producedOutputs[findingsKey] is List<*>
    return if (hasVerdict || hasFindingsArray) {
      null
    } else {
      "Review phase reported 'completed' without a verification signal: the output must carry either a " +
        "top-level 'verdict' or a 'produced_outputs.findings' array (an explicit empty array affirms no " +
        "blocking findings). A review that emits neither cannot advance past a possible Blocker/Major; " +
        "the schema gate fails rather than silently advancing to validation."
    }
  }

  private fun launchAndSettle(
    run: PhaseRun,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    input: GoalSubtaskReviewInput,
    pass: ReviewPassRun,
  ): PhaseOutcome {
    val gitOperations = context.phaseGates.gitOperations
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
    state.recordReviewContentIdentities()
    context.runState.recordReviewRun(pass.reviewRunId, result, reviewPass.recordsLaneTelemetry)
    val manifest =
      PhaseStepFileManifest(
        before = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(before.value.orEmpty()),
        after = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(after.value.orEmpty()),
      )
    failedLaneReason(result)?.let { reason ->
      return blockStep(state, pass.iteration, reason, FeatureTaskRuntimeFailureDisposition.RETRYABLE)
    }
    val dispositions = blockerDispositions(run, state, result, pass)
    val settled = pass.copy(cycle = pass.cycle.copy(blockerDispositions = dispositions))
    return settle(run, context, state, settled, result, manifest)
  }

  private fun launch(
    run: PhaseRun,
    state: PhaseStepState,
    input: GoalSubtaskReviewInput,
    reviewRunId: String,
  ): ReviewPassLaunch {
    val outcome = runCatching { reviewPass.review(run, input, reviewRunId, runner, state) }
    outcome.exceptionOrNull()?.let { error -> return launchFailure(error) ?: throw error }
    return ReviewPassLaunch.Reviewed(outcome.getOrThrow())
  }

  private fun settle(
    run: PhaseRun,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    pass: ReviewPassRun,
    result: ParallelCodeReviewResult,
    manifest: PhaseStepFileManifest,
  ): PhaseOutcome {
    val initialText = InlineReviewEnvelope.assemble(result, pass.reviewRunId, pass.cycle)
    val accepted =
      runCatching {
        context.outputValidator.validatePhaseOutput(initialText, sourceLabel = run.phaseId)
          .requireAcceptedOutput(run.phaseId)
      }.getOrElse { error ->
        return blockStep(
          state,
          pass.iteration,
          "Runtime-owned review settlement did not validate: ${error.message.orEmpty()}",
          fileManifest = manifest,
        )
      }
    if (manifest.before == manifest.after) {
      return complete(run, state, pass.iteration, initialText, accepted, manifest)
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
      InlineReviewEnvelope.assemble(result, pass.reviewRunId, pass.cycle.copy(repositoryFingerprint = refreshed))
    return complete(
      run,
      state,
      pass.iteration,
      outputText,
      accepted.withNormalizedEnvelope(outputText),
      manifest,
    )
  }

  private fun complete(
    run: PhaseRun,
    state: PhaseStepState,
    iteration: Int,
    outputText: String,
    output: AcceptedFeatureTaskRuntimePhaseOutput,
    manifest: PhaseStepFileManifest,
  ): PhaseOutcome {
    state.retainReviewOutput(iteration, outputText)
    state.completeReview(iteration, outputText, output, manifest)?.let { reason -> return PhaseOutcome.blocked(reason) }
    state.stepCompleted(iteration)
    return PhaseOutcome.completed(completedOutput(run, iteration, output))
  }

  private fun blockerDispositions(
    run: PhaseRun,
    state: PhaseStepState,
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
    context: PhaseAttemptEnvironment,
  ): PhaseReviewRun {
    val invocation = run.request.reviewInvocation ?: return PhaseReviewRun.Resolved(run)
    val gitOperations = context.phaseGates.gitOperations
    val repoRoot = run.request.repoRoot
    val status = gitOperations.worktreeStatus(repoRoot)
    if (status !is WorkflowGitOperationResult.Ok) {
      return PhaseReviewRun.Unresolved(
        "Feature-task-runtime phase 'review' could not read the worktree status to resolve its target: " +
          status.error,
      )
    }
    val target =
      context.runState.pinnedReviewTarget {
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
    context: PhaseAttemptEnvironment,
  ): String? =
    context.phaseGates.gitOperations.repositoryFingerprint(run.request.repoRoot).value.takeIf(String::isNotBlank)

  private fun blockStep(
    state: PhaseStepState,
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

private sealed interface PhaseReviewRun {
  data class Resolved(val run: PhaseRun) : PhaseReviewRun

  data class Unresolved(val reason: String) : PhaseReviewRun
}

private sealed interface ReviewPassLaunch {
  data class Reviewed(val result: ParallelCodeReviewResult) : ReviewPassLaunch

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

private fun launchFailure(error: Throwable): ReviewPassLaunch.Failed? {
  val message = error.message.orEmpty()
  return when (error) {
    is CancellationException -> null
    is DiffResolutionException ->
      ReviewPassLaunch.Failed("Runtime-owned review could not resolve the child-owned diff: $message")
    is UsageValidationException, is StackDetectionException ->
      ReviewPassLaunch.Failed("Runtime-owned review failed: $message", FeatureTaskRuntimeFailureDisposition.RETRYABLE)
    is ReviewContextBudgetExceededException ->
      ReviewPassLaunch.Failed("Runtime-owned review exceeded a review-context budget: $message")
    is UnreadableSpecIntentProjectionError ->
      ReviewPassLaunch.Failed("Runtime-owned review could not read the spec intent projection: $message")
    is InvalidReviewContextSchemaError ->
      ReviewPassLaunch.Failed("Runtime-owned review produced an invalid review-context envelope: $message")
    is RuntimeOwnedFactUnavailable ->
      ReviewPassLaunch.Failed(
        "Runtime-owned review could not establish a required persistence fact: $message",
        FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
    is Exception ->
      ReviewPassLaunch.Failed(
        "Runtime-owned review failed: ${error::class.simpleName}: $message",
        FeatureTaskRuntimeFailureDisposition.RETRYABLE,
      )
    else -> null
  }
}

private fun AcceptedFeatureTaskRuntimePhaseOutput.withNormalizedEnvelope(
  outputText: String,
): AcceptedFeatureTaskRuntimePhaseOutput {
  val envelope = InlineReviewEnvelope.envelopeMap(outputText)
  return copy(
    normalizedOutput =
      NormalizedFeatureTaskRuntimePhaseOutput(
        canonicalJson = JsonCodec.mapToJsonString(envelope),
        envelope = envelope,
      ),
  )
}
