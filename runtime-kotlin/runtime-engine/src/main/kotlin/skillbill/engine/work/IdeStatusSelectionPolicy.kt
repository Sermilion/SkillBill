package skillbill.engine.work

import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.engine.work.model.IdeStatusSelectionTier
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.idestatus.model.IdeStatusDurableWorkflowState
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import java.time.Duration
import java.time.Instant

object IdeStatusSelectionPolicy {
  private const val LIVE_RETENTION_HOURS = 24L
  private const val BLOCKED_RETENTION_HOURS = 24L
  private const val SETTLED_RETENTION_HOURS = 6L
  private val LIVE_COHORT_TIERS =
    setOf(
      IdeStatusSelectionTier.ACTIVE,
      IdeStatusSelectionTier.PAUSED,
      IdeStatusSelectionTier.BLOCKED,
    )

  val LIVE_RETENTION: Duration = Duration.ofHours(LIVE_RETENTION_HOURS)

  val BLOCKED_RETENTION: Duration = Duration.ofHours(BLOCKED_RETENTION_HOURS)

  val SETTLED_RETENTION: Duration = Duration.ofHours(SETTLED_RETENTION_HOURS)

  fun select(
    candidates: List<IdeStatusCandidate>,
    observedAt: Instant,
  ): IdeStatusCandidate? {
    val retained = withoutSupersededVerifyRuns(candidates.filter { retainedAt(it, observedAt) })
    val freshLive = retained.filter { candidate -> inFreshLiveCohort(candidate, observedAt) }
    val cohort = freshLive.ifEmpty { retained }
    cohort
      .filter { it.execution != null }
      .groupBy { it.execution?.statusStoreId to it.execution?.runSequence }
      .values
      .firstOrNull { group -> group.mapNotNull { it.execution?.executionId }.distinct().size > 1 }
      ?.let { conflict ->
        throw invalidWorkflowStateSchemaError(
          "IDE status execution sequence conflict: sequence=${conflict.first().execution?.runSequence}; " +
            "execution_ids=${conflict.joinToString { it.execution?.executionId.orEmpty() }}",
        )
      }
    return cohort.sortedWith(comparator(observedAt)).firstOrNull()
  }

  private fun withoutSupersededVerifyRuns(candidates: List<IdeStatusCandidate>): List<IdeStatusCandidate> {
    val latestVerifyRuns =
      candidates
        .filter { it.workflowFamily == IdeStatusWorkflowFamily.FEATURE_VERIFY && it.execution != null }
        .groupBy { it.execution?.statusStoreId to it.branchCorrelation }
        .values
        .map { runs -> runs.maxWith(::compareRunSequence) }
    return candidates.filter { candidate ->
      candidate.workflowFamily != IdeStatusWorkflowFamily.FEATURE_VERIFY ||
        candidate.execution == null ||
        latestVerifyRuns.any { it === candidate }
    }
  }

  private fun inFreshLiveCohort(
    candidate: IdeStatusCandidate,
    observedAt: Instant,
  ): Boolean {
    if (IdeStatusFreshnessClassifier.classify(candidate.updatedAt, observedAt) == IdeStatusFreshness.STALE) {
      return false
    }
    return when (candidate.selectionTier) {
      IdeStatusSelectionTier.ACTIVE, IdeStatusSelectionTier.PAUSED -> true
      IdeStatusSelectionTier.BLOCKED ->
        candidate.execution?.scope != IdeStatusExecutionScope.STANDALONE_PHASE
      IdeStatusSelectionTier.FAILED,
      IdeStatusSelectionTier.RECENTLY_TERMINAL,
      IdeStatusSelectionTier.IDLE,
      -> false
    }
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
    compareBy<IdeStatusCandidate> { it.execution?.runSequence == null }
      .thenComparator { left, right -> goalAuthoritativeLiveOverPausedNonGoal(left, right) }
      .thenComparator { left, right -> compareRunSequence(right, left) }
      .thenBy { freshnessKey(it, observedAt) }
      .thenBy { it.selectionTier.rank }
      .thenBy { if (it.isGoalAuthoritative) 0 else 1 }
      .thenByDescending { it.updatedAt }
      .thenBy { it.workflowId }

  private fun goalAuthoritativeLiveOverPausedNonGoal(
    left: IdeStatusCandidate,
    right: IdeStatusCandidate,
  ): Int {
    fun goalAuthoritativeOverPausedNonGoal(
      goal: IdeStatusCandidate,
      other: IdeStatusCandidate,
    ): Boolean =
      goal.isGoalAuthoritative &&
        goal.selectionTier in LIVE_COHORT_TIERS &&
        !other.isGoalAuthoritative &&
        other.selectionTier == IdeStatusSelectionTier.PAUSED
    val leftWins = goalAuthoritativeOverPausedNonGoal(left, right)
    val rightWins = goalAuthoritativeOverPausedNonGoal(right, left)
    return when {
      leftWins -> -1
      rightWins -> 1
      else -> 0
    }
  }

  private fun compareRunSequence(
    left: IdeStatusCandidate,
    right: IdeStatusCandidate,
  ): Int {
    val leftSequence = left.execution?.runSequence
    val rightSequence = right.execution?.runSequence
    if (leftSequence == null && rightSequence == null) return 0
    if (leftSequence == null) return -1
    if (rightSequence == null) return 1
    return when {
      leftSequence.length != rightSequence.length -> leftSequence.length.compareTo(rightSequence.length)
      leftSequence == rightSequence -> 0
      leftSequence > rightSequence -> 1
      else -> -1
    }
  }

  fun selectionTier(lifecycle: IdeStatusLifecycleState): IdeStatusSelectionTier =
    when (lifecycle) {
      IdeStatusLifecycleState.ACTIVE -> IdeStatusSelectionTier.ACTIVE
      IdeStatusLifecycleState.PAUSED -> IdeStatusSelectionTier.PAUSED
      IdeStatusLifecycleState.BLOCKED -> IdeStatusSelectionTier.BLOCKED
      IdeStatusLifecycleState.FAILED -> IdeStatusSelectionTier.FAILED
      IdeStatusLifecycleState.CANCELED, IdeStatusLifecycleState.TERMINAL -> IdeStatusSelectionTier.RECENTLY_TERMINAL
      IdeStatusLifecycleState.IDLE -> IdeStatusSelectionTier.IDLE
    }

  fun lifecycleFromDurableStateWire(currentState: String): IdeStatusLifecycleState? =
    IdeStatusPolicy.workflowLifecycle(currentState)

  fun lifecycleFromDurableStateWire(
    family: IdeStatusWorkflowFamily,
    currentState: String,
  ): IdeStatusLifecycleState? =
    if (family == IdeStatusWorkflowFamily.FEATURE_VERIFY &&
      currentState == IdeStatusDurableWorkflowState.PENDING.wireValue
    ) {
      IdeStatusLifecycleState.TERMINAL
    } else {
      lifecycleFromDurableStateWire(currentState)
    }
}

object IdeStatusFreshnessClassifier {
  private const val FRESH_WINDOW_MINUTES = 30L
  val FRESH_WINDOW: Duration = Duration.ofMinutes(FRESH_WINDOW_MINUTES)

  fun classify(
    updatedAt: Instant?,
    observedAt: Instant,
  ): IdeStatusFreshness {
    if (updatedAt == null) return IdeStatusFreshness.UNKNOWN
    val age = Duration.between(updatedAt, observedAt)
    if (age.isNegative) return IdeStatusFreshness.UNKNOWN
    return if (age <= FRESH_WINDOW) IdeStatusFreshness.FRESH else IdeStatusFreshness.STALE
  }
}
