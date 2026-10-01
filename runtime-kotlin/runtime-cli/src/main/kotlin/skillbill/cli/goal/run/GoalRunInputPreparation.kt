package skillbill.cli.goal.run

import com.github.ajalt.clikt.core.UsageError
import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.cli.kernel.agent.ConfiguredAgentAddonSelectionResolver
import skillbill.cli.kernel.agent.parseAgentAddonSelection
import skillbill.cli.kernel.agent.refuseUnavailableAgentLaunchers
import skillbill.cli.kernel.agent.requireInvokingAgentId
import skillbill.cli.kernel.agent.requireSupportedOptionalAgentId
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentrun.ExecutableLookup

@Inject
class GoalRunInputPreparation(
  private val executableLookup: ExecutableLookup,
  private val agentAddonSelectionPort: AgentAddonSelectionPort,
  private val agentAddonResolver: ConfiguredAgentAddonSelectionResolver,
  private val inputs: CliRunInputs,
) {
  internal fun validate(args: GoalRunInputValidationArgs) {
    val invokedAgentId = resolveInvokedAgentId(args.agent, inputs.environment)
    requireSupportedOptionalAgentId(args.agentOverride, "--agent-override")
    refuseUnavailableAgentLaunchers(listOf(invokedAgentId, args.agentOverride), executableLookup)
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

  internal fun hydrateAgentAddonSelection(args: GoalRunAgentAddonHydrationArgs): HydratedAgentAddonSelection {
    val persistedSelection = parseAgentAddonSelection(args.agentAddonSelectionJson)
    return if (args.agentAddonSlugs.isNotEmpty()) {
      agentAddonResolver.resolveInitial(
        repoRoot = args.effectiveRepoRoot,
        requestedSlugs = args.agentAddonSlugs,
        receivingAgentIds = args.receivingAgents,
      )
    } else if (persistedSelection.entries.isEmpty()) {
      HydratedAgentAddonSelection()
    } else {
      agentAddonSelectionPort.verifyPersisted(
        persistedSelection,
        AgentAddonConsumer.SKILL_BILL,
        args.receivingAgents,
      )
    }
  }
}

internal fun resolveInvokedAgentId(
  explicitAgent: String?,
  environment: Map<String, String>,
): String = requireInvokingAgentId(explicitAgent, environment, "--agent")
