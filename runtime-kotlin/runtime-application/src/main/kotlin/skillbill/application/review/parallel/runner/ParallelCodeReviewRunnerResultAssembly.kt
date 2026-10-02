package skillbill.application.review.parallel.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.application.review.model.ReviewIntegrationPassRunRequest
import skillbill.application.review.model.ReviewLaneIntegrationInput
import skillbill.application.review.preparation.ReviewStageResumeSelection
import skillbill.application.review.verification.ReviewIntegrationPassRunner
import skillbill.application.runtimepersistence.RuntimeOwnedPersistenceBoundary
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.goalrunner.terminalStatus
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.ports.review.model.ReviewIntegrationPassRecord
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.review.context.ReviewTreeAccounting
import skillbill.review.context.model.accounting.ReviewAccountingCounters
import skillbill.review.context.model.accounting.ReviewAccountingInput
import skillbill.review.context.model.accounting.ReviewAccountingSummary
import skillbill.review.context.model.accounting.ReviewAccountingTerminalOutcome
import skillbill.review.context.model.accounting.ReviewCommitRoutingAccounting
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.accounting.ReviewIntegrationAccounting
import skillbill.review.context.model.accounting.ReviewIntegrationTerminalOutcome
import skillbill.review.context.model.accounting.ReviewParentAnalysisConsumption
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.context.model.packet.ReviewRunLaneSegmentAccountingJson
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewCoverageReport
import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import skillbill.review.model.ReviewFindingCitationDiagnosticWithFinding
import skillbill.review.model.ReviewLaneAggregationInput
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageDegradationSelectionRequest
import skillbill.review.model.ReviewStageReached
import skillbill.review.model.ReviewStageResumeReport
import skillbill.review.model.ReviewVerificationNonSuccess
import skillbill.review.parallel.ParallelReviewMerger
import skillbill.review.parsing.ReviewLaneAggregation
import skillbill.review.stage.ReviewStageDegradationSelection
import skillbill.text.sha256HexUtf8
import java.time.Clock

