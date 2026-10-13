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
)

enum class IdeStatusLiveSection { NEEDS_YOU, GOALS, PHASES }

enum class IdeStatusDisplayGroup { GOALS, PHASES }

data class IdeStatusWorkPolicy(
  val liveSection: IdeStatusLiveSection?,
  val controlEligible: Boolean,
  val requiresAttention: Boolean,
  val displayGroup: IdeStatusDisplayGroup?,
  val displayPriority: Int,
)

object IdeStatusPolicy {
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
    val goal = facts.family == IdeStatusWorkflowFamily.FEATURE_GOAL
    return IdeStatusWorkPolicy(
      liveSection = liveSection(facts, goal),
      controlEligible =
        goal && facts.scope != IdeStatusExecutionScope.STANDALONE_PHASE &&
          facts.controlTargetPresent && facts.lifecycle == IdeStatusLifecycleState.ACTIVE &&
          facts.freshness == IdeStatusFreshness.FRESH,
      requiresAttention =
        facts.lifecycle in setOf(IdeStatusLifecycleState.BLOCKED, IdeStatusLifecycleState.FAILED) ||
          facts.awaitingOperatorDecision,
      displayGroup = displayGroup(facts, goal),
      displayPriority = displayPriority(facts),
    )
  }

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

  private const val BLOCKED_PRIORITY = 0
  private const val FAILED_PRIORITY = 1
  private const val PLANNING_PRIORITY = 2
  private const val ACTIVE_PRIORITY = 3
  private const val PAUSED_PRIORITY = 4
  private const val OTHER_PRIORITY = 5
}
