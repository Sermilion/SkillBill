package skillbill.application.review.parallel.runner

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ReviewDelegatedStageLaunch
import skillbill.application.review.model.ReviewEvidenceReadCount
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.reviewevidence.SharedReviewEvidenceCommits
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.review.context.model.accounting.ReviewAccountingTerminalOutcome
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.execution.ResolvedReviewExecutionMode
import skillbill.review.context.model.execution.SpecIntentResolution
import skillbill.review.context.model.hunk.ReviewLearningsReference
import skillbill.review.context.model.launch.GovernedReviewLaunch
import skillbill.review.context.model.packet.LANE_EVIDENCE_BYTES_DIMENSION
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.context.model.packet.ReviewLaneAssembledBundle
import skillbill.review.context.model.packet.ReviewLaneCompletionState
import skillbill.review.context.model.packet.asFailedLaneRun
import skillbill.review.context.model.packet.withBrokerEvidenceRefusal
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ReviewCoverageReport
import skillbill.review.model.ReviewFindingCitationDiagnosticWithFinding
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.model.ReviewStageResumeReport
import skillbill.scaffold.model.PlatformManifest

internal data class LaunchParentLaneArgs(
  val agentId: String,
  val launchRequests: List<ReviewSpecialistLaunchRequest>,
  val routedManifests: List<PlatformManifest>,
  val budget: ReviewContextBudgetPolicy,
  val request: ParallelCodeReviewRequest,
  val modelOverride: String?,
)

internal data class LaunchedBoundParentArgs(
  val launch: ParallelCodeReviewInlineParentLaunch,
  val bound: ParallelCodeReviewGovernedEvidenceBind.Bound,
  val budget: ReviewContextBudgetPolicy,
  val request: ParallelCodeReviewRequest,
  val modelOverride: String?,
)

internal data class ParallelResultArgs(
  val agent1Id: String,
  val outcomes: ParallelReviewLaneRunResult,
  val integration: ReviewIntegrationPassOutcome,
  val coverage: ReviewCoverageReport?,
  val packet: ReviewContextPacket?,
  val budget: ReviewContextBudgetPolicy,
  val stageResume: ReviewStageResumeReport?,
)

internal data class PlanningPrepareArgs(
  val request: ParallelCodeReviewRequest,
  val revisions: Pair<String, String>,
  val diffText: String,
  val evidence: ReviewDiffEvidence,
  val sharedSequence: SharedReviewEvidenceCommits,
  val routedManifests: List<PlatformManifest>,
  val manifests: List<PlatformManifest>,
  val ownedPathsBySlug: Map<String, Set<String>>,
  val agentIds: List<String>,
  val budget: ReviewContextBudgetPolicy,
  val evidenceStorePath: String?,
  val learningsReferences: List<ReviewLearningsReference>,
)

internal data class ParallelCodeReviewInitialRun(
  val request: ParallelCodeReviewRequest,
  val detection: ParallelCodeReviewStackDetection,
  val resolvedMode: ResolvedReviewExecutionMode,
  val agent1Id: String,
  val preparedLaunchRequests: List<ReviewSpecialistLaunchRequest>,
  val compiledLaunchRequests: List<ReviewSpecialistLaunchRequest>,
  val budget: ReviewContextBudgetPolicy,
  val specIntentResolution: SpecIntentResolution,
  val reviewSessionId: String,
  val appliedLearnings: String,
)

internal fun ParallelCodeReviewInitialRun.delegatedStageLaunch(): ReviewDelegatedStageLaunch =
  ReviewDelegatedStageLaunch(
    budget = budget,
    brokerId = agent1Id,
    repoRoot = request.repoRoot,
    timeout = request.timeout,
    promptSuffix = request.selectedAgentAddonsSection,
    laneProgressIdleTimeout = request.laneProgressIdleTimeout,
  )

internal data class ParallelCodeReviewCompiledLaunches(
  val all: List<ReviewSpecialistLaunchRequest>,
  val toRun: List<ReviewSpecialistLaunchRequest>,
  val specIntentResolution: SpecIntentResolution,
)

internal data class ParallelCodeReviewStackDetection(
  val routed: List<PlatformManifest>,
  val manifests: List<PlatformManifest>,
  val ownedPathsBySlug: Map<String, Set<String>>,
)

internal data class ParallelCodeReviewSoftRegisterAdmission(
  val findings: List<ParallelReviewRawFinding>,
  val droppedCandidateDiagnostic: String?,
  val rejectedCandidateCount: Int,
  val citationDiagnostics: List<ReviewFindingCitationDiagnosticWithFinding> = emptyList(),
)

internal class ParallelCodeReviewInlineParentLaunch(
  val agentId: String,
  val selected: List<ReviewSpecialistLaunchRequest>,
  val prompt: String,
  val bundleState: ReviewLaneCompletionState,
) {
  val assignment: ReviewAssignment get() = selected.first().assignment
}

internal sealed class ParallelCodeReviewGovernedEvidenceBind {
  class Bound(
    val broker: ReviewEvidenceBroker,
    val endpoint: GovernedReviewEvidenceEndpointHandle,
    val evidenceReads: ReviewEvidenceReadCount,
  ) : ParallelCodeReviewGovernedEvidenceBind()

  class Unbound(
    val seam: String,
    val fault: ParallelCodeReviewGovernedEvidenceBindFault,
  ) : ParallelCodeReviewGovernedEvidenceBind()
}

internal enum class ParallelCodeReviewGovernedEvidenceBindFault(val wireValue: String) {
  CONSTRUCTION("construction"),
  PROTOCOL("protocol"),
  ENDPOINT("endpoint"),
}

