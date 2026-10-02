package skillbill.application.review.parallel.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.ParallelCodeReviewPlanned
import skillbill.application.review.model.ParallelCodeReviewPlanningFailure
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelCodeReviewRunOutcome
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.application.review.parallel.planning.ParallelCodeReviewRunnerPlanning
import skillbill.application.review.parallel.planning.hasSuppliedDiff
import skillbill.application.review.parallel.planning.resolveDiff
import skillbill.application.review.parallel.planning.resolveReviewRevisions
import skillbill.application.review.parallel.verification.ParallelCodeReviewRunnerVerificationStages
import skillbill.application.reviewevidence.model.DiffResolution
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.application.runtimepersistence.RuntimeOwnedPersistenceBoundary
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.ports.review.launch.ReviewNativeAgentPreflightPort
import skillbill.ports.review.model.ReviewAccountingRecord
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.model.execution.ResolvedReviewExecutionMode
import skillbill.review.parallel.ParallelReviewMerger

@Inject
class ParallelCodeReviewRunner(
  private val planning: ParallelCodeReviewRunnerPlanning,
  private val laneLaunch: ParallelCodeReviewRunnerLaneLaunch,
  private val resultAssembly: ParallelCodeReviewRunnerResultAssembly,
  private val verificationStages: ParallelCodeReviewRunnerVerificationStages,
  private val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
  private val nativeAgentPreflight: ReviewNativeAgentPreflightPort,
) {
  fun run(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewRunOutcome {
    requireDelegatedMode(originalRequest)
    earlyEmptyDelta(originalRequest)?.let { return it }
    val initial =
      when (val planned = planning.prepareInitialRun(originalRequest)) {
        is ParallelCodeReviewPlanned.Failed -> return ParallelCodeReviewRunOutcome.PlanningFailed(planned.failure)
        is ParallelCodeReviewPlanned.Ready -> planned.value
      }
    return ParallelCodeReviewRunOutcome.Reviewed(reviewPlanned(initial))
  }

  private fun reviewPlanned(initial: ParallelCodeReviewInitialRun): ParallelCodeReviewResult {
    verifyNativeWorkers(initial)
    val outcomes = laneLaunch.runLanes(initial)
    resultAssembly.recordLaneDispositions(initial, outcomes)
    val integration = resultAssembly.runIntegrationPass(initial, outcomes)
    val coverage = resultAssembly.coverageReport(initial, outcomes, integration)
    val result =
      resultAssembly.parallelResult(
        ParallelResultArgs(
          agent1Id = initial.agent1Id,
          outcomes = outcomes,
          integration = integration,
          coverage = coverage,
          packet = initial.compiledLaunchRequests.firstOrNull()?.packet,
          budget = initial.budget,
          stageResume = resultAssembly.stageResumeReport(initial.request.reviewRunId),
        ),
      )
    resultAssembly.persistReviewPassClaims(
      initial.request.reviewRunId,
      result.mergeResult.findings,
      persistEmpty = true,
    )
    resultAssembly.recordReviewStageBoundary(
      initial.request.reviewRunId,
      integration,
      result.mergeResult.findings,
    )
    resultAssembly.recordMergedFindingLanes(initial.request.reviewRunId)
    val verificationOutcome = verificationStages.runClaimVerification(initial, result)
    val adjudicationOutcome = verificationStages.runSpecAdjudication(initial, result)
    val recordedVerdicts =
      verificationStages.recordedFindingVerdicts(
        initial.request.reviewRunId,
        verificationOutcome.verdicts + adjudicationOutcome.verdicts,
      )
    resultAssembly.emitReviewStageDegradations(
      initial.request.reviewRunId,
      outcomes,
      verificationOutcome.nonSuccess,
    )
    val prose = result.output
    val assembled =
      ParallelReviewMerger.withRecordedVerdicts(result.mergeResult, recordedVerdicts)
        .copy(formattedOutput = prose)
    persistAccounting(result)
    return result.copy(
      mergeResult = assembled,
      reviewSessionId = initial.reviewSessionId,
      appliedLearnings = initial.appliedLearnings,
      stageResume = resultAssembly.stageResumeReport(initial.request.reviewRunId),
      citationDiagnostics =
        result.citationDiagnostics +
          verificationOutcome.citationDiagnostics +
          adjudicationOutcome.citationDiagnostics,
    )
  }

  private fun persistAccounting(result: ParallelCodeReviewResult) {
    val summary = result.accountingSummary ?: return
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.saveAccounting",
      expected = "runtime-owned review accounting",
    ) { unitOfWork ->
      unitOfWork.reviews.saveAccounting(
        ReviewAccountingRecord(summary.reviewId, summary.packetDigest, summary),
      )
    }
  }

  private fun earlyEmptyDelta(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewRunOutcome? =
    when {
      originalRequest.suppliedDiff != null && originalRequest.suppliedDiff.isBlank() ->
        completeEmptyDelta(originalRequest)
      originalRequest.scope == ParallelReviewScope.WORKTREE_FROM_BASE &&
        !planning.hasSuppliedDiff(originalRequest) -> worktreeEmptyDelta(originalRequest)
      else -> null
    }

  private fun worktreeEmptyDelta(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewRunOutcome? {
    val revisions =
      when (val resolved = planning.resolveReviewRevisions(originalRequest)) {
        is DiffResolution.Unresolved -> return planningFailed(resolved)
        is DiffResolution.Resolved -> resolved.value
      }
    val diff =
      when (val resolved = planning.resolveDiff(originalRequest, revisions)) {
        is DiffResolution.Unresolved -> return planningFailed(resolved)
        is DiffResolution.Resolved -> resolved.value
      }
    return if (diff.isBlank()) completeEmptyDelta(originalRequest) else null
  }

  private fun completeEmptyDelta(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewRunOutcome =
    ParallelCodeReviewRunOutcome.Reviewed(
      planning.completeEmptySuppliedDelta(originalRequest) { reviewRunId ->
        verificationStages.recordAdjudicationBoundary(reviewRunId)
      },
    )

  private fun planningFailed(unresolved: DiffResolution.Unresolved): ParallelCodeReviewRunOutcome =
    ParallelCodeReviewRunOutcome.PlanningFailed(ParallelCodeReviewPlanningFailure.DiffUnresolved(unresolved.message))

  private fun requireDelegatedMode(request: ParallelCodeReviewRequest) {
    val requested = request.resolvedTier ?: request.codeReviewMode
    if (ReviewExecutionModePolicy.resolve(requested) == ResolvedReviewExecutionMode.INLINE) {
      throw SkillBillRuntimeException(
        GovernedReviewFailureCode.INLINE_PARALLEL_UNSUPPORTED,
        "The parallel code-review runner runs only delegated reviews; requested mode " +
          "'${requested.wireValue}' resolves to inline, which the inline review strategy runs.",
      )
    }
  }

  private fun verifyNativeWorkers(initial: ParallelCodeReviewInitialRun) {
    val logicalNames =
      initial.compiledLaunchRequests
        .filter { it.workerKind == ReviewWorkerKind.PROVIDER_NATIVE }
        .mapNotNull { it.logicalWorkerName }
        .distinct()
    if (logicalNames.isEmpty()) return
    nativeAgentPreflight.verify(
      ReviewNativeAgentPreflightRequest(
        repoRoot = initial.request.repoRoot,
        agentIds = listOf(initial.agent1Id),
        logicalNames = logicalNames,
      ),
    )
  }
}