@Inject
class ParallelCodeReviewRunnerResultAssembly(
  private val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  private val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  private val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
  private val clock: Clock,
) {
  internal fun runIntegrationPass(
    initial: ParallelCodeReviewInitialRun,
    outcomes: ParallelReviewLaneRunResult,
  ): ReviewIntegrationPassOutcome {
    val packet =
      initial.compiledLaunchRequests.firstOrNull()?.packet
        ?: return ReviewIntegrationPassOutcome.skipped(
          PARALLEL_REVIEW_NO_SEQUENCE_DIGEST,
          "the review compiled no specialist lane, so there is no commit sequence to integrate over",
        )
    runtimeOwnedPersistence.durableIntegrationOutcome(initial.request.reviewRunId, packet.commitSequenceDigest)
      ?.let { return it }
    val findingsByLane =
      outcomes.lane1.findings
        .groupingBy { it.specialistSkillName.orEmpty() }.eachCount()
    val lanes =
      initial.compiledLaunchRequests.map { launch ->
        ReviewLaneIntegrationInput(
          launch = launch,
          completion = parallelCodeReviewEffectiveCompletionState(launch, outcomes),
          findingCount = findingsByLane[launch.assignment.laneDecision.specialistSkillName] ?: 0,
        )
      }
    val outcome =
      ReviewIntegrationPassRunner(parentReviewLauncher, reviewContextEnvelopeValidator).run(
        ReviewIntegrationPassRunRequest(
          packet = packet,
          lanes = lanes,
          launch = initial.delegatedStageLaunch(),
        ),
      )
    runtimeOwnedPersistence.recordIntegrationBoundary(initial.request.reviewRunId, outcome)
    return outcome
  }

  internal fun coverageReport(
    initial: ParallelCodeReviewInitialRun,
    outcomes: ParallelReviewLaneRunResult,
    integration: ReviewIntegrationPassOutcome,
  ): ReviewCoverageReport? {
    val packet = initial.compiledLaunchRequests.firstOrNull()?.packet ?: return null
    val ranThisPass =
      initial.preparedLaunchRequests.map { launch ->
        val completion = parallelCodeReviewEffectiveCompletionState(launch, outcomes)
        ReviewLaneAggregationInput(
          lane = launch.assignment.lane,
          commitSequenceDigest = packet.commitSequenceDigest,
          disposition = completion.disposition,
          unreviewedUnits = completion.unreviewedUnits,
        )
      }
    val results = ranThisPass + runtimeOwnedPersistence.durablyCompleteLanes(initial, packet, ranThisPass)
    val bothAgentsSucceeded = outcomes.lane1.success
    return ReviewLaneAggregation.requireCompleteLaneResults(
      expectedLanes = packet.selectedLanes,
      results = results,
      commitSequenceDigest = packet.commitSequenceDigest,
    ).copy(
      integrationCompleted = integration.completed && bothAgentsSucceeded,
      integrationNotApplicableReason = integration.skipReason,
    )
  }

  internal fun recordLaneDispositions(
    initial: ParallelCodeReviewInitialRun,
    outcomes: ParallelReviewLaneRunResult,
  ) {
    val reviewRunId = initial.request.reviewRunId ?: return
    val existing =
      runtimeOwnedPersistence.requiredRead(
        seam = "ParallelCodeReviewRunner.recordLaneDispositions.read",
        expected = "runtime-owned review lane dispositions",
      ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
    if (existing.isEmpty()) return
    val completionBySkill =
      initial.preparedLaunchRequests.associate { launch ->
        requireNotNull(launch.assignment.laneDecision.specialistSkillName) to
          parallelCodeReviewEffectiveCompletionState(launch, outcomes)
      }
    val updated =
      existing.map { lane ->
        val completion = completionBySkill[lane.laneSkillName] ?: return@map lane
        val durableComplete = completion.disposition == ReviewLaneReviewDisposition.COMPLETE
        lane.copy(
          reviewDisposition =
            if (durableComplete) {
              ReviewLaneReviewDisposition.COMPLETE
            } else {
              ReviewLaneReviewDisposition.INCOMPLETE
            },
          bundleCompositionDigest = completion.bundleCompositionDigest,
          segmentAccountingJson = ReviewRunLaneSegmentAccountingJson.encode(completion.segments),
          unreviewedSegmentIds = completion.unreviewedSegmentIds,
          budgetDimension = completion.budgetDimension,
        )
      }
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordLaneDispositions.write",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.replaceReviewRunLanes(reviewRunId, updated) }
  }

  fun recordMergedFindingLanes(reviewRunId: String?) {
    if (reviewRunId == null) return
    val claims =
      runtimeOwnedPersistence.requiredRead(
        seam = "ParallelCodeReviewRunner.recordMergedFindingLanes.read",
        expected = "runtime-owned review pass claims",
      ) { unitOfWork ->
        unitOfWork.reviews.fetchReviewPassClaims(reviewRunId)
      }?.findings.orEmpty()
    val attribution =
      claims.mapNotNull { finding ->
        finding.specialistSkillNames.firstOrNull()?.let { finding.fNumber to it }
      }.toMap()
    if (attribution.isEmpty()) return
    runtimeOwnedPersistence.optionalWrite(
      seam = "ParallelCodeReviewRunner.recordMergedFindingLanes.write",
      expected = "optional runtime-owned finding lane attribution",
      fallback = Unit,
    ) { unitOfWork -> unitOfWork.reviews.recordFindingLaneAttribution(reviewRunId, attribution) }
  }

  fun emitReviewStageDegradations(
    reviewRunId: String?,
    outcomes: ParallelReviewLaneRunResult,
    verificationNonSuccess: ReviewVerificationNonSuccess?,
  ) {
    if (reviewRunId == null) return
    val evidenceBoundaries = evidenceBoundaryAccountings(outcomes)
    val selected =
      runtimeOwnedPersistence.optionalRead(
        seam = "ParallelCodeReviewRunner.emitReviewStageDegradations.read",
        expected = "optional review stage degradation inputs",
        fallback = emptyList(),
      ) { unitOfWork ->
        ReviewStageDegradationSelection.select(
          ReviewStageDegradationSelectionRequest(
            reviewRunId = reviewRunId,
            spec = unitOfWork.reviews.fetchSpecProjectionReference(reviewRunId),
            boundaries = unitOfWork.reviews.fetchStageBoundaries(reviewRunId),
            verdicts = unitOfWork.reviews.fetchFindingVerdicts(reviewRunId),
            claims = unitOfWork.reviews.fetchReviewPassClaims(reviewRunId),
            evidenceBoundaries = evidenceBoundaries,
            verificationNonSuccess = verificationNonSuccess,
          ),
        )
      }
    runtimeOwnedPersistence.optionalWrite(
      seam = "ParallelCodeReviewRunner.emitReviewStageDegradations.write",
      expected = "optional review stage degradation telemetry",
      fallback = Unit,
    ) { unitOfWork ->
      selected.forEach { unitOfWork.lifecycleTelemetry.reviewStageDegradation(it) }
    }
  }

  fun persistReviewPassClaims(
    reviewRunId: String?,
    findings: List<ParallelReviewMergedFinding>,
    persistEmpty: Boolean,
  ) {
    if (reviewRunId == null) return
    if (findings.isEmpty() && !persistEmpty) return
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.persistReviewPassClaims",
      expected = "runtime-owned review pass claims",
    ) { unitOfWork ->
      val recorded = unitOfWork.reviews.fetchReviewPassClaims(reviewRunId)
      val existing = recorded?.findings.orEmpty()
      val unioned = unionReviewPassClaims(existing, findings)
      if (unioned.isEmpty() && !persistEmpty) return@requiredWrite
      if (recorded != null && existing == unioned) return@requiredWrite
      unitOfWork.reviews.recordReviewPassClaims(reviewRunId, unioned)
    }
  }

  fun recordReviewStageBoundary(
    reviewRunId: String?,
    integration: ReviewIntegrationPassOutcome,
    findings: List<ParallelReviewMergedFinding>,
  ) {
    if (reviewRunId == null || !integration.durable) return
    val lanes =
      runtimeOwnedPersistence.requiredRead(
        seam = "ParallelCodeReviewRunner.recordReviewStageBoundary.read",
        expected = "runtime-owned review lane dispositions",
      ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
    if (lanes.isEmpty() || lanes.any { it.reviewDisposition != ReviewLaneReviewDisposition.COMPLETE }) {
      return
    }
    persistReviewPassClaims(reviewRunId, findings, persistEmpty = true)
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordReviewStageBoundary.write",
      expected = "runtime-owned review stage boundary",
    ) { unitOfWork ->
      unitOfWork.reviews.recordStageBoundary(
        reviewRunId,
        ReviewStageBoundary(
          stage = ReviewStage.REVIEW,
          reached = ReviewStageReached.REACHED,
          recordedAt = clock.instant().toString(),
          contractVersion = REVIEW_CONTEXT_CONTRACT_VERSION,
        ),
      )
    }
  }

  fun stageResumeReport(reviewRunId: String?): ReviewStageResumeReport? {
    if (reviewRunId == null) return null
    return runtimeOwnedPersistence.optionalRead(
      seam = "ParallelCodeReviewRunner.stageResumeReport",
      expected = "optional runtime-owned review resume report",
      fallback = null,
    ) { unitOfWork ->
      ReviewStageResumeSelection.select(
        unitOfWork.reviews.fetchStageBoundaries(reviewRunId),
        unitOfWork.reviews.fetchFindingVerdicts(reviewRunId),
      )
    }
  }

  internal fun parallelResult(args: ParallelResultArgs): ParallelCodeReviewResult {
    val prose = args.outcomes.lane1.rawOutput.ifBlank { "Review completed with no prose body." }
    val lane1Result =
      ParallelReviewLaneResult(
        agentId = args.agent1Id,
        findings = args.outcomes.lane1.findings,
      )
    val integrationLane =
      args.integration.findings.takeIf { it.isNotEmpty() }?.let {
        ParallelReviewLaneResult(ReviewIntegrationPassRunner.INTEGRATION_LANE, it)
      }
    val merged =
      if (integrationLane != null) {
        ParallelReviewMerger.merge(lane1Result, integrationLane)
      } else {
        ParallelReviewMerger.merge(
          lane1Result,
          ParallelReviewLaneResult(agentId = args.agent1Id, findings = emptyList()),
        )
      }
    val citationDiagnostics =
      remapCitationDiagnostics(
        diagnostics = args.outcomes.lane1.citationDiagnostics + args.integration.citationDiagnostics,
        findings = merged.findings,
      )
    return ParallelCodeReviewResult(
      mergeResult = merged.copy(formattedOutput = prose),
      lane1 = args.outcomes.lane1.toParallelReviewLaneStatus(args.agent1Id),
      accountingSummary =
        parallelAccountingSummary(args.outcomes)
          ?.withCommitFocusedAccounting(args.packet, args.budget, args.integration, args.coverage),
      integration = args.integration,
      coverage = args.coverage,
      stageResume = args.stageResume,
      citationDiagnostics = citationDiagnostics,
    )
  }
}

