package skillbill.engine.goalrunner.model

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.goalrunner.model.GoalPullRequestStatus
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class GoalRunnerRunRequest(
  val issueKey: String,
  val repoRoot: Path,
  val invokedAgentId: String,
  val configuredAgentOverrideId: String? = null,
  val timeout: Duration? = null,
  val progressIdleTimeout: Duration? = null,
  val planningBudget: Duration? = DEFAULT_GOAL_PLANNING_BUDGET,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
  val eventSink: GoalRunnerEventSink = GoalRunnerEventSink.NONE,
  val codeReviewMode: CodeReviewExecutionMode? = null,
  val qaSelection: FeatureTaskRuntimeQaSelection? = null,
  val agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
  val stopAfterSubtaskId: Int? = null,
  val intake: String? = null,
  val modelAssignment: FeatureTaskRuntimeModelAssignment = FeatureTaskRuntimeModelAssignment(),
  internal val executionGeneration: Long? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(invokedAgentId.isNotBlank()) { "invokedAgentId is required." }
    configuredAgentOverrideId?.let { require(it.isNotBlank()) { "configuredAgentOverrideId must not be blank." } }
    stopAfterSubtaskId?.let { require(it > 0) { "stopAfterSubtaskId must be positive when provided." } }
    timeout?.let { maxWallClockTimeout ->
      require(maxWallClockTimeout.isPositive()) { "timeout must be positive when provided." }
    }
    progressIdleTimeout?.let { idleTimeout ->
      require(idleTimeout.isPositive()) { "progressIdleTimeout must be positive when provided." }
    }
    planningBudget?.let { budget ->
      require(budget.isPositive()) { "planningBudget must be positive when provided." }
    }
  }
}

sealed interface GoalRunnerRunEvent {
  val issueKey: String

  data class Started(override val issueKey: String) : GoalRunnerRunEvent

  data class SubtaskStarted(
    override val issueKey: String,
    val subtaskId: Int,
    val action: String,
    val currentStepId: String? = null,
  ) : GoalRunnerRunEvent

  data class SubtaskCompleted(
    override val issueKey: String,
    val subtaskId: Int,
    val currentStepId: String? = null,
  ) : GoalRunnerRunEvent

  data class SubtaskStopped(
    override val issueKey: String,
    val subtaskId: Int,
    val reason: String,
    val blockedReason: String,
    val currentStepId: String? = null,
    val parentWorkflowId: String? = null,
    val workflowId: String? = null,
  ) : GoalRunnerRunEvent

  data class SubtaskReviewSummary(
    override val issueKey: String,
    val subtaskId: Int,
    val passNumber: Int,
    val verdict: String,
    val findingCount: Int,
    val unresolvedFindingCount: Int,
    val findings: List<GoalSubtaskReviewCompactFinding>,
  ) : GoalRunnerRunEvent

  data class ResumeRepaired(
    override val issueKey: String,
    val repairs: List<GoalRunnerAppliedRepair>,
  ) : GoalRunnerRunEvent

  data class Completed(
    override val issueKey: String,
    val completedCount: Int,
    val pendingCount: Int,
    val blockedCount: Int,
    val pullRequestStatus: GoalPullRequestStatus,
    val pullRequestUrl: String?,
  ) : GoalRunnerRunEvent
}

val DEFAULT_GOAL_PLANNING_BUDGET: Duration = 30.minutes

fun interface GoalRunnerEventSink {
  fun emit(event: GoalRunnerRunEvent)

  companion object {
    val NONE: GoalRunnerEventSink = GoalRunnerEventSink {}
  }
}

sealed interface GoalSubtaskQaSummary {
  data object NotComposed : GoalSubtaskQaSummary

  data object NotRecorded : GoalSubtaskQaSummary

  data class Recorded(val outcome: FeatureTaskQaOutcome) : GoalSubtaskQaSummary
}

data class GoalUnknownScenario(
  val scenarioRef: String,
  val reason: String?,
)

sealed interface GoalSubtaskUnknownScenarios {
  data object Absent : GoalSubtaskUnknownScenarios

  data object Empty : GoalSubtaskUnknownScenarios

  data class Malformed(val reason: String) : GoalSubtaskUnknownScenarios

  data class Recorded(val scenarios: List<GoalUnknownScenario>) : GoalSubtaskUnknownScenarios
}

sealed interface GoalQaSummary {
  data object RanPerSubtask : GoalQaSummary

  data object NotRecorded : GoalQaSummary

  data class Recorded(val outcome: FeatureTaskQaOutcome) : GoalQaSummary

  data class Warned(
    val reason: String,
    val outcome: FeatureTaskQaOutcome?,
  ) : GoalQaSummary
}
