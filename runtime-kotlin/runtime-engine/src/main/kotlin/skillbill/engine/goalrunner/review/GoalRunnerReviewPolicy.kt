package skillbill.engine.goalrunner.review

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.review.context.model.execution.CodeReviewExecutionMode

fun GoalRunnerManifestStore.effectiveAgentAddonSelection(
  parentWorkflowId: String,
  request: GoalRunnerRunRequest,
): AgentAddonSelection =
  request.agentAddonSelection.persisted
    .takeUnless { it.entries.isEmpty() }
    ?: reviewPolicy(parentWorkflowId)?.agentAddonSelection
    ?: AgentAddonSelection()

internal data class GoalRunnerEffectiveReviewPolicy(
  val codeReviewMode: CodeReviewExecutionMode,
)

internal fun effectiveGoalRunnerReviewPolicy(
  requestedReviewMode: CodeReviewExecutionMode?,
  persisted: GoalRunnerReviewPolicy?,
): GoalRunnerEffectiveReviewPolicy =
  GoalRunnerEffectiveReviewPolicy(
    codeReviewMode =
      requestedReviewMode
        ?: persisted?.codeReviewMode
        ?: CodeReviewExecutionMode.DEFAULT,
  )

fun goalRunnerReviewPolicyMismatch(
  parentWorkflowId: String,
  requestedReviewMode: CodeReviewExecutionMode?,
  persisted: GoalRunnerReviewPolicy,
): String? =
  when {
    requestedReviewMode != null && persisted.codeReviewMode != requestedReviewMode ->
      "Cannot change code-review mode on goal resume: parent workflow '$parentWorkflowId' " +
        "is pinned to '${persisted.codeReviewMode.wireValue}', not '${requestedReviewMode.wireValue}'."
    else -> null
  }
