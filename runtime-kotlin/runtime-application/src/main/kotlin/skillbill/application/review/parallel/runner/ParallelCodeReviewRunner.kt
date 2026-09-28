package skillbill.application.review.parallel.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.application.review.parallel.planning.ParallelCodeReviewRunnerPlanning
import skillbill.application.review.parallel.planning.hasSuppliedDiff
import skillbill.application.review.parallel.planning.resolveDiff
import skillbill.application.review.parallel.planning.resolveReviewRevisions
import skillbill.application.review.parallel.verification.ParallelCodeReviewRunnerVerificationStages
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.application.runtimepersistence.RuntimeOwnedPersistenceBoundary
import skillbill.error.shellcontent.InlineParallelReviewUnsupportedError
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
  fun run(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewResult {
    requireDelegatedMode(originalRequest)
    earlyEmptyDelta(originalRequest)?.let { return it }
    val initial = planning.prepareInitialRun(originalRequest)
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

  private fun earlyEmptyDelta(originalRequest: ParallelCodeReviewRequest): ParallelCodeReviewResult? {
    if (originalRequest.suppliedDiff != null && originalRequest.suppliedDiff.isBlank()) {
      return planning.completeEmptySuppliedDelta(originalRequest) { reviewRunId ->
        verificationStages.recordAdjudicationBoundary(reviewRunId)
      }
    }
    if (
      originalRequest.scope == ParallelReviewScope.WORKTREE_FROM_BASE &&
      !planning.hasSuppliedDiff(originalRequest)
    ) {
      val revisions = planning.resolveReviewRevisions(originalRequest)
      if (planning.resolveDiff(originalRequest, revisions).isBlank()) {
        return planning.completeEmptySuppliedDelta(originalRequest) { reviewRunId ->
          verificationStages.recordAdjudicationBoundary(reviewRunId)
        }
      }
    }
    return null
  }

  private fun requireDelegatedMode(request: ParallelCodeReviewRequest) {
    val requested = request.resolvedTier ?: request.codeReviewMode
    if (ReviewExecutionModePolicy.resolve(requested) == ResolvedReviewExecutionMode.INLINE) {
      throw InlineParallelReviewUnsupportedError(requested.wireValue)
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