private fun remapCitationDiagnostics(
  diagnostics: List<ReviewFindingCitationDiagnosticWithFinding>,
  findings: List<ParallelReviewMergedFinding>,
): List<ReviewFindingCitationDiagnosticWithFinding> =
  diagnostics.map { diagnostic ->
    val finalRef =
      diagnostic.findingRef?.let { sourceRef ->
        findings.firstOrNull { sourceRef in it.sourceFindingRefs }?.fNumber
      }
    if (finalRef == null) diagnostic else diagnostic.copy(findingRef = finalRef)
  }.distinct()

private fun ParallelReviewLaneOutcome.toParallelReviewLaneStatus(agentId: String) =
  ParallelReviewLaneStatus(
    agentId,
    success,
    failureReason,
    droppedCandidateDiagnostic,
    budgetOutcome,
    accounting,
    specialistAccounting,
    reviewPassDisposition(),
  )

private fun ParallelReviewLaneOutcome.reviewPassDisposition(): ReviewLaneReviewDisposition =
  if (success && !interrupted && rawOutput.isNotBlank()) {
    reviewDisposition ?: ReviewLaneReviewDisposition.COMPLETE
  } else {
    ReviewLaneReviewDisposition.INCOMPLETE
  }

private fun RuntimeOwnedPersistenceBoundary.durableIntegrationOutcome(
  reviewRunId: String?,
  commitSequenceDigest: String,
): ReviewIntegrationPassOutcome? {
  if (reviewRunId == null) return null
  val record =
    optionalRead(
      seam = "ParallelCodeReviewRunner.durableIntegrationOutcome",
      expected = "optional durable review integration result",
      fallback = null,
    ) { unitOfWork -> unitOfWork.reviews.fetchIntegrationPass(reviewRunId) }
  val terminal =
    record
      ?.takeIf { it.commitSequenceDigest == commitSequenceDigest }
      ?.let { ReviewIntegrationTerminalOutcome.fromWire(it.terminalOutcome) }
      ?.takeIf { it.isDurablyComplete }
  return terminal?.let {
    ReviewIntegrationPassOutcome(
      commitSequenceDigest = commitSequenceDigest,
      terminalOutcome = ReviewIntegrationTerminalOutcome.NO_OP_RESUME,
      summarizedLaneCount = 0,
    )
  }
}

