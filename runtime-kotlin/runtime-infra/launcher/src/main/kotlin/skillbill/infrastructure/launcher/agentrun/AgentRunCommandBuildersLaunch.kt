package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.agentaddon.AGENT_ADDON_SELECTION_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.error.shellcontent.governedReviewLaunchCapability
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.SkillRunGoalContinuationContext
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.review.model.ReviewLaunchIsolationStrategy
import skillbill.review.context.model.launch.ReviewConversationIsolation
import java.nio.file.Path

internal fun launchPrompt(request: SkillRunRequest): String =
  requireNotNull(request.promptOverride) {
    "launchPrompt requires a promptOverride; goal-continuation runs spawn skill-bill directly."
  }

internal fun governedReviewConversationIsolation(request: SkillRunRequest): ReviewConversationIsolation? =
  ReviewConversationIsolation.FRESH.takeIf { request.reviewEvidenceBroker != null }

internal fun requireGovernedReviewLaunch(
  request: SkillRunRequest,
  agent: SupportedAgent,
  capability: GovernedReviewLaunchCapability,
) {
  if (request.reviewEvidenceEndpoint == null) return
  if (!capability.governedOnlyTooling) {
    throw governedReviewLaunchCapability(agent.id, "governed-only tooling")
  }
  if (!capability.mcpIsolation) {
    throw governedReviewLaunchCapability(agent.id, "MCP isolation")
  }
}

internal fun requireProcessLaunch(
  request: SkillRunRequest,
  strategy: ReviewLaunchIsolationStrategy,
) {
  if (request.reviewEvidenceBroker == null) return
  require(strategy.supported) {
    "Governed specialist launches require a supported fresh-context strategy."
  }
  if (strategy == ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE) {
    require(strategy.supported) {
      "Governed Codex review launches require fresh-context isolation."
    }
  }
}

internal fun goalContinuationCommand(
  request: SkillRunRequest,
  agent: SupportedAgent,
  databasePath: Path?,
): AgentRunCommand? {
  val context = request.goalContinuation ?: return null
  if (request.promptOverride != null) return null
  return AgentRunCommand(
    command = goalContinuationArguments(request, agent, databasePath),
    workingDirectory = request.repoRoot,
    timeout = request.timeout,
    environment = goalContinuationEnvironment(request),
    idlePolicy = unstreamedLivenessPolicy(request),
  )
}

internal fun goalContinuationArguments(
  request: SkillRunRequest,
  agent: SupportedAgent,
  databasePath: Path?,
): List<String> {
  val context = requireNotNull(request.goalContinuation)
  val childWorkflowId = context.childWorkflowId?.takeIf(String::isNotBlank)
  val assignedWorkflowId = context.assignedWorkflowId?.takeIf(String::isNotBlank)
  return buildList {
    add("skill-bill")
    databasePath?.let { db ->
      add("--db")
      add(db.toString())
    }
    add(FeatureTaskRuntimeGoalContinuationLaunchTokens.FEATURE_TASK_COMMAND)
    if (childWorkflowId != null) {
      add(FeatureTaskRuntimeGoalContinuationLaunchTokens.RESUME_SUBCOMMAND)
      add(childWorkflowId)
    } else {
      add(FeatureTaskRuntimeGoalContinuationLaunchTokens.RUN_SUBCOMMAND)
    }
    add(request.issueKey)
    add(context.specPath)
    if (childWorkflowId == null && assignedWorkflowId != null) {
      add(FeatureTaskRuntimeGoalContinuationLaunchTokens.WORKFLOW_ID_FLAG)
      add(assignedWorkflowId)
    }
    addGoalContinuationArguments(context)
    add("--agent")
    add(agent.id)
    request.timeout?.inWholeMinutes?.takeIf { it > 0L }?.let { minutes ->
      add("--max-wall-clock-minutes")
      add(minutes.toString())
    }
  }
}

internal fun MutableList<String>.addGoalContinuationArguments(context: SkillRunGoalContinuationContext) {
  val tokens = FeatureTaskRuntimeGoalContinuationLaunchTokens
  add(tokens.GOAL_PARENT_ISSUE_KEY_FLAG)
  add(context.parentIssueKey)
  add(tokens.GOAL_SUBTASK_ID_FLAG)
  add(context.subtaskId.toString())
  add(tokens.GOAL_BRANCH_FLAG)
  add(context.goalBranch)
  add(tokens.SUPPRESS_PR_FLAG)
  context.parentWorkflowId?.takeIf(String::isNotBlank)?.let { parentWorkflowId ->
    add(tokens.GOAL_PARENT_WORKFLOW_ID_FLAG)
    add(parentWorkflowId)
  }
  context.lastResumableStep?.takeIf(String::isNotBlank)?.let { step ->
    add(tokens.GOAL_LAST_RESUMABLE_STEP_FLAG)
    add(step)
  }
  add(tokens.CODE_REVIEW_MODE_FLAG)
  add(context.codeReviewMode.wireValue)
  add(tokens.QUALITY_GATE_SELECTION_FLAG)
  add(context.qualityGateSelection.wireValue)
  context.reviewBaseline?.let { baseline ->
    add(tokens.GOAL_REVIEW_BASE_SHA_FLAG)
    add(baseline.reviewBaseSha)
    baseline.baselineUntrackedPaths.forEach { path ->
      add(tokens.GOAL_BASELINE_UNTRACKED_PATH_FLAG)
      add(path)
    }
  }
  if (context.agentAddonSelection.entries.isNotEmpty()) {
    add(tokens.AGENT_ADDON_SELECTION_JSON_FLAG)
    add(
      JsonCodec.mapToJsonString(
        linkedMapOf(
          SharedPayloadKeys.CONTRACT_VERSION to AGENT_ADDON_SELECTION_CONTRACT_VERSION,
          FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ENTRIES to
            context.agentAddonSelection.entries.map { entry ->
              linkedMapOf(
                FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG to entry.slug,
                FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY to entry.sourceIdentity,
                FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256 to entry.contentSha256,
              )
            },
        ),
      ),
    )
  }
}
