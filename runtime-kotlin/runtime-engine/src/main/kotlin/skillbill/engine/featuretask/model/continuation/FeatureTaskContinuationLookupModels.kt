package skillbill.engine.featuretask.model.continuation

import skillbill.application.continuation.model.GoalContinuationCandidate
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode

sealed interface FeatureTaskContinuationLookupResult {
  data object NoMatch : FeatureTaskContinuationLookupResult

  data class Resumable(val candidate: FeatureTaskContinuationCandidate) : FeatureTaskContinuationLookupResult

  data class AlreadyRunning(val candidate: FeatureTaskContinuationCandidate) : FeatureTaskContinuationLookupResult

  data class Ambiguous(val candidates: List<FeatureTaskContinuationCandidate>) : FeatureTaskContinuationLookupResult

  data class TerminalOnly(val candidates: List<FeatureTaskContinuationCandidate>) : FeatureTaskContinuationLookupResult

  /**
   * A prepared goal for this issue already owns durable state. Goal parents are not feature-task
   * workflows — they carry no execution identity and are excluded from the standalone candidate
   * scan — so without this variant every goal-orchestrated feature looked like unstarted work and
   * the caller's continuation gate could never fire.
   */
  data class GoalContinuation(val candidate: GoalContinuationCandidate) : FeatureTaskContinuationLookupResult

  /**
   * A matching feature-task row exists but has no immutable execution identity. Lookup must name the
   * row and point the operator at `feature-task repair-identity` instead of throwing out of the gate.
   */
  data class NeedsIdentityRepair(
    val workflowId: String,
    val summary: String,
  ) : FeatureTaskContinuationLookupResult
}

data class FeatureTaskContinuationCandidate(
  val executionIdentity: FeatureTaskExecutionIdentity,
  val workflowId: String,
  val mode: FeatureTaskWorkflowMode,
  val status: String,
  val currentStep: String,
  val governedSpecPath: String,
  val updatedAt: String?,
  val liveness: FeatureTaskContinuationLiveness?,
  val summary: String,
)

data class FeatureTaskContinuationLiveness(
  val classification: String,
  val lastEvidenceAt: String?,
  val evidence: String,
)