private fun RuntimeOwnedPersistenceBoundary.recordIntegrationBoundary(
  reviewRunId: String?,
  outcome: ReviewIntegrationPassOutcome,
) {
  if (reviewRunId == null) return
  requiredWrite(
    seam = "ParallelCodeReviewRunner.recordIntegrationBoundary",
    expected = "runtime-owned review integration result",
  ) { unitOfWork ->
    unitOfWork.reviews.recordIntegrationPass(
      reviewRunId,
      ReviewIntegrationPassRecord(outcome.commitSequenceDigest, outcome.terminalOutcome.wireValue),
    )
  }
}

private fun RuntimeOwnedPersistenceBoundary.durablyCompleteLanes(
  initial: ParallelCodeReviewInitialRun,
  packet: ReviewContextPacket,
  ranThisPass: List<ReviewLaneAggregationInput>,
): List<ReviewLaneAggregationInput> {
  val reviewRunId = initial.request.reviewRunId ?: return emptyList()
  val alreadyRan = ranThisPass.map { it.lane }.toSet()
  val notRun = packet.selectedLanes.filterNot { it in alreadyRan }
  if (notRun.isEmpty()) return emptyList()
  val completeSkills =
    requiredRead(
      seam = "ParallelCodeReviewRunner.durablyCompleteLanes",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
      .filter { it.reviewDisposition == ReviewLaneReviewDisposition.COMPLETE }
      .map { it.laneSkillName }
      .toSet()
  return notRun.filter { it.substringAfter(':') in completeSkills }.map { lane ->
    ReviewLaneAggregationInput(
      lane = lane,
      commitSequenceDigest = packet.commitSequenceDigest,
      disposition = ReviewLaneReviewDisposition.COMPLETE,
    )
  }
}

private fun evidenceBoundaryAccountings(
  outcomes: ParallelReviewLaneRunResult,
): List<ReviewEvidenceBoundaryAccounting> = listOfNotNull(laneEvidenceBoundary(outcomes.lane1))

private fun laneEvidenceBoundary(outcome: ParallelReviewLaneOutcome): ReviewEvidenceBoundaryAccounting? {
  if (outcome.accounting?.terminalStatus == UNSUPPORTED_PROVIDER_TERMINAL_STATUS.wireValue) return null
  if (outcome.accounting == null && outcome.unboundSeam == null) return null
  val accounting = outcome.accounting
  return ReviewEvidenceBoundaryAccounting(
    governedLaunchCount =
      if (
        accounting != null &&
        accounting.terminalStatus != NO_OP_RESUME_TERMINAL_STATUS.wireValue
      ) {
        1
      } else {
        0
      },
    authorizedReadCount = accounting?.authorizedReadCount ?: 0,
    refusedOperationCount = accounting?.refusedOperationCount ?: 0,
    refusedCategories = accounting?.refusals.orEmpty().map { it.category },
    evidenceBytes = accounting?.evidenceBytes ?: 0,
    expansionCount = accounting?.expansions?.size ?: 0,
    rejectedCandidateCount = outcome.rejectedCandidateCount,
    unboundSeam = outcome.unboundSeam,
  )
}

internal fun parallelAccountingSummary(outcomes: ParallelReviewLaneRunResult): ReviewAccountingSummary? {
  val accountedLanes = listOf(outcomes.lane1)
  val specialists = accountedLanes.flatMap { it.specialistAccounting }
  if (specialists.isEmpty()) return null

  fun ReviewLaneAccounting.toInput() =
    ReviewAccountingInput(
      lane = lane,
      assignmentDigest = assignmentDigest,
      counters =
        ReviewAccountingCounters(
          launchBytes,
          evidenceBytes,
          resultBytes,
          expansions.size,
          toolCalls,
          modelTurns,
        ),
      terminalOutcome =
        ReviewAccountingTerminalOutcome.fromWire(terminalStatus)
          ?: ReviewAccountingTerminalOutcome.FAILED,
      bundleCompositionDigest = bundleCompositionDigest,
      segmentAccounting = segmentAccounting,
      unreviewedSegmentIds = unreviewedSegmentIds,
    )
  val roots =
    accountedLanes.mapIndexed { index, outcome ->
      ReviewAccountingInput(
        lane = "parallel-agent-${index + 1}",
        assignmentDigest = sha256HexUtf8("parallel-agent-${index + 1}"),
        children = outcome.specialistAccounting.map { it.toInput() },
        terminalOutcome =
          requireNotNull(
            ReviewAccountingTerminalOutcome.fromWire(parallelReviewLaneTerminalOutcome(outcome)),
          ) {
            "Unknown parallel review terminal outcome."
          },
        bundleCompositionDigest = outcome.bundleCompositionDigest,
        segmentAccounting = outcome.segmentAccounting,
        unreviewedSegmentIds = outcome.unreviewedSegmentIds,
      )
    }
  return ReviewTreeAccounting.summarize(
    reviewId = specialists.first().reviewId,
    packetDigest = specialists.first().packetDigest,
    root = ReviewAccountingInput("parallel-review", sha256HexUtf8("parallel-review"), children = roots),
  )
}

internal fun ReviewAccountingSummary.withCommitFocusedAccounting(
  packet: ReviewContextPacket?,
  budget: ReviewContextBudgetPolicy,
  integration: ReviewIntegrationPassOutcome,
  coverage: ReviewCoverageReport?,
): ReviewAccountingSummary {
  if (packet == null) return this
  val matrix = packet.routingMatrix
  val focusedCommits = matrix.decisions.filter { it.focused }.map { it.commitSha }.distinct()
  return copy(
    commitRouting =
      ReviewCommitRoutingAccounting(
        commitSequenceDigest = packet.commitSequenceDigest,
        routingDigest = matrix.routingDigest,
        commitCount = matrix.commitShas.size,
        laneCount = matrix.lanes.size,
        focusedCommitCount = focusedCommits.size,
        skippedCommitCount = matrix.commitShas.size - focusedCommits.size,
        focusedPairCount = matrix.focusedPairCount,
        skippedPairCount = matrix.analyzedPairCount - matrix.focusedPairCount,
        incompleteLanes = coverage?.incompleteLanes?.map { it.lane }?.sorted().orEmpty(),
      ),
    parentAnalysis =
      ReviewParentAnalysisConsumption(
        analyzedPairs = matrix.analyzedPairCount,
        analyzedBytes = matrix.canonical.toByteArray(Charsets.UTF_8).size.toLong(),
        maxAnalysisPairs = budget.maxRoutingAnalysisPairs,
        maxAnalysisBytes = budget.maxRoutingAnalysisBytes,
      ),
    integration =
      ReviewIntegrationAccounting(
        commitSequenceDigest = integration.commitSequenceDigest,
        terminalOutcome = integration.terminalOutcome.wireValue,
        summarizedLaneCount = integration.summarizedLaneCount,
        findingCount = integration.findings.size,
        counters =
          ReviewAccountingCounters(
            launchBytes = integration.launchBytes,
            resultBytes = integration.resultBytes,
            modelTurns = integration.modelTurns,
          ),
        skipReason =
          integration.skipReason?.takeIf { it.isNotBlank() }
            ?: coverage?.integrationNotApplicableReason?.takeIf { it.isNotBlank() }
            ?: "the review compiled commit routing without recording why integration was not applicable"
              .takeIf {
                integration.terminalOutcome == ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE
              },
      ),
  )
}

internal fun parallelReviewLaneTerminalOutcome(outcome: ParallelReviewLaneOutcome): String =
  when {
    outcome.reviewDisposition == ReviewLaneReviewDisposition.INCOMPLETE -> "incomplete"
    outcome.success -> "completed"
    else -> "partial_failure"
  }
