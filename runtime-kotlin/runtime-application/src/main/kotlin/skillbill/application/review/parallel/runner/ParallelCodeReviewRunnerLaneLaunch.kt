package skillbill.application.review.parallel.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.getOrElseUnlessCooperative
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ReviewEvidenceReadCount
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.application.review.model.boundedReviewLane
import skillbill.application.review.packet.ReviewLocatorHunkBodyExtractor
import skillbill.application.review.parallel.verification.ParallelCodeReviewRunnerFailureAdmission
import skillbill.application.review.parallel.verification.parallelCodeReviewCaptureLane
import skillbill.application.review.parallel.verification.parallelCodeReviewInlineTerminalStatus
import skillbill.application.review.parallel.verification.parallelCodeReviewNoOpResumeOutcome
import skillbill.goalrunner.terminalStatus
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.evidence.ReviewEvidenceBrokerFactory
import skillbill.ports.review.launch.ReviewLaunchAgentStagingPort
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewLaunchAgentStagingRequest
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.review.context.model.accounting.ReviewAccountingTerminalOutcome
import skillbill.review.context.model.accounting.ReviewBudgetEvaluator
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.accounting.ReviewLaneIdentity
import skillbill.review.context.model.bundle.ReviewLaneBundle
import skillbill.review.context.model.bundle.ReviewLaneBundleEntry
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.context.model.packet.ReviewLaneCompletionState
import skillbill.review.context.model.packet.asFailedLaneRun
import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import java.nio.file.Path

