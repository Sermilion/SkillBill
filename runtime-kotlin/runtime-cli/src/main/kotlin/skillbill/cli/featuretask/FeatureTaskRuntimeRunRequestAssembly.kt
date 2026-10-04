package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import skillbill.application.review.service.RuntimeOwnedReviewMode
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.error.featuretask.UnknownQualityGateSelectionError
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.goalreview.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection

internal fun FeatureTaskRuntimePhaseAgentCommand.parseGoalContinuationContext(
  environment: Map<String, String>,
): FeatureTaskRuntimeGoalContinuationContext? {
  val requestedReviewMode = requestedCodeReviewMode()
  val supplied =
    listOf(goalParentIssueKey, goalSubtaskId, goalBranch).count { it != null } +
      if (suppressPr) 1 else 0
  if (supplied == 0) {
    return null
  }
  val missing = goalContinuationMissingFields()
  if (missing.isNotEmpty()) {
    throw UsageError("${missing.joinToString()} required with goal-continuation options.")
  }
  val tokens = FeatureTaskRuntimeGoalContinuationLaunchTokens
  return FeatureTaskRuntimeGoalContinuationContext(
    parentIssueKey = requireNotNull(goalParentIssueKey),
    subtaskId = requireNotNull(goalSubtaskId),
    goalBranch = requireNotNull(goalBranch),
    suppressPr = true,
    parentWorkflowId = goalParentWorkflowId?.takeIf(String::isNotBlank),
    lastResumableStep = goalLastResumableStep?.takeIf(String::isNotBlank),
    codeReviewMode = requestedReviewMode,
    validationDepth =
      environment[tokens.VALIDATION_DEPTH_ENV]
        ?.takeIf(String::isNotBlank)
        ?.let(ValidationDepth::fromWire)
        ?: ValidationDepth.FULL,
    qualityGateSelection = requestedQualityGateSelection(environment),
    reviewBaseline =
      requireNotNull(goalReviewBaseSha?.takeIf(String::isNotBlank)) {
        "${FeatureTaskRuntimeGoalContinuationLaunchTokens.GOAL_REVIEW_BASE_SHA_FLAG} is required with " +
          "goal-continuation options."
      }.let { base ->
        GoalSubtaskReviewBaseline(base, goalBaselineUntrackedPaths.distinct().sorted())
      },
  )
}

internal fun FeatureTaskRuntimePhaseAgentCommand.requestedQualityGateSelection(
  environment: Map<String, String>,
): FeatureTaskRuntimeQualityGateSelection {
  val fromEnv =
    environment[FeatureTaskRuntimeGoalContinuationLaunchTokens.QUALITY_GATE_SELECTION_ENV]
      ?.takeIf(String::isNotBlank)
      ?.let { raw ->
        parseQualityGateSelection(FeatureTaskRuntimeGoalContinuationLaunchTokens.QUALITY_GATE_SELECTION_ENV, raw)
      }
  val fromCli =
    when (qualityGateSelections.size) {
      0 -> null
      1 ->
        parseQualityGateSelection(
          FeatureTaskRuntimeGoalContinuationLaunchTokens.QUALITY_GATE_SELECTION_FLAG,
          qualityGateSelections.single(),
        )
      else -> {
        val raw = qualityGateSelections.joinToString(", ")
        if (qualityGateSelections.distinct().size == 1) {
          throw UsageError(
            "Duplicate ${FeatureTaskRuntimeGoalContinuationLaunchTokens.QUALITY_GATE_SELECTION_FLAG} " +
              "'$raw' is not allowed; supply it at most once.",
          )
        }
        throw UsageError(
          "Conflicting ${FeatureTaskRuntimeGoalContinuationLaunchTokens.QUALITY_GATE_SELECTION_FLAG} values " +
            "'$raw' are not allowed; supply exactly one selection.",
        )
      }
    }
  return fromCli ?: fromEnv ?: FeatureTaskRuntimeQualityGateSelection.VALIDATE
}

private fun parseQualityGateSelection(
  source: String,
  raw: String,
): FeatureTaskRuntimeQualityGateSelection =
  try {
    FeatureTaskRuntimeQualityGateSelection.fromWire(raw)
  } catch (error: UnknownQualityGateSelectionError) {
    throw UsageError(
      "Unknown $source value '$raw'. Allowed: ${error.allowedValues.joinToString()}.",
    ).also { usage ->
      runCatching { usage.initCause(error) }
    }
  }

internal fun FeatureTaskRuntimePhaseAgentCommand.requestedOperatorDecision(): GoalSubtaskOperatorDecision? {
  if (operatorDecisions.size > 1) {
    throw UsageError(
      "Conflicting --operator-decision values '${operatorDecisions.joinToString(", ")}' are not allowed; " +
        "supply exactly one decision.",
    )
  }
  return operatorDecisions.singleOrNull()?.let { raw ->
    GoalSubtaskOperatorDecision.entries.firstOrNull { it.wireValue == raw }
      ?: throw UsageError(
        "Unknown operator decision '$raw'. Allowed: " +
          "${GoalSubtaskOperatorDecision.entries.joinToString { it.wireValue }}.",
      )
  }
}

internal fun FeatureTaskRuntimePhaseAgentCommand.requestedCodeReviewMode() =
  run {
    val modes = codeReviewModes.map(::parseRequestedCodeReviewMode)
    when (modes.size) {
      0 -> null
      1 -> modes.single()
      else -> {
        val rawModes = codeReviewModes.joinToString(", ")
        if (modes.distinct().size == 1) {
          throw UsageError(
            "Duplicate ${FeatureTaskRuntimeGoalContinuationLaunchTokens.CODE_REVIEW_MODE_FLAG} '$rawModes' " +
              "is not allowed; supply it at most once.",
          )
        }
        throw UsageError(
          "Conflicting ${FeatureTaskRuntimeGoalContinuationLaunchTokens.CODE_REVIEW_MODE_FLAG} values " +
            "'$rawModes' are not allowed; supply exactly one mode.",
        )
      }
    }
  }

internal fun FeatureTaskRuntimePhaseAgentCommand.parseRequestedCodeReviewMode(raw: String) =
  RuntimeOwnedReviewMode.parse(raw) ?: throw UsageError(RuntimeOwnedReviewMode.unknownModeMessage(raw))

internal fun FeatureTaskRuntimePhaseAgentCommand.goalContinuationMissingFields(): List<String> =
  buildList {
    val tokens = FeatureTaskRuntimeGoalContinuationLaunchTokens
    if (goalParentIssueKey.isNullOrBlank()) add("${tokens.GOAL_PARENT_ISSUE_KEY_FLAG} is")
    if (goalSubtaskId == null) add("${tokens.GOAL_SUBTASK_ID_FLAG} is")
    if (goalBranch.isNullOrBlank()) add("${tokens.GOAL_BRANCH_FLAG} is")
    if (goalReviewBaseSha.isNullOrBlank()) add("${tokens.GOAL_REVIEW_BASE_SHA_FLAG} is")
    if (!suppressPr) add("${tokens.SUPPRESS_PR_FLAG} is")
  }
