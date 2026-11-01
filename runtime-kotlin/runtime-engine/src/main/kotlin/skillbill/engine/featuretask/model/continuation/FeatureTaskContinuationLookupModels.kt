package skillbill.engine.featuretask.model.continuation

import skillbill.application.continuation.model.GoalContinuationCandidate
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode

public sealed interface FeatureTaskContinuationLookupResult {
  public data object NoMatch : FeatureTaskContinuationLookupResult

  public data class Resumable(val candidate: FeatureTaskContinuationCandidate) : FeatureTaskContinuationLookupResult

  public data class AlreadyRunning(
    val candidate: FeatureTaskContinuationCandidate,
  ) : FeatureTaskContinuationLookupResult

  public data class TerminalOnly(
    val candidates: List<FeatureTaskContinuationCandidate>,
  ) : FeatureTaskContinuationLookupResult

  /**
   * A prepared goal for this issue already owns durable state. Goal parents are not feature-task
   * workflows — they carry no execution identity and are excluded from the standalone candidate
   * scan — so without this variant every goal-orchestrated feature looked like unstarted work and
   * the caller's continuation gate could never fire.
   */
  public data class GoalContinuation(val candidate: GoalContinuationCandidate) : FeatureTaskContinuationLookupResult

  /**
   * A matching feature-task row exists but has no immutable execution identity. Lookup must name the
   * row and point the operator at `feature-task repair-identity` instead of throwing out of the gate.
   */
  public data class NeedsIdentityRepair(
    public val workflowId: String,
    public val summary: String,
  ) : FeatureTaskContinuationLookupResult
}

public data class FeatureTaskContinuationCandidate(
  public val executionIdentity: FeatureTaskExecutionIdentity,
  public val workflowId: String,
  public val mode: FeatureTaskWorkflowMode,
  public val status: String,
  public val currentStep: String,
  public val governedSpecPath: String,
  public val updatedAt: String?,
  public val liveness: FeatureTaskContinuationLiveness?,
  public val summary: String,
)

public data class FeatureTaskContinuationLiveness(
  public val classification: String,
  public val lastEvidenceAt: String?,
  public val evidence: String,
)