@Inject
class ParallelCodeReviewRunnerLaneLaunch(
  private val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  private val reviewEvidenceBrokerFactory: ReviewEvidenceBrokerFactory,
  private val governedEvidenceEndpointBinder: GovernedReviewEvidenceEndpointBinder,
  private val reviewLaunchAgentStaging: ReviewLaunchAgentStagingPort,
  private val sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort?,
  private val failureAdmission: ParallelCodeReviewRunnerFailureAdmission,
  private val activityStampWriter: AgentActivityStampWriter,
) {
  internal fun runLanes(initial: ParallelCodeReviewInitialRun): ParallelReviewLaneRunResult {
    val request = initial.request
    val byAgent = initial.preparedLaunchRequests.groupBy { it.agentId }
    val lane1 =
      parallelCodeReviewCaptureLane {
        launchParentLane(
          LaunchParentLaneArgs(
            agentId = initial.agent1Id,
            launchRequests = byAgent[initial.agent1Id].orEmpty(),
            routedManifests = initial.detection.routed,
            budget = initial.budget,
            request = request,
            modelOverride = null,
          ),
        )
      }
    return ParallelReviewLaneRunResult(lane1 = lane1)
  }

  private fun launchParentLane(args: LaunchParentLaneArgs): ParallelReviewLaneOutcome {
    if (args.launchRequests.isEmpty()) return parallelCodeReviewNoOpResumeOutcome(args.agentId)
    val selected = args.launchRequests.sortedBy { it.assignment.laneDecision.orderIndex }
    val bundleStates = selected.map(::parallelCodeReviewGovernedLaunchFor).map { it.completionState }
    val launch =
      ParallelCodeReviewInlineParentLaunch(
        agentId = args.agentId,
        selected = selected,
        prompt =
          ParallelCodeReviewRunnerParentPrompt.build(
            selected,
            args.routedManifests,
            args.agentId,
          ),
        bundleState = parallelCodeReviewAggregateBundleCompletion(bundleStates),
      )
    return when (val bound = bindGovernedEvidence(selected, args.request)) {
      is ParallelCodeReviewGovernedEvidenceBind.Unbound -> unboundParentOutcome(launch, bound)
      is ParallelCodeReviewGovernedEvidenceBind.Bound ->
        launchedBoundParent(
          LaunchedBoundParentArgs(
            launch = launch,
            bound = bound,
            budget = args.budget,
            request = args.request,
            modelOverride = args.modelOverride,
          ),
        )
    }
  }

  private fun launchedBoundParent(args: LaunchedBoundParentArgs): ParallelReviewLaneOutcome =
    args.bound.endpoint.use {
      if (args.launch.agentId == "cursor") {
        reviewLaunchAgentStaging.stage(
          ReviewLaunchAgentStagingRequest(
            agentId = args.launch.agentId,
            reviewLaunchDirectory = args.bound.endpoint.descriptor.mcpConfigPath.parent,
            logicalWorkerNames =
              args.launch.selected
                .filter { it.workerKind == ReviewWorkerKind.PROVIDER_NATIVE }
                .mapNotNull { it.logicalWorkerName }
                .distinct(),
          ),
        )
      }
      val outcome =
        parentReviewLauncher.launch(
          GoalRunnerSubtaskLaunchRequest(
            invokedAgentId = args.launch.agentId,
            configuredAgentOverrideId = null,
            skillRunRequest =
              SkillRunRequest(
                issueKey = "code-review",
                repoRoot = args.request.repoRoot,
                timeout = args.request.timeout,
                promptOverride = args.request.withSelectedAgentAddons(args.launch.prompt),
                modelOverride = args.modelOverride,
                reviewEvidenceBroker = args.bound.broker,
                reviewEvidenceEndpoint = args.bound.endpoint,
                reviewFanOut = true,
              ).boundedReviewLane(args.request.laneProgressIdleTimeout, args.bound.evidenceReads),
          ),
        )
      when (outcome) {
        is UnsupportedAgentRunLaunch -> unsupportedParentOutcome(args.launch, outcome)
        is AgentRunLaunchDenied -> error("Parallel code review lane launch never carries a spawn authorization.")
        is AgentRunLaunchFacts -> launchedParentOutcome(args.launch, outcome, args.budget, args.bound.broker)
      }
    }

  private fun bindGovernedEvidence(
    selected: List<ReviewSpecialistLaunchRequest>,
    request: ParallelCodeReviewRequest,
  ): ParallelCodeReviewGovernedEvidenceBind {
    val broker =
      runCatching { parentEvidenceBroker(selected, request.repoRoot) }
        .getOrElseUnlessCooperative {
          return ParallelCodeReviewGovernedEvidenceBind.Unbound(
            ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
            ParallelCodeReviewGovernedEvidenceBindFault.CONSTRUCTION,
          )
        }
    return runCatching {
      val evidenceReads = ReviewEvidenceReadCount()
      val onEvidenceRead = evidenceReadCallback(request, evidenceReads)
      ParallelCodeReviewGovernedEvidenceBind.Bound(
        broker,
        governedEvidenceEndpointBinder.bind(broker.accounting().lane, broker, onEvidenceRead),
        evidenceReads,
      )
    }.getOrElseUnlessCooperative {
      ParallelCodeReviewGovernedEvidenceBind.Unbound(
        ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
        ParallelCodeReviewGovernedEvidenceBindFault.ENDPOINT,
      )
    }
  }

  internal fun evidenceReadCallback(
    request: ParallelCodeReviewRequest,
    evidenceReads: ReviewEvidenceReadCount,
  ): () -> Unit {
    val workflowId = request.activityWorkflowId?.takeIf(String::isNotBlank)
    return {
      evidenceReads.increment()
      workflowId?.let { activityStampWriter.recordEvidenceRead(it, request.activityParentWorkflowId) }
    }
  }

  private fun unboundParentOutcome(
    launch: ParallelCodeReviewInlineParentLaunch,
    unbound: ParallelCodeReviewGovernedEvidenceBind.Unbound,
  ): ParallelReviewLaneOutcome {
    val bundleState = launch.bundleState
    return ParallelReviewLaneOutcome(
      success = false,
      rawOutput = "",
      failureReason = "governed evidence broker ${unbound.fault.wireValue} failed",
      accounting = inlineParentAccounting(launch, ReviewAccountingTerminalOutcome.FAILED, null, null),
      reviewDisposition = bundleState.disposition,
      bundleCompositionDigest = bundleState.bundleCompositionDigest,
      segmentAccounting = bundleState.segments,
      unreviewedSegmentIds = bundleState.unreviewedSegmentIds,
      budgetDimension = bundleState.budgetDimension,
      unreviewedUnits = bundleState.unreviewedUnits,
      unboundSeam = unbound.seam,
    )
  }

  private fun launchedParentOutcome(
    launch: ParallelCodeReviewInlineParentLaunch,
    outcome: AgentRunLaunchFacts,
    budget: ReviewContextBudgetPolicy,
    evidenceBroker: ReviewEvidenceBroker,
  ): ParallelReviewLaneOutcome {
    val bundleState = launch.bundleState
    val budgetOutcome =
      ReviewBudgetEvaluator.laneResultOutcome(
        ReviewLaneIdentity.of(launch.assignment),
        budget,
        outcome.stdout.toByteArray().size.toLong(),
      )
    val evidenceAccounting = evidenceBroker.accounting()
    val noEvidenceRead =
      evidenceAccounting.authorizedReadCount == 0 &&
        launch.selected.any { parallelCodeReviewGovernedLaunchFor(it).assembledBundle.entries.isNotEmpty() }
    val launchReason =
      budgetOutcome?.let { "${it.type}: ${it.budgetKind.wireValue} ${it.observedValue} > ${it.configuredLimit}" }
        ?: failureAdmission.laneFailureReason(outcome)
        ?: "Review worker returned without reading assigned evidence.".takeIf { noEvidenceRead }
    val evidenceCompletion = parallelCodeReviewBrokerEvidenceCompletionState(bundleState, evidenceAccounting)
    val completion =
      if (launchReason == null) {
        evidenceCompletion
      } else {
        evidenceCompletion.asFailedLaneRun(
          launch.selected.flatMap { selected ->
            parallelCodeReviewGovernedLaunchFor(
              selected,
            ).assembledBundle.entries.map { "${it.commitSha}@${it.hunk.path}" }
          },
        )
      }
    val softAdmission =
      if (launchReason == null) {
        failureAdmission.softAdmitFindings(outcome.stdout, launch)
      } else {
        ParallelCodeReviewSoftRegisterAdmission(emptyList(), null, 0, emptyList())
      }
    return ParallelReviewLaneOutcome(
      success = launchReason == null,
      rawOutput = outcome.stdout,
      failureReason = launchReason,
      droppedCandidateDiagnostic = softAdmission.droppedCandidateDiagnostic,
      budgetOutcome = budgetOutcome,
      accounting =
        inlineParentAccounting(
          launch,
          parallelCodeReviewInlineTerminalStatus(outcome, completion.disposition),
          outcome,
          evidenceAccounting,
          completion,
        ),
      findings = softAdmission.findings,
      reviewDisposition = completion.disposition,
      bundleCompositionDigest = completion.bundleCompositionDigest,
      segmentAccounting = completion.segments,
      unreviewedSegmentIds = completion.unreviewedSegmentIds,
      budgetDimension = completion.budgetDimension,
      unreviewedUnits = completion.unreviewedUnits,
      rejectedCandidateCount = softAdmission.rejectedCandidateCount,
      citationDiagnostics = softAdmission.citationDiagnostics,
    )
  }

  private fun parentEvidenceBroker(
    selected: List<ReviewSpecialistLaunchRequest>,
    repoRoot: Path,
  ): ReviewEvidenceBroker =
    reviewEvidenceBrokerFactory.brokerFor(parentBrokerBinding(selected, repoRoot, sharedEvidenceLocatorReader))
}

