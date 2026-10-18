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
  val awaitingOperatorDecision: Boolean,
  val planningPrepared: Boolean,
  val controlTargetPresent: Boolean,
  val pauseRequested: Boolean = false,
  val observedAt: Instant? = null,
  val launchScope: IdeStatusLaunchScope = IdeStatusLaunchScope.STANDALONE,
  val successfulGoalCompletion: Boolean = false,
)

enum class IdeStatusLiveSection { NEEDS_YOU, GOALS, PHASES }

enum class IdeStatusDisplayGroup { GOALS, PHASES }

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
)

object IdeStatusPolicy {
  fun arrange(entries: List<IdeStatusBoardEntry>): IdeStatusBoardArrangement {
    val goals =
      entries.filter { it.policy.displayGroup == IdeStatusDisplayGroup.GOALS && it.policy.liveSection != null }
        .sortedWith(compareBy({ it.policy.displayPriority }, { it.key }, { it.repositoryIdentity }))
    val phases = entries.filter { it.policy.displayGroup == IdeStatusDisplayGroup.PHASES }
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
    if (durable == IdeStatusLifecycleState.ACTIVE && Duration.between(updatedAt, observedAt) > VERIFY_ABANDONED_AFTER) {
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
    val goal = facts.family == IdeStatusWorkflowFamily.FEATURE_GOAL || facts.launchScope == IdeStatusLaunchScope.GOAL
    val controlEligible =
      goal && facts.scope != IdeStatusExecutionScope.STANDALONE_PHASE &&
        facts.controlTargetPresent && facts.lifecycle == IdeStatusLifecycleState.ACTIVE &&
        facts.freshness == IdeStatusFreshness.FRESH
    val liveSection = liveSection(facts, goal)
    val displayGroup = displayGroup(facts, goal)
    val recent = windowed(facts, RECENT_PHASE_WINDOW, displayGroup == IdeStatusDisplayGroup.PHASES)
    val done =
      windowed(
        facts,
        DONE_WINDOW,
        facts.lifecycle == IdeStatusLifecycleState.TERMINAL && facts.successfulGoalCompletion,
      )
    return IdeStatusWorkPolicy(
      liveSection = liveSection,
      controlEligible = controlEligible,
      pauseEligible = controlEligible && !facts.pauseRequested,
      requiresAttention =
        facts.lifecycle in setOf(IdeStatusLifecycleState.BLOCKED, IdeStatusLifecycleState.FAILED) ||
          facts.awaitingOperatorDecision ||
          (goal && facts.lifecycle == IdeStatusLifecycleState.IDLE && facts.finishedAt == null),
      displayGroup = displayGroup,
      displayPriority = displayPriority(facts),
      running = liveSection == IdeStatusLiveSection.GOALS || liveSection == IdeStatusLiveSection.PHASES,
      recentlyFinishedPhase = recent.within,
      doneWithinDay = done.within,
      membershipDeadline = listOfNotNull(recent.nextChange, done.nextChange).minOrNull(),
    )
  }

  private fun windowed(
    facts: IdeStatusWorkPolicyFacts,
    window: Duration,
    applies: Boolean,
  ): WindowedFact {
    val finishedAt = facts.finishedAt
    val observedAt = facts.observedAt
    if (!applies || finishedAt == null || observedAt == null) return WindowedFact(false, null)
    return when {
      finishedAt.isAfter(observedAt) -> WindowedFact(false, finishedAt)
      finishedAt.isBefore(observedAt.minus(window)) -> WindowedFact(false, null)
      else -> WindowedFact(true, finishedAt.plus(window).plusNanos(1))
    }
  }

  private data class WindowedFact(val within: Boolean, val nextChange: Instant?)

  private fun liveSection(
    facts: IdeStatusWorkPolicyFacts,
    goal: Boolean,
  ): IdeStatusLiveSection? =
    if (facts.finishedAt != null) {
      null
    } else {
      when (facts.lifecycle) {
        IdeStatusLifecycleState.BLOCKED, IdeStatusLifecycleState.FAILED, IdeStatusLifecycleState.PAUSED ->
          IdeStatusLiveSection.NEEDS_YOU
        IdeStatusLifecycleState.ACTIVE -> if (goal) IdeStatusLiveSection.GOALS else IdeStatusLiveSection.PHASES
        IdeStatusLifecycleState.IDLE -> IdeStatusLiveSection.NEEDS_YOU.takeIf { goal }
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
    } else {
      IdeStatusDisplayGroup.PHASES.takeIf {
        facts.family == IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME ||
          facts.family == IdeStatusWorkflowFamily.FEATURE_VERIFY ||
          facts.scope == IdeStatusExecutionScope.STANDALONE_PHASE
      }
    }

  private const val VERIFY_ABANDONED_AFTER_HOURS = 24L
  val VERIFY_ABANDONED_AFTER: Duration = Duration.ofHours(VERIFY_ABANDONED_AFTER_HOURS)
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
