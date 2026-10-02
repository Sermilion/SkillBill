package skillbill.application.review.model

import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.withBoundedLaneProgress
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.execution.ResolvedReviewExecutionMode
import skillbill.review.context.model.execution.SpecIntentProjection
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingVerdict
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal data class ReviewDelegatedStageLaunch(
  val budget: ReviewContextBudgetPolicy,
  val brokerId: String,
  val repoRoot: Path,
  val timeout: Duration?,
  val modelOverride: String? = null,
  val promptSuffix: String = "",
  val laneProgressIdleTimeout: Duration = READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES.minutes,
)

internal fun SkillRunRequest.boundedReviewLane(
  bound: Duration,
  evidenceReads: ReviewEvidenceReadCount? = null,
): SkillRunRequest =
  withBoundedLaneProgress(
    bound,
    evidenceReads?.let { reads -> AgentRunProgressProbe { reads.current().toString() } } ?: AgentRunProgressProbe.NONE,
  )

internal data class ReviewClaimVerificationRunRequest(
  val packet: ReviewContextPacket?,
  val reviewOutput: String = "",
  val findings: List<ParallelReviewMergedFinding>,
  val existingVerdicts: List<ReviewFindingVerdict>,
  val mode: ResolvedReviewExecutionMode,
  val launch: ReviewDelegatedStageLaunch,
)

internal data class ReviewSpecAdjudicationRunRequest(
  val packet: ReviewContextPacket?,
  val findings: List<ParallelReviewMergedFinding>,
  val existingVerdicts: List<ReviewFindingVerdict>,
  val projection: SpecIntentProjection?,
  val launch: ReviewDelegatedStageLaunch,
)

internal data class ReviewIntegrationPassRunRequest(
  val packet: ReviewContextPacket,
  val lanes: List<ReviewLaneIntegrationInput>,
  val launch: ReviewDelegatedStageLaunch,
)