internal fun unsupportedParentOutcome(
  launch: ParallelCodeReviewInlineParentLaunch,
  outcome: UnsupportedAgentRunLaunch,
): ParallelReviewLaneOutcome {
  val bundleState = launch.bundleState
  return ParallelReviewLaneOutcome(
    success = false,
    rawOutput = "",
    failureReason = "unsupported agent: ${outcome.reason}",
    accounting = inlineParentAccounting(launch, UNSUPPORTED_PROVIDER_TERMINAL_STATUS, null, null),
    reviewDisposition = bundleState.disposition,
    bundleCompositionDigest = bundleState.bundleCompositionDigest,
    segmentAccounting = bundleState.segments,
    unreviewedSegmentIds = bundleState.unreviewedSegmentIds,
    budgetDimension = bundleState.budgetDimension,
    unreviewedUnits = bundleState.unreviewedUnits,
  )
}

internal fun inlineParentAccounting(
  launch: ParallelCodeReviewInlineParentLaunch,
  terminalStatus: ReviewAccountingTerminalOutcome,
  outcome: AgentRunLaunchFacts?,
  brokerAccounting: ReviewLaneAccounting?,
  completionState: ReviewLaneCompletionState = launch.bundleState,
) = ReviewLaneAccounting(
  lane = launch.agentId,
  reviewId = launch.assignment.reviewId,
  packetDigest = launch.assignment.packetDigest,
  assignmentDigest = launch.assignment.digest,
  launchBytes = launch.prompt.toByteArray(Charsets.UTF_8).size.toLong(),
  authorizedReadCount = brokerAccounting?.authorizedReadCount ?: 0,
  refusedOperationCount = brokerAccounting?.refusedOperationCount ?: 0,
  refusals = brokerAccounting?.refusals.orEmpty(),
  evidenceBytes = brokerAccounting?.evidenceBytes ?: 0,
  expansions = brokerAccounting?.expansions.orEmpty(),
  toolCalls = brokerAccounting?.toolCalls ?: 0,
  modelTurns = 1,
  resultBytes = outcome?.stdout?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0,
  terminalStatus = terminalStatus.wireValue,
  terminalOutcome = brokerAccounting?.terminalOutcome,
  reviewDisposition = completionState.disposition,
  bundleCompositionDigest = completionState.bundleCompositionDigest,
  segmentAccounting = completionState.segments,
  unreviewedSegmentIds = completionState.unreviewedSegmentIds,
  budgetDimension = completionState.budgetDimension,
  unreviewedUnits = completionState.unreviewedUnits,
)

