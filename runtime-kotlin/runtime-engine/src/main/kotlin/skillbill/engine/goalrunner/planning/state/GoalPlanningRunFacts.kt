package skillbill.engine.goalrunner.planning.state

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.config.model.CompactionSettings
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.time.Duration

internal class GoalPlanningRunFacts(
  shared: GoalPlanningSharedContext,
  request: GoalRunnerRunRequest,
) : FeatureTaskRuntimeRunFacts {
  override val issueKey: String = request.issueKey
  override val workflowId: String = ""
  override val runInvariants: FeatureTaskRuntimeRunInvariants =
    FeatureTaskRuntimeRunInvariants(
      specReference = shared.parentSpecPath.toString(),
      acceptanceCriteria = listOf(GOAL_PLANNING_RUN_CRITERION),
      mandatesAndOverrides = emptyList(),
    )
  override val invokedAgentId: String = request.invokedAgentId
  override val agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment()
  override val modelAssignment: FeatureTaskRuntimeModelAssignment = FeatureTaskRuntimeModelAssignment()
  override val compactionSettings: CompactionSettings = CompactionSettings.DEFAULT
  override val environment: Map<String, String> = emptyMap()
  override val repoRoot: Path = shared.repoRoot
  override val timeout: Duration? = request.planningBudget
  override val requestedCodeReviewMode: CodeReviewExecutionMode? = null
  override val goalContinuation: FeatureTaskRuntimeGoalContinuationContext? = null
  override val agentAddonSelection: HydratedAgentAddonSelection = request.agentAddonSelection
  override val eventSink: FeatureTaskRuntimeRunEventSink = FeatureTaskRuntimeRunEventSink.NONE
  override val transitionsOverride: FeatureTaskRuntimeTransitionDeclaration? = null
  override val skeletonDefinition: SkeletonDefinition = SkeletonDefinition.GOAL_PLANNING
}

private const val GOAL_PLANNING_RUN_CRITERION =
  "Goal planning settles the shared preplan and the plan of every active subtask."