internal fun parallelCodeReviewGovernedLaunchFor(request: ReviewSpecialistLaunchRequest): GovernedReviewLaunch =
  GovernedReviewLaunch(
    assignment = request.assignment,
    packet = request.packet,
    specialistContract = request.specialistContract,
    rubric = request.rubrics.first().body,
    brokerId = request.brokerId,
    budget = request.budget,
  )

internal fun parallelCodeReviewEffectiveCompletionState(
  launch: ReviewSpecialistLaunchRequest,
  outcomes: ParallelReviewLaneRunResult,
): ReviewLaneCompletionState {
  val governed = parallelCodeReviewGovernedLaunchFor(launch)
  val runCompletion =
    if (outcomes.lane1.success) {
      governed.completionState
    } else {
      governed.completionState.asFailedLaneRun(
        governed.assembledBundle.entries.map { "${it.commitSha}@${it.hunk.path}" },
      )
    }
  val assignedUnits =
    governed.assembledBundle.entries
      .map { "${it.commitSha}@${it.hunk.path}" }
      .toSet()
  val deniedUnits =
    listOf(outcomes.lane1)
      .flatMap { outcome ->
        val fromAccounting =
          (outcome.specialistAccounting + listOfNotNull(outcome.accounting))
            .filter { it.budgetDimension == LANE_EVIDENCE_BYTES_DIMENSION }
            .flatMap { it.unreviewedUnits }
        val fromOutcome =
          outcome.takeIf { it.budgetDimension == LANE_EVIDENCE_BYTES_DIMENSION }
            ?.unreviewedUnits
            .orEmpty()
        fromAccounting + fromOutcome
      }
      .filter { it in assignedUnits }
      .distinct()
  return if (deniedUnits.isEmpty()) {
    runCompletion
  } else {
    runCompletion.withBrokerEvidenceRefusal(deniedUnits)
  }
}

internal fun parallelCodeReviewBrokerEvidenceCompletionState(
  completion: ReviewLaneCompletionState,
  accounting: ReviewLaneAccounting,
): ReviewLaneCompletionState =
  if (accounting.budgetDimension == LANE_EVIDENCE_BYTES_DIMENSION) {
    completion.withBrokerEvidenceRefusal(accounting.unreviewedUnits)
  } else {
    completion
  }

internal fun parallelCodeReviewAggregateBundleCompletion(
  states: List<ReviewLaneCompletionState>,
): ReviewLaneCompletionState {
  if (states.isEmpty()) {
    return ReviewLaneCompletionState(
      disposition = ReviewLaneReviewDisposition.COMPLETE,
      bundleCompositionDigest = ReviewLaneAssembledBundle.EMPTY.compositionDigest,
      segments = emptyList(),
    )
  }
  val incomplete = states.filter { it.disposition == ReviewLaneReviewDisposition.INCOMPLETE }
  if (incomplete.isEmpty()) {
    return states.first()
  }
  return ReviewLaneCompletionState(
    disposition = ReviewLaneReviewDisposition.INCOMPLETE,
    bundleCompositionDigest = incomplete.first().bundleCompositionDigest,
    segments = incomplete.flatMap { it.segments }.distinctBy { it.segmentId },
    unreviewedSegmentIds = incomplete.flatMap { it.unreviewedSegmentIds }.distinct(),
    unreviewedUnits = incomplete.flatMap { it.unreviewedUnits }.distinct(),
    budgetDimension = incomplete.first().budgetDimension,
  )
}

internal const val PARALLEL_REVIEW_STDERR_EXCERPT_MAX_LENGTH = 120
internal const val PARALLEL_REVIEW_REGISTER_ABSENCE_EXCERPT_MAX_LENGTH = 800
internal const val PARALLEL_REVIEW_MAX_SUPPLIED_DIFF_BYTES = 1_000_000L
internal const val PARALLEL_REVIEW_FIRST_SOURCE_LINE = 1
internal const val PARALLEL_REVIEW_HEAD_REVISION = "HEAD"
internal const val PARALLEL_REVIEW_SHARED_EVIDENCE_WORKFLOW_ID = "code-review"
internal const val PARALLEL_REVIEW_NO_SEQUENCE_DIGEST = "no-commit-sequence"
internal const val PARALLEL_REVIEW_NO_FINDINGS_TOKEN = "NO_FINDINGS"

internal val NO_OP_RESUME_TERMINAL_STATUS = ReviewAccountingTerminalOutcome.NO_OP_RESUME
internal val UNSUPPORTED_PROVIDER_TERMINAL_STATUS = ReviewAccountingTerminalOutcome.UNSUPPORTED_PROVIDER
internal const val LANE_FINDING_PARSE_SEAM: String = "attributeLaneFindings"

internal const val PARALLEL_REVIEW_DELEGATED_DEPTH_DIRECTIVE: String =
  "Assign each routed rubric above to its own specialist worker over that rubric's owned paths. " +
    "Accept each specialist's raw return as-is with no shape check. Synthesize the final review " +
    "prose and verdict yourself from those returns."

internal const val PARALLEL_REVIEW_LEARNINGS_DIRECTIVE: String =
  "The driver resolved these learnings for this repository and review skill. Apply them as explicit " +
    "review context. A learning never suppresses an evidence-based correctness, security, or contract finding."

internal const val PARALLEL_REVIEW_DELEGATED_LEARNINGS_DIRECTIVE: String =
  "Pass this learnings section unchanged to every specialist worker you launch."
