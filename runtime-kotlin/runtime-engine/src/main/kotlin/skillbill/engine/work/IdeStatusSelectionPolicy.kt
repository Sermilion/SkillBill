package skillbill.engine.work

import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.engine.work.model.IdeStatusSelectionTier
import skillbill.ports.idestatus.model.IdeStatusFreshness
import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import java.time.Duration
import java.time.Instant

object IdeStatusSelectionPolicy {
  private const val LIVE_RETENTION_HOURS = 24L
  private const val BLOCKED_RETENTION_HOURS = 24L
  private const val SETTLED_RETENTION_HOURS = 6L

  val LIVE_RETENTION: Duration = Duration.ofHours(LIVE_RETENTION_HOURS)

  val BLOCKED_RETENTION: Duration = Duration.ofHours(BLOCKED_RETENTION_HOURS)

  val SETTLED_RETENTION: Duration = Duration.ofHours(SETTLED_RETENTION_HOURS)

  fun select(
    candidates: List<IdeStatusCandidate>,
    observedAt: Instant,
  ): IdeStatusCandidate? {
    val retained = candidates.filter { retainedAt(it, observedAt) }
    if (retained.isEmpty()) return null
    return retained.sortedWith(comparator(observedAt)).first()
  }

  private fun freshnessKey(
    candidate: IdeStatusCandidate,
    observedAt: Instant,
  ): Int =
    if (IdeStatusFreshnessClassifier.classify(candidate.updatedAt, observedAt) == IdeStatusFreshness.STALE) 1 else 0

  fun retainedAt(
    candidate: IdeStatusCandidate,
    observedAt: Instant,
  ): Boolean {
    val ceiling =
      when (candidate.selectionTier) {
        IdeStatusSelectionTier.ACTIVE, IdeStatusSelectionTier.PAUSED -> LIVE_RETENTION
        IdeStatusSelectionTier.BLOCKED -> BLOCKED_RETENTION
        IdeStatusSelectionTier.FAILED,
        IdeStatusSelectionTier.RECENTLY_TERMINAL,
        -> SETTLED_RETENTION
        IdeStatusSelectionTier.IDLE -> return true
      }
    val age = Duration.between(candidate.updatedAt, observedAt)

    return age.isNegative || age <= ceiling
  }

  private fun comparator(observedAt: Instant): Comparator<IdeStatusCandidate> =
    compareBy<IdeStatusCandidate> { freshnessKey(it, observedAt) }
      .thenBy { it.selectionTier.rank }
      .thenBy { if (it.isGoalAuthoritative) 0 else 1 }
      .thenByDescending { it.updatedAt }
      .thenBy { it.workflowId }

  fun selectionTier(lifecycle: IdeStatusLifecycleState): IdeStatusSelectionTier =
    when (lifecycle) {
      IdeStatusLifecycleState.ACTIVE -> IdeStatusSelectionTier.ACTIVE
      IdeStatusLifecycleState.PAUSED -> IdeStatusSelectionTier.PAUSED
      IdeStatusLifecycleState.BLOCKED -> IdeStatusSelectionTier.BLOCKED
      IdeStatusLifecycleState.FAILED -> IdeStatusSelectionTier.FAILED
      IdeStatusLifecycleState.TERMINAL -> IdeStatusSelectionTier.RECENTLY_TERMINAL
      IdeStatusLifecycleState.IDLE -> IdeStatusSelectionTier.IDLE
    }

  internal fun lifecycleFromDurableState(currentState: IdeStatusDurableWorkflowState): IdeStatusLifecycleState =
    when (currentState) {
      IdeStatusDurableWorkflowState.RUNNING, IdeStatusDurableWorkflowState.PENDING -> IdeStatusLifecycleState.ACTIVE
      IdeStatusDurableWorkflowState.PAUSED -> IdeStatusLifecycleState.PAUSED
      IdeStatusDurableWorkflowState.BLOCKED -> IdeStatusLifecycleState.BLOCKED
      IdeStatusDurableWorkflowState.FAILED -> IdeStatusLifecycleState.FAILED
      IdeStatusDurableWorkflowState.COMPLETED,
      IdeStatusDurableWorkflowState.ABANDONED,
      IdeStatusDurableWorkflowState.COMPLETE,
      IdeStatusDurableWorkflowState.SKIPPED,
      -> IdeStatusLifecycleState.TERMINAL
    }

  fun lifecycleFromDurableStateWire(currentState: String): IdeStatusLifecycleState? =
    IdeStatusDurableWorkflowState.fromWire(currentState)?.let(::lifecycleFromDurableState)
}

internal enum class IdeStatusDurableWorkflowState(val wireValue: String) {
  PENDING("pending"),
  RUNNING("running"),
  PAUSED("paused"),
  BLOCKED("blocked"),
  FAILED("failed"),
  COMPLETED("completed"),
  ABANDONED("abandoned"),
  COMPLETE("complete"),
  SKIPPED("skipped"),
  ;

  companion object {
    fun fromWire(value: String): IdeStatusDurableWorkflowState? = entries.firstOrNull { it.wireValue == value }
  }
}
