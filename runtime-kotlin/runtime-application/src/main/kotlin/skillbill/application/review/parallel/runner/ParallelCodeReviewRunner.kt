package skillbill.application.review.parallel.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.application.review.model.ParallelCodeReviewPlanned
import skillbill.application.review.model.ParallelCodeReviewPlanningFailure
import skillbill.application.review.model.ParallelCodeReviewReportContract
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelCodeReviewRunOutcome
import skillbill.application.review.model.ParallelReviewAnalysisStageFailure
import skillbill.application.review.model.ReviewClaimVerificationOutcome
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
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ReviewAccountingRecord
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.model.accounting.ReviewIntegrationTerminalOutcome
import skillbill.review.context.model.execution.ResolvedReviewExecutionMode
import skillbill.review.model.ReviewCoverageReport
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewLaneAggregationInput
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.model.ReviewStage
import skillbill.review.parallel.ParallelReviewMerger
import skillbill.review.stage.ReviewStageDegradationSelection

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
    return finishReview(initial, outcomes, assembleReview(initial, outcomes))
  }

  private fun assembleReview(
    initial: ParallelCodeReviewInitialRun,
    outcomes: ParallelReviewLaneRunResult,
  ): ParallelCodeReviewResult {
    val failures = mutableListOf<String>()
    reportStage(initial, { resultAssembly.recordLaneDispositions(initial, outcomes) }) { error ->
      failures += "Lane disposition recording failed (${error.code}): ${error.message.orEmpty()}"
    }
    val integration =
      reportStage(initial, { resultAssembly.runIntegrationPass(initial, outcomes) }) { error ->
        val packet = initial.compiledLaunchRequests.firstOrNull()?.packet ?: throw error
        failures += "Integration stage failed (${error.code}): ${error.message.orEmpty()}"
        ReviewIntegrationPassOutcome(
          commitSequenceDigest = packet.commitSequenceDigest,
          terminalOutcome = ReviewIntegrationTerminalOutcome.FAILED,
          summarizedLaneCount = 0,
          failureReason = failures.joinToString("; "),
        )
      }
    val coverage =
      reportStage(initial, { resultAssembly.coverageReport(initial, outcomes, integration) }) { error ->
        val packet = initial.compiledLaunchRequests.firstOrNull()?.packet ?: throw error
        failures += "Coverage assembly failed (${error.code}): ${error.message.orEmpty()}"
        ReviewCoverageReport(
          cleanLanes = emptyList(),
          incompleteLanes =
            initial.preparedLaunchRequests.map { launch ->
              ReviewLaneAggregationInput(
                lane = launch.assignment.lane,
                commitSequenceDigest = packet.commitSequenceDigest,
                disposition = ReviewLaneReviewDisposition.INCOMPLETE,
                unreviewedUnits = listOf("coverage assembly failed"),
              )
            },
          integrationCompleted = false,
          integrationNotApplicableReason = null,
        )
      }
    val result =
      resultAssembly.parallelResult(
        ParallelResultArgs(
          agent1Id = initial.agent1Id,
          reportOnly = standaloneReportOnly(initial),
          outcomes = outcomes,
          integration = integration,
          coverage = coverage,
          packet = initial.compiledLaunchRequests.firstOrNull()?.packet,
          budget = initial.budget,
          stageResume = resultAssembly.stageResumeReport(initial.request.reviewRunId),
        ),
      )
    return if (failures.isEmpty()) {
      result
    } else {
      result.copy(
        lane1 =
          result.lane1.copy(
            success = false,
            failureReason = failures.joinToString("; "),
            reviewDisposition = ReviewLaneReviewDisposition.INCOMPLETE,
          ),
      )
    }
  }

  private fun <T> reportStage(
    initial: ParallelCodeReviewInitialRun,
    action: () -> T,
    onFailure: (SkillBillRuntimeException) -> T,
  ): T =
    try {
      action()
    } catch (error: SkillBillRuntimeException) {
      error.rethrowIfCooperativeCancellationOrInterruption()
      if (!standaloneReportOnly(initial)) throw error
      onFailure(error)
    }

  private fun finishReview(
    initial: ParallelCodeReviewInitialRun,
    outcomes: ParallelReviewLaneRunResult,
    assembledResult: ParallelCodeReviewResult,
  ): ParallelCodeReviewResult {
    var result = assembledResult
    return try {
      resultAssembly.persistReviewPassClaims(
        initial.request.reviewRunId,
        result.mergeResult.findings,
        persistEmpty = true,
      )
      resultAssembly.recordReviewStageBoundary(
        initial.request.reviewRunId,
        requireNotNull(result.integration),
        result.mergeResult.findings,
      )
      resultAssembly.recordMergedFindingLanes(initial.request.reviewRunId)
      val verificationOutcome = verificationStages.runClaimVerification(initial, result)
      result = withVerificationFailures(initial, result, verificationOutcome)
      val adjudicationOutcome = verificationStages.runSpecAdjudication(initial, result)
      val recordedVerdicts =
        verificationStages.recordedFindingVerdicts(
          initial.request.reviewRunId,
          verificationOutcome.verdicts + adjudicationOutcome.verdicts,
        )
      result =
        result.copy(
          analysisStageFailures =
            (
              result.analysisStageFailures +
                workerFailures(
                  verificationOutcome.verdicts + adjudicationOutcome.verdicts + recordedVerdicts,
                )
            ).distinct(),
        )
      resultAssembly.emitReviewStageDegradations(
        initial.request.reviewRunId,
        outcomes,
        verificationOutcome.nonSuccess,
      )
      val assembled =
        ParallelReviewMerger.withRecordedVerdicts(result.mergeResult, recordedVerdicts)
          .copy(formattedOutput = result.output)
      persistAccounting(result)
      result.copy(
        mergeResult = assembled,
        reviewSessionId = initial.reviewSessionId,
        appliedLearnings = initial.appliedLearnings,
        stageResume = resultAssembly.stageResumeReport(initial.request.reviewRunId),
        citationDiagnostics =
          result.citationDiagnostics +
            verificationOutcome.citationDiagnostics +
            adjudicationOutcome.citationDiagnostics,
      )
    } catch (error: SkillBillRuntimeException) {
      error.rethrowIfCooperativeCancellationOrInterruption()
      if (
        initial.request.reportContract !=
        ParallelCodeReviewReportContract.STANDALONE_REPORT_ONLY
      ) {
        throw error
      }
      result.copy(
        lane1 =
          result.lane1.copy(
            success = false,
            failureReason = "A delegated review analysis stage failed (${error.code}): ${error.message.orEmpty()}",
            reviewDisposition = ReviewLaneReviewDisposition.INCOMPLETE,
          ),
      )
    }
  }

  private fun withVerificationFailures(
    initial: ParallelCodeReviewInitialRun,
    reviewResult: ParallelCodeReviewResult,
    verificationOutcome: ReviewClaimVerificationOutcome,
  ): ParallelCodeReviewResult {
    val failures =
      listOfNotNull(
        verificationOutcome.nonSuccess?.let { nonSuccess ->
          ParallelReviewAnalysisStageFailure(
            stage = ReviewStage.VERIFICATION,
            reason = nonSuccess.reason,
            detail = nonSuccess.detail,
          )
        },
      ) + workerFailures(verificationOutcome.verdicts)
    val verificationVerdicts =
      verificationStages.recordedFindingVerdicts(
        initial.request.reviewRunId,
        verificationOutcome.verdicts,
      )
    return reviewResult.copy(
      analysisStageFailures =
        (reviewResult.analysisStageFailures + failures + workerFailures(verificationVerdicts)).distinct(),
    )
  }

  private fun standaloneReportOnly(initial: ParallelCodeReviewInitialRun): Boolean =
    initial.request.reportContract ==
      ParallelCodeReviewReportContract.STANDALONE_REPORT_ONLY

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

  private fun workerFailures(verdicts: List<ReviewFindingVerdict>): List<ParallelReviewAnalysisStageFailure> =
    verdicts.mapNotNull { verdict ->
      val detail = verdict.rejectionReason ?: return@mapNotNull null
      val reason = ReviewStageDegradationSelection.workerFailureReason(detail) ?: return@mapNotNull null
      ParallelReviewAnalysisStageFailure(verdict.stage, reason, detail, verdict.findingRef)
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
