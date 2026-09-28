package skillbill.cli.goal.run

import com.github.ajalt.clikt.core.UsageError
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.cli.codereview.usageError
import skillbill.cli.kernel.agent.parseAgentAddonSelection
import skillbill.cli.kernel.agent.refuseUnavailableAgentLaunchers
import skillbill.cli.kernel.agent.requireInvokingAgentId
import skillbill.cli.kernel.agent.requireSupportedOptionalAgentId
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.model.toPath
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigRequest

internal fun validateGoalRunInputs(args: GoalRunInputValidationArgs) {
  val invokedAgentId = resolveInvokedAgentId(args.agent, args.inputs.environment)
  requireSupportedOptionalAgentId(args.agentOverride, "--agent-override")
  refuseUnavailableAgentLaunchers(listOf(invokedAgentId, args.agentOverride), args.executableLookup)
  val usageError =
    when {
      args.issueKey == null -> "issue_key is required for goal run."
      args.stopAfterSubtask != null && args.stopAfterSubtask <= 0 ->
        "--stop-after-subtask must be a positive integer."
      args.agentAddonSlugs.isNotEmpty() && args.agentAddonSelectionJson != null ->
        "Use either --agent-addon or " +
          "${FeatureTaskRuntimeGoalContinuationLaunchTokens.AGENT_ADDON_SELECTION_JSON_FLAG}, not both."
      else -> null
    }
  if (usageError != null) throw UsageError(usageError)
}

internal fun hydrateGoalRunAgentAddonSelection(args: GoalRunAgentAddonHydrationArgs): HydratedAgentAddonSelection {
  val persistedSelection = parseAgentAddonSelection(args.agentAddonSelectionJson)
  return if (args.agentAddonSlugs.isNotEmpty()) {
    args.agentAddonSelectionPort.resolveInitial(
      repoRoot = args.effectiveRepoRoot,
      requestedSlugs = args.agentAddonSlugs,
      consumer = AgentAddonConsumer.SKILL_BILL,
      receivingAgentIds = args.receivingAgents,
      externalSourceRoots =
        args.externalAgentAddonSourceConfigPort.readExternalAgentAddonSources(
          ExternalAgentAddonSourceConfigRequest(args.inputs.userHome, args.inputs.environment),
        ).sources.map { source -> source.path.toPath() },
    )
  } else if (persistedSelection.entries.isEmpty()) {
    HydratedAgentAddonSelection()
  } else {
    args.agentAddonSelectionPort.verifyPersisted(
      persistedSelection,
      AgentAddonConsumer.SKILL_BILL,
      args.receivingAgents,
    )
  }
}

internal fun resolveInvokedAgentId(
  explicitAgent: String?,
  environment: Map<String, String>,
): String = requireInvokingAgentId(explicitAgent, environment, "--agent")
