package skillbill.idestatus.model

import skillbill.workflow.model.WorkflowStatus
import java.time.Duration
import java.time.Instant

enum class IdeStatusWorkflowFamily(val wireValue: String) {
  FEATURE_TASK_RUNTIME("feature-task-runtime"),
  FEATURE_VERIFY("feature-verify"),
  FEATURE_GOAL("feature-goal"),
}

enum class IdeStatusLifecycleState(val wireValue: String) {
  ACTIVE("active"),
  PAUSED(WorkflowStatus.PAUSED.wireValue),
  BLOCKED(WorkflowStatus.BLOCKED.wireValue),
  FAILED(WorkflowStatus.FAILED.wireValue),
  TERMINAL("terminal"),
  CANCELED("canceled"),
  IDLE("idle"),
}

enum class IdeStatusFreshness(val wireValue: String) {
  FRESH("fresh"),
  STALE("stale"),
  UNKNOWN("unknown"),
}

enum class IdeStatusExecutionScope(val wireValue: String) {
  WORKFLOW("workflow"),
  STANDALONE_PHASE("standalone_phase"),
}

enum class IdeStatusLaunchScope(val wireValue: String) {
  STANDALONE("standalone"),
  GOAL("goal"),
  ;

  companion object {
    fun fromWire(value: String): IdeStatusLaunchScope? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class IdeStatusDurableWorkflowState(val wireValue: String) {
  PENDING("pending"),
  RUNNING("running"),
  PAUSED("paused"),
  BLOCKED("blocked"),
  FAILED("failed"),
  COMPLETED("completed"),
  ABANDONED("abandoned"),
  TIMED_OUT("timed_out"),
  COMPLETE("complete"),
  SKIPPED("skipped"),
  ;

  companion object {
    fun fromWire(value: String): IdeStatusDurableWorkflowState? = entries.firstOrNull { it.wireValue == value }
  }
}

data class IdeStatusGoalLifecycleFacts(
  val durableLifecycle: IdeStatusLifecycleState,
  val completionRecorded: Boolean,
  val executionLive: Boolean,
  val pausedControl: Boolean,
)

data class IdeStatusWorkPolicyFacts(
  val lifecycle: IdeStatusLifecycleState,
  val family: IdeStatusWorkflowFamily?,
  val scope: IdeStatusExecutionScope?,
  val freshness: IdeStatusFreshness,
  val finishedAt: Instant?,
  val operatorFinalizedAt: Instant? = null,
  val awaitingOperatorDecision: Boolean,
  val planningPrepared: Boolean,
  val controlTargetPresent: Boolean,
  val pauseRequested: Boolean = false,
  val observedAt: Instant? = null,
  val launchScope: IdeStatusLaunchScope = IdeStatusLaunchScope.STANDALONE,
  val successfulGoalCompletion: Boolean = false,
)

enum class IdeStatusLiveSection { NEEDS_YOU, GOALS, PHASES, OPERATIONS }

enum class IdeStatusDisplayGroup { GOALS, PHASES, OPERATIONS }

data class IdeStatusWorkPolicy(
  val liveSection: IdeStatusLiveSection?,
  val controlEligible: Boolean,
  val pauseEligible: Boolean,
  val requiresAttention: Boolean,
  val displayGroup: IdeStatusDisplayGroup?,
  val displayPriority: Int,
  val running: Boolean,
  val recentlyFinishedPhase: Boolean,
  val doneWithinDay: Boolean,
  val membershipDeadline: Instant?,
)

enum class IdeStatusTrayStatus { FAILED, NEEDS_YOU, RUNNING, IDLE }

data class IdeStatusBoardEntry(
  val key: String,
  val repositoryIdentity: String,
  val lifecycle: IdeStatusLifecycleState,
  val policy: IdeStatusWorkPolicy,
)

data class IdeStatusBoardArrangement(
  val trayStatus: IdeStatusTrayStatus,
  val running: Int,
  val needsYou: Int,
  val doneWithinDay: Int,
  val goals: List<IdeStatusBoardEntry>,
  val livePhases: List<IdeStatusBoardEntry>,
  val finishedPhases: List<IdeStatusBoardEntry>,
  val liveOperations: List<IdeStatusBoardEntry>,
  val finishedOperations: List<IdeStatusBoardEntry>,
)

object IdeStatusPolicy {
  fun arrange(entries: List<IdeStatusBoardEntry>): IdeStatusBoardArrangement {
    val goals =
      entries.filter { it.policy.displayGroup == IdeStatusDisplayGroup.GOALS && it.policy.liveSection != null }
        .sortedWith(compareBy({ it.policy.displayPriority }, { it.key }, { it.repositoryIdentity }))
    val phases = entries.filter { it.policy.displayGroup == IdeStatusDisplayGroup.PHASES }
    val operations = entries.filter { it.policy.displayGroup == IdeStatusDisplayGroup.OPERATIONS }
    val running = entries.count { it.policy.running }
    val needsYou = entries.count { it.policy.requiresAttention }
    return IdeStatusBoardArrangement(
      trayStatus =
        when {
          entries.any { it.lifecycle == IdeStatusLifecycleState.FAILED } -> IdeStatusTrayStatus.FAILED
          needsYou > 0 -> IdeStatusTrayStatus.NEEDS_YOU
          running > 0 -> IdeStatusTrayStatus.RUNNING
          else -> IdeStatusTrayStatus.IDLE
        },
      running = running,
      needsYou = needsYou,
      doneWithinDay = entries.count { it.policy.doneWithinDay },
      goals = goals,
      livePhases = phases.filter { it.policy.liveSection != null },
      finishedPhases = phases.filter { it.policy.recentlyFinishedPhase },
      liveOperations = operations.filter { it.policy.liveSection != null },
      finishedOperations = operations.filter { it.policy.recentlyFinishedPhase },
    )
  }

  fun standaloneLifecycle(state: String): IdeStatusLifecycleState =
    when (state) {
      "active", "running" -> IdeStatusLifecycleState.ACTIVE
      "paused", "runner_interrupted" -> IdeStatusLifecycleState.PAUSED
      "blocked" -> IdeStatusLifecycleState.BLOCKED
      "failed" -> IdeStatusLifecycleState.FAILED
      "canceled" -> IdeStatusLifecycleState.CANCELED
      "terminal", "completed", "success" -> IdeStatusLifecycleState.TERMINAL
      else -> IdeStatusLifecycleState.FAILED
    }

  fun workflowLifecycle(state: String): IdeStatusLifecycleState? =
    IdeStatusDurableWorkflowState.fromWire(state)?.let { durable ->
      when (durable) {
        IdeStatusDurableWorkflowState.RUNNING, IdeStatusDurableWorkflowState.PENDING -> IdeStatusLifecycleState.ACTIVE
        IdeStatusDurableWorkflowState.PAUSED -> IdeStatusLifecycleState.PAUSED
        IdeStatusDurableWorkflowState.BLOCKED -> IdeStatusLifecycleState.BLOCKED
        IdeStatusDurableWorkflowState.FAILED -> IdeStatusLifecycleState.FAILED
        IdeStatusDurableWorkflowState.TIMED_OUT -> IdeStatusLifecycleState.FAILED
        IdeStatusDurableWorkflowState.ABANDONED -> IdeStatusLifecycleState.CANCELED
        IdeStatusDurableWorkflowState.COMPLETED,
        IdeStatusDurableWorkflowState.COMPLETE, IdeStatusDurableWorkflowState.SKIPPED,
        -> IdeStatusLifecycleState.TERMINAL
      }
    }

  fun verifyLifecycle(
    durable: IdeStatusLifecycleState,
    updatedAt: Instant,
    observedAt: Instant,
  ): IdeStatusLifecycleState =
    if (durable in VERIFY_OPEN_LIFECYCLES && Duration.between(updatedAt, observedAt) > VERIFY_ABANDONED_AFTER) {
      IdeStatusLifecycleState.IDLE
    } else {
      durable
    }

  fun goalLifecycle(facts: IdeStatusGoalLifecycleFacts): IdeStatusLifecycleState {
    if (facts.completionRecorded || facts.durableLifecycle == IdeStatusLifecycleState.TERMINAL) {
      return IdeStatusLifecycleState.TERMINAL
    }
    if (facts.durableLifecycle == IdeStatusLifecycleState.PAUSED && facts.executionLive && !facts.pausedControl) {
      return IdeStatusLifecycleState.ACTIVE
    }
    return when {
      facts.durableLifecycle != IdeStatusLifecycleState.ACTIVE -> facts.durableLifecycle
      facts.pausedControl -> IdeStatusLifecycleState.PAUSED
      !facts.executionLive -> IdeStatusLifecycleState.IDLE
      else -> IdeStatusLifecycleState.ACTIVE
    }
  }

  fun work(facts: IdeStatusWorkPolicyFacts): IdeStatusWorkPolicy {
    val goal = isGoal(facts)
    val controlEligible = controlEligible(facts, goal)
    val liveSection = liveSection(facts, goal)
    val displayGroup = displayGroup(facts, goal)
    val recent =
      windowed(
        facts.operatorFinalizedAt,
        RECENT_PHASE_WINDOW,
        displayGroup in setOf(IdeStatusDisplayGroup.PHASES, IdeStatusDisplayGroup.OPERATIONS),
        facts.observedAt,
      )
    val done =
      windowed(
        facts.finishedAt,
        DONE_WINDOW,
        facts.lifecycle == IdeStatusLifecycleState.TERMINAL && facts.successfulGoalCompletion,
        facts.observedAt,
      )
    return IdeStatusWorkPolicy(
      liveSection = liveSection,
      controlEligible = controlEligible,
      pauseEligible = controlEligible && !facts.pauseRequested,
      requiresAttention = requiresAttention(facts, goal),
      displayGroup = displayGroup,
      displayPriority = displayPriority(facts),
      running =
        liveSection in
          setOf(
            IdeStatusLiveSection.GOALS,
            IdeStatusLiveSection.PHASES,
            IdeStatusLiveSection.OPERATIONS,
          ),
      recentlyFinishedPhase = recent.within,
      doneWithinDay = done.within,
      membershipDeadline = listOfNotNull(recent.nextChange, done.nextChange).minOrNull(),
    )
  }

  private fun isGoal(facts: IdeStatusWorkPolicyFacts): Boolean =
    facts.family == IdeStatusWorkflowFamily.FEATURE_GOAL || facts.launchScope == IdeStatusLaunchScope.GOAL

  private fun controlEligible(
    facts: IdeStatusWorkPolicyFacts,
    goal: Boolean,
  ): Boolean {
    if (!goal || facts.scope == IdeStatusExecutionScope.STANDALONE_PHASE) {
      return false
    }
    if (!facts.controlTargetPresent || facts.lifecycle != IdeStatusLifecycleState.ACTIVE) {
      return false
    }
    return facts.freshness == IdeStatusFreshness.FRESH
  }

  private fun requiresAttention(
    facts: IdeStatusWorkPolicyFacts,
    goal: Boolean,
  ): Boolean {
    if (facts.lifecycle in setOf(IdeStatusLifecycleState.BLOCKED, IdeStatusLifecycleState.FAILED)) return true
    if (facts.awaitingOperatorDecision) return true
    if (goal && facts.lifecycle == IdeStatusLifecycleState.IDLE && facts.finishedAt == null) return true
    return !goal && facts.lifecycle == IdeStatusLifecycleState.TERMINAL && facts.operatorFinalizedAt == null
  }

  private fun windowed(
    anchor: Instant?,
    window: Duration,
    applies: Boolean,
    observedAt: Instant?,
  ): WindowedFact {
    if (!applies || anchor == null || observedAt == null) return WindowedFact(false, null)
    return when {
      anchor.isAfter(observedAt) -> WindowedFact(false, anchor)
      anchor.isBefore(observedAt.minus(window)) -> WindowedFact(false, null)
      else -> WindowedFact(true, anchor.plus(window).plusNanos(1))
    }
  }

  private data class WindowedFact(val within: Boolean, val nextChange: Instant?)

  private fun liveSection(
    facts: IdeStatusWorkPolicyFacts,
    goal: Boolean,
  ): IdeStatusLiveSection? {
    if (goal) {
      if (facts.finishedAt != null) return null
      return when (facts.lifecycle) {
        IdeStatusLifecycleState.BLOCKED, IdeStatusLifecycleState.FAILED, IdeStatusLifecycleState.PAUSED ->
          IdeStatusLiveSection.NEEDS_YOU
        IdeStatusLifecycleState.ACTIVE -> IdeStatusLiveSection.GOALS
        IdeStatusLifecycleState.IDLE -> IdeStatusLiveSection.NEEDS_YOU
        else -> null
      }
    }
    if (facts.operatorFinalizedAt != null) return null
    return when (facts.lifecycle) {
      IdeStatusLifecycleState.BLOCKED, IdeStatusLifecycleState.FAILED, IdeStatusLifecycleState.PAUSED,
      IdeStatusLifecycleState.TERMINAL,
      ->
        IdeStatusLiveSection.NEEDS_YOU
      IdeStatusLifecycleState.ACTIVE ->
        if (facts.family == IdeStatusWorkflowFamily.FEATURE_VERIFY) {
          IdeStatusLiveSection.OPERATIONS
        } else {
          IdeStatusLiveSection.PHASES
        }
      else -> null
    }
  }

  private fun displayPriority(facts: IdeStatusWorkPolicyFacts): Int =
    when {
      facts.lifecycle == IdeStatusLifecycleState.BLOCKED -> BLOCKED_PRIORITY
      facts.lifecycle == IdeStatusLifecycleState.FAILED -> FAILED_PRIORITY
      facts.lifecycle == IdeStatusLifecycleState.ACTIVE && facts.planningPrepared -> PLANNING_PRIORITY
      facts.lifecycle == IdeStatusLifecycleState.ACTIVE -> ACTIVE_PRIORITY
      facts.lifecycle == IdeStatusLifecycleState.PAUSED -> PAUSED_PRIORITY
      else -> OTHER_PRIORITY
    }

  private fun displayGroup(
    facts: IdeStatusWorkPolicyFacts,
    goal: Boolean,
  ): IdeStatusDisplayGroup? =
    if (goal) {
      IdeStatusDisplayGroup.GOALS
    } else if (facts.family == IdeStatusWorkflowFamily.FEATURE_VERIFY) {
      IdeStatusDisplayGroup.OPERATIONS
    } else {
      IdeStatusDisplayGroup.PHASES.takeIf {
        facts.family == IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME ||
          facts.scope == IdeStatusExecutionScope.STANDALONE_PHASE
      }
    }

  private const val VERIFY_ABANDONED_AFTER_HOURS = 24L
  val VERIFY_ABANDONED_AFTER: Duration = Duration.ofHours(VERIFY_ABANDONED_AFTER_HOURS)
  val VERIFY_OPEN_LIFECYCLES: Set<IdeStatusLifecycleState> =
    setOf(IdeStatusLifecycleState.ACTIVE, IdeStatusLifecycleState.PAUSED)
  private const val RECENT_PHASE_HOURS = 1L
  private const val DONE_HOURS = 24L
  val RECENT_PHASE_WINDOW: Duration = Duration.ofHours(RECENT_PHASE_HOURS)
  val DONE_WINDOW: Duration = Duration.ofHours(DONE_HOURS)

  private const val BLOCKED_PRIORITY = 0
  private const val FAILED_PRIORITY = 1
  private const val PLANNING_PRIORITY = 2
  private const val ACTIVE_PRIORITY = 3
  private const val PAUSED_PRIORITY = 4
  private const val OTHER_PRIORITY = 5
}
