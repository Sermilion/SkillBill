package skillbill.application.review.model

import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.ports.agentrun.model.READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.review.context.model.accounting.ReviewAccountingSummary
import skillbill.review.context.model.accounting.ReviewBudgetOutcome
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.context.model.hunk.ReviewBaselineUntrackedPolicy
import skillbill.review.context.model.packet.ReviewLaneCompletionState
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ReviewCoverageReport
import skillbill.review.model.ReviewFindingCitationDiagnosticWithFinding
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.model.ReviewStageResumeReport
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class ParallelCodeReviewRequest(
  val agent1Id: String,
  val scope: ParallelReviewScope,
  val repoRoot: Path,
  val timeout: Duration?,
  val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  val resolvedTier: CodeReviewExecutionMode? = null,
  val suppliedDiff: String? = null,
  val suppliedDiffPath: Path? = null,
  val reviewRunId: String? = null,
  val reviewSessionId: String? = null,
  val activityWorkflowId: String? = null,
  val activityParentWorkflowId: String? = null,
  val baseRevision: String? = null,
  val headRevision: String? = null,
  val prelaunchExpansions: List<ReviewPrelaunchExpansion> = emptyList(),
  val baselineUntrackedPolicy: ReviewBaselineUntrackedPolicy = ReviewBaselineUntrackedPolicy.EMPTY,
  val ownedPathspec: List<String> = emptyList(),
  val specPath: Path? = null,
  val selectedAgentAddonsSection: String = "",
  val laneProgressIdleTimeout: Duration = READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES.minutes,
) {
  init {
    reviewRunId?.let { require(it.isNotBlank()) { "reviewRunId must be non-blank when provided." } }
    reviewSessionId?.let { require(it.isNotBlank()) { "reviewSessionId must be non-blank when provided." } }
    activityWorkflowId?.let {
      require(it.isNotBlank()) { "activityWorkflowId must be non-blank when provided." }
    }
    activityParentWorkflowId?.let {
      require(it.isNotBlank()) { "activityParentWorkflowId must be non-blank when provided." }
    }
    specPath?.let { require(it.toString().isNotBlank()) { "specPath must be non-blank when provided." } }
    baseRevision?.let { require(it.isNotBlank()) { "baseRevision must be non-blank when provided." } }
    headRevision?.let { require(it.isNotBlank()) { "headRevision must be non-blank when provided." } }
    require(ownedPathspec.all(String::isNotBlank)) { "ownedPathspec must not contain blanks." }
    require(suppliedDiff == null || suppliedDiffPath == null) {
      "suppliedDiff and suppliedDiffPath cannot both be provided."
    }
    require((baseRevision == null) == (headRevision == null)) {
      "baseRevision and headRevision must be supplied together."
    }
    require((suppliedDiff == null && suppliedDiffPath == null) || baseRevision != null) {
      "A supplied diff requires paired baseRevision and headRevision immutable identities."
    }
    require(scope != ParallelReviewScope.WORKTREE_FROM_BASE || baseRevision != null) {
      "WORKTREE_FROM_BASE requires paired baseRevision and headRevision."
    }
    resolvedTier?.let { tier ->
      require(tier != CodeReviewExecutionMode.AUTO) {
        "resolvedTier must be a concrete mode, got $tier."
      }
      require(codeReviewMode == CodeReviewExecutionMode.AUTO || codeReviewMode == tier) {
        "The resolved depth tier is ${tier.wireValue} but the requested mode is ${codeReviewMode.wireValue}."
      }
    }
  }

  fun withResolvedTier(tier: CodeReviewExecutionMode): ParallelCodeReviewRequest = copy(resolvedTier = tier)

  fun withSelectedAgentAddons(prompt: String): String {
    if (selectedAgentAddonsSection.isEmpty()) return prompt
    return prompt.trimEnd() + "\n\n" + selectedAgentAddonsSection
  }

  companion object {
    fun baselineUntrackedPolicy(
      includedPaths: List<String>,
      excludedPaths: List<String>,
    ) = ReviewBaselineUntrackedPolicy(includedPaths, excludedPaths)
  }
}

data class ReviewPrelaunchExpansion(
  val lane: String,
  val path: String,
  val reachabilityReason: String,
) {
  init {
    require(lane.isNotBlank() && path.isNotBlank() && reachabilityReason.isNotBlank()) {
      "A prelaunch expansion requires a lane, path, and non-blank reachability reason."
    }
  }
}

data class ParallelCodeReviewResult(
  val mergeResult: ParallelReviewMergeResult,
  val lane1: ParallelReviewLaneStatus,
  val accountingSummary: ReviewAccountingSummary? = null,
  val integration: ReviewIntegrationPassOutcome? = null,
  val coverage: ReviewCoverageReport? = null,
  val stageResume: ReviewStageResumeReport? = null,
  val citationDiagnostics: List<ReviewFindingCitationDiagnosticWithFinding> = emptyList(),
  val reviewSessionId: String? = null,
  val appliedLearnings: String? = null,
) {
  val output: String
    get() = mergeResult.output
}

data class ParallelReviewLaneStatus(
  val agentId: String,
  val success: Boolean,
  val failureReason: String? = null,
  val droppedCandidateDiagnostic: String? = null,
  val budgetOutcome: ReviewBudgetOutcome? = null,
  val accounting: ReviewLaneAccounting? = null,
  val specialistAccounting: List<ReviewLaneAccounting> = accounting?.let(::listOf) ?: emptyList(),
  val reviewDisposition: ReviewLaneReviewDisposition = ReviewLaneReviewDisposition.INCOMPLETE,
)

sealed interface ParallelCodeReviewPlanningFailure {
  val message: String

  data class UsageInvalid(override val message: String) : ParallelCodeReviewPlanningFailure

  data class StackUndetected(override val message: String) : ParallelCodeReviewPlanningFailure

  data class DiffUnresolved(override val message: String) : ParallelCodeReviewPlanningFailure
}

sealed interface ParallelCodeReviewPlanned<out T> {
  data class Ready<T>(val value: T) : ParallelCodeReviewPlanned<T>

  data class Failed(val failure: ParallelCodeReviewPlanningFailure) : ParallelCodeReviewPlanned<Nothing>
}

sealed interface ParallelCodeReviewRunOutcome {
  data class Reviewed(val result: ParallelCodeReviewResult) : ParallelCodeReviewRunOutcome

  data class PlanningFailed(val failure: ParallelCodeReviewPlanningFailure) : ParallelCodeReviewRunOutcome
}

internal data class ReviewLaneIntegrationInput(
  val launch: ReviewSpecialistLaunchRequest,
  val completion: ReviewLaneCompletionState,
  val findingCount: Int,
)