private fun mergedBudget(selected: List<ReviewSpecialistLaunchRequest>): ReviewContextBudgetPolicy {
  val primary = selected.minByOrNull { it.assignment.laneDecision.orderIndex } ?: selected.first()
  return primary.budget.copy(
    maxLaneEvidenceBytes = selected.sumOf { it.budget.maxLaneEvidenceBytes },
    maxSpecialistToolCalls = primary.budget.maxSpecialistToolCalls * selected.size,
    maxAssignmentExpansions = primary.budget.maxAssignmentExpansions * selected.size,
  )
}

private fun mergedBundle(
  packet: ReviewContextPacket,
  assignedHunks: Set<String>,
): ReviewLaneBundle =
  ReviewLaneBundle(
    packet.commitUnits.sortedBy { it.orderIndex }.mapNotNull { unit ->
      unit.hunkIds.filter { it in assignedHunks }
        .takeIf { it.isNotEmpty() }
        ?.let { ReviewLaneBundleEntry(unit.commitSha, unit.orderIndex, it) }
    },
  )

private fun parentBrokerBinding(
  selected: List<ReviewSpecialistLaunchRequest>,
  repoRoot: Path,
  locatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort?,
): ReviewEvidenceBrokerBinding {
  val primary = selected.minByOrNull { it.assignment.laneDecision.orderIndex } ?: selected.first()
  if (selected.size == 1) return brokerBinding(primary, repoRoot, locatorReader)
  val assignedPaths = selected.flatMap { it.assignment.assignedPaths }.distinct()
  val assignedHunks = selected.flatMap { it.assignment.assignedHunks }.distinct()
  val expansions = selected.flatMap { it.assignment.expansions }.distinctBy { it.expansionId }
  val assigned = assignedHunks.toSet()
  val merged =
    primary.assignment.copy(
      laneRouting = emptyList(),
      assignedPaths = assignedPaths,
      assignedHunks = assignedHunks,
      assignedBundle = mergedBundle(primary.packet, assigned),
      evidenceTargets = selected.flatMap { it.assignment.evidenceTargets }.distinctBy { it.targetId },
      dependencyAllowlist =
        ReviewDependencyAllowlist(
          selected.flatMap { it.assignment.dependencyAllowlist.normalized }
            .distinct()
            .filterNot { it in assignedPaths.toSet() },
        ),
      expansions = expansions,
    )
  return ReviewEvidenceBrokerBinding(
    repoRoot = repoRoot,
    assignment = merged,
    laneRubricId = primary.rubrics.first().rubricId,
    budget = mergedBudget(selected),
    namedDependencies = selected.flatMap { it.namedDependencies }.toSet(),
    trustedExpansionLedger = expansions,
    projectedHunks = primary.packet.changedHunks.filter { it.hunkId in assigned },
    locatorReader = locatorReader,
    bodyExtractor = ReviewLocatorHunkBodyExtractor,
  )
}

private fun brokerBinding(
  launch: ReviewSpecialistLaunchRequest,
  repoRoot: Path,
  locatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort?,
): ReviewEvidenceBrokerBinding {
  val assigned = launch.assignment.assignedHunks.toSet()
  return ReviewEvidenceBrokerBinding(
    repoRoot = repoRoot,
    assignment = launch.assignment,
    laneRubricId = launch.rubrics.first().rubricId,
    budget = launch.budget,
    namedDependencies = launch.namedDependencies,
    trustedExpansionLedger = launch.assignment.expansions,
    projectedHunks = launch.packet.changedHunks.filter { it.hunkId in assigned },
    locatorReader = locatorReader,
    bodyExtractor = ReviewLocatorHunkBodyExtractor,
  )
}
