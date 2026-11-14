package skillbill.cli.goal.run

import com.github.ajalt.clikt.core.UsageError
import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.cli.kernel.agent.ConfiguredAgentAddonSelectionResolver
import skillbill.cli.kernel.agent.parseAgentAddonSelection
import skillbill.cli.kernel.agent.requireInvokingAgentId
import skillbill.cli.kernel.agent.requireSupportedAgentId
import skillbill.cli.kernel.agent.requireSupportedOptionalAgentId
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.install.model.unavailableAgentLauncherReason
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunLaunchCapability
import skillbill.ports.agentrun.model.AgentRunLaunchCapabilityRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.skeleton.LaunchEnvironmentKind
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot

private const val MAX_DIAGNOSTIC_ID = 128

@Inject
class GoalRunInputPreparation(
  private val executableLookup: ExecutableLookup,
  private val agentRunLauncher: AgentRunLauncher,
  private val agentAddonSelectionPort: AgentAddonSelectionPort,
  private val agentAddonResolver: ConfiguredAgentAddonSelectionResolver,
  private val inputs: CliRunInputs,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun validate(args: GoalRunInputValidationArgs) {
    resolveInvokedAgentId(args.agent, inputs.environment)
    requireSupportedOptionalAgentId(args.agentOverride, "--agent-override")
    val usageError =
      when {
        args.issueKey == null -> {
          "issue_key is required for goal run."
        }

        args.stopAfterSubtask != null && args.stopAfterSubtask <= 0 -> {
          "--stop-after-subtask must be a positive integer."
        }

        args.agentAddonSlugs.isNotEmpty() && args.agentAddonSelectionJson != null -> {
          "Use either --agent-addon or " +
            "${FeatureTaskRuntimeGoalContinuationLaunchTokens.AGENT_ADDON_SELECTION_JSON_FLAG}, not both."
        }

        else -> {
          null
        }
      }
    if (usageError != null) throw UsageError(usageError)
  }

  internal fun validateRoutingProviders(
    providersByPhase: Map<String, Set<String>>,
    retained: Boolean = false,
  ) {
    providersByPhase.filterKeys(::isAgentBackedGoalPhase).forEach { (phaseId, providerIds) ->
      providerIds.forEach { providerId ->
        val provider = requireSupportedAgentId(providerId, "routing for phase '$phaseId'")
        when (
          val support =
            agentRunLauncher.queryLaunchCapability(
              AgentRunLaunchCapabilityRequest(provider, LaunchEnvironmentKind.INHERITED),
            )
        ) {
          AgentRunLaunchCapability.Supported -> {}

          is AgentRunLaunchCapability.Unsupported -> {
            recordRoutingRefusal(phaseId, provider, "unsupported_adapter")
            throw UsageError("${support.reason} Selected for phase '$phaseId'.")
          }
        }
        unavailableAgentLauncherReason(provider, executableLookup::onPath)?.let { reason ->
          recordRoutingRefusal(phaseId, provider, "executable_unavailable")
          throw UsageError("$reason Selected for phase '$phaseId'.")
        }
        if (retained) {
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "seam=workflow_agent_assignment phase=${phaseId.take(MAX_DIAGNOSTIC_ID)} provider=$provider " +
              "reason=retained_authority outcome=validated provider_defaults=not_read",
          )
        }
      }
    }
  }

  private fun recordRoutingRefusal(
    phaseId: String,
    provider: String,
    reason: String,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=workflow_agent_assignment phase=${phaseId.take(MAX_DIAGNOSTIC_ID)} provider=$provider " +
        "reason=$reason outcome=refused",
    )
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

  internal fun recordLegacyRoutingUse(providerId: String) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=workflow_agent_assignment phase=preplan provider=${providerId.take(MAX_DIAGNOSTIC_ID)} " +
        "reason=legacy_invocation_compatibility outcome=used",
    )
  }
}

internal fun isAgentBackedGoalPhase(phaseId: String): Boolean =
  when (PhaseSlot.slotForStep(phaseId)) {
    PhaseSlot.INTAKE, PhaseSlot.COMMIT_PUSH, PhaseSlot.STANDALONE_REVIEW -> false
    PhaseSlot.QUALITY_GATE -> phaseId != PhaseSlot.QUALITY_GATE.steps.first()
    else -> true
  }

internal fun resolveInvokedAgentId(
  explicitAgent: String?,
  environment: Map<String, String>,
): String = requireInvokingAgentId(explicitAgent, environment, "--agent")
