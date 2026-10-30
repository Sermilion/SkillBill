package skillbill.workflow.taskruntime.model.persistence

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.goalrunner.model.GoalContinuation
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection

fun DurableWorkflowArtifacts.goalContinuationArtifact(): FeatureTaskRuntimeGoalContinuationArtifact? =
  typedArtifactRecordOrNull<FeatureTaskRuntimeGoalContinuationArtifact>(
    FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY,
  )

fun DurableWorkflowArtifacts.goalContinuation(): GoalContinuation? =
  goalContinuationArtifact()?.let { artifact ->
    GoalContinuation(
      issueKey = artifact.issueKey,
      subtaskId = artifact.subtaskId,
      suppressPr = artifact.suppressPr,
      goalBranch = artifact.goalBranch,
    )
  }

internal fun DurableWorkflowArtifacts.hasGoalContinuationMarker(): Boolean {
  if (!containsKey(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY)) return false
  val stored = this[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY]
  if (stored is UndecodedTypedArtifact && stored.legacyMarker != null) return true
  return goalContinuationArtifact() != null
}

data class FeatureTaskRuntimeGoalContinuationArtifact(
  val issueKey: String,
  val subtaskId: Int,
  val suppressPr: Boolean,
  val goalBranch: String,
  val parentWorkflowId: String? = null,
  val codeReviewMode: CodeReviewExecutionMode,
  val validationDepth: ValidationDepth? = null,
  val qualityGateSelection: FeatureTaskRuntimeQualityGateSelection? = null,
  val qaSelection: FeatureTaskRuntimeQaSelection? = null,
  val parallelReviewAgent: String? = null,
  val subtaskName: String? = null,
  val agentAddonSelection: AgentAddonSelection = AgentAddonSelection(),
) {
  init {
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationArtifact.issueKey must be non-blank." }
    require(subtaskId > 0) { "FeatureTaskRuntimeGoalContinuationArtifact.subtaskId must be positive." }
    require(goalBranch.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationArtifact.goalBranch must be non-blank." }
    parallelReviewAgent?.let {
      require(it.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationArtifact.parallelReviewAgent must be non-blank." }
    }
    subtaskName?.let {
      require(it.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationArtifact.subtaskName must be non-blank." }
    }
  }

  fun toWorkflowArtifactPatch(): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to this),
    )
}
