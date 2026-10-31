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

public data class GoalRunnerRunRequest(
  public val issueKey: String,
  public val repoRoot: Path,
  public val invokedAgentId: String,
  public val configuredAgentOverrideId: String? = null,
  public val timeout: Duration? = null,
  public val progressIdleTimeout: Duration? = null,
  public val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
  public val eventSink: GoalRunnerEventSink = GoalRunnerEventSink.NONE,
  public val codeReviewMode: CodeReviewExecutionMode? = null,
  public val qaSelection: FeatureTaskRuntimeQaSelection? = null,
  public val agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
  public val stopAfterSubtaskId: Int? = null,
  public val intake: String? = null,
  public val modelAssignment: FeatureTaskRuntimeModelAssignment = FeatureTaskRuntimeModelAssignment(),
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
  }
}

public sealed interface GoalRunnerRunEvent {
  public val issueKey: String

  public data class Started(override val issueKey: String) : GoalRunnerRunEvent

  public data class SubtaskStarted(
    override val issueKey: String,
    public val subtaskId: Int,
    public val action: String,
    public val currentStepId: String? = null,
  ) : GoalRunnerRunEvent

  public data class SubtaskCompleted(
    override val issueKey: String,
    public val subtaskId: Int,
    public val currentStepId: String? = null,
  ) : GoalRunnerRunEvent

  public data class SubtaskStopped(
    override val issueKey: String,
    public val subtaskId: Int,
    public val reason: String,
    public val blockedReason: String,
    public val currentStepId: String? = null,
    public val parentWorkflowId: String? = null,
    public val workflowId: String? = null,
  ) : GoalRunnerRunEvent

  public data class SubtaskReviewSummary(
    override val issueKey: String,
    public val subtaskId: Int,
    public val passNumber: Int,
    public val verdict: String,
    public val findingCount: Int,
    public val unresolvedFindingCount: Int,
    public val findings: List<GoalSubtaskReviewCompactFinding>,
  ) : GoalRunnerRunEvent

  public data class ResumeRepaired(
    override val issueKey: String,
    public val repairs: List<GoalRunnerAppliedRepair>,
  ) : GoalRunnerRunEvent

  public data class Completed(
    override val issueKey: String,
    public val completedCount: Int,
    public val pendingCount: Int,
    public val blockedCount: Int,
    public val pullRequestStatus: GoalPullRequestStatus,
    public val pullRequestUrl: String?,
  ) : GoalRunnerRunEvent
}

public fun interface GoalRunnerEventSink {
  public fun emit(event: GoalRunnerRunEvent)

  public companion object {
    public val NONE: GoalRunnerEventSink = GoalRunnerEventSink {}
  }
}

public sealed interface GoalSubtaskQaSummary {
  public data object NotComposed : GoalSubtaskQaSummary

  public data object NotRecorded : GoalSubtaskQaSummary

  public data class Recorded(public val outcome: FeatureTaskQaOutcome) : GoalSubtaskQaSummary
}

public data class GoalUnknownScenario(
  public val scenarioRef: String,
  public val reason: String?,
)

public sealed interface GoalSubtaskUnknownScenarios {
  public data object Absent : GoalSubtaskUnknownScenarios

  public data object Empty : GoalSubtaskUnknownScenarios

  public data class Malformed(public val reason: String) : GoalSubtaskUnknownScenarios

  public data class Recorded(public val scenarios: List<GoalUnknownScenario>) : GoalSubtaskUnknownScenarios
}

public sealed interface GoalQaSummary {
  public data object RanPerSubtask : GoalQaSummary

  public data object NotRecorded : GoalQaSummary

  public data class Recorded(public val outcome: FeatureTaskQaOutcome) : GoalQaSummary

  public data class Warned(
    public val reason: String,
    public val outcome: FeatureTaskQaOutcome?,
  ) : GoalQaSummary
}
