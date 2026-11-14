package skillbill.cli.config

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.config.RuntimeConfigEditService
import skillbill.application.config.model.ConfigDocumentRead
import skillbill.application.config.model.ConfigWriteResult
import skillbill.application.config.model.ExecutionMatrixEditIntent
import skillbill.application.config.model.ExecutionMatrixPhaseAgentEdit
import skillbill.application.config.model.ExecutionMatrixTierAgentEdit
import skillbill.application.config.model.MachineConfigEdit
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.DocumentedNoOpCliCommand
import skillbill.cli.kernel.cli.usageError
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionMatrixFieldKeys
import skillbill.config.model.ExecutionTier
import skillbill.config.model.WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS
import skillbill.contracts.SharedPayloadKeys
import skillbill.install.model.SupportedAgent
import java.nio.file.Path

private fun ConfigDocumentRead<*>.selectedPath(): Path =
  when (this) {
    is ConfigDocumentRead.Absent -> path
    is ConfigDocumentRead.Unreadable -> path
    is ConfigDocumentRead.Present -> path
  }

private object ConfigAgentsPayloadKeys {
  const val CONFIG_PATH = "config_path"
  const val PHASES = "phases"
  const val ERROR = "error"
}

private const val ROUTING_HELP =
  "Machine provider routing saved under execution_matrix.tier_agents and phase_agents in config.json. " +
    "First admission resolves, highest first: run-wide --agent-override, CLI --phase-agent, phase_agents, " +
    "tier_agents for the phase's phase_tiers value, then the invoking provider. Unset removes an authored " +
    "entry so the phase inherits again. A frozen run keeps its admitted providers; a conflicting explicit " +
    "override is refused - remove that option."

private fun failurePayload(
  path: Path,
  reason: String,
): Map<String, Any?> =
  mapOf(
    SharedPayloadKeys.STATUS to "failed",
    ConfigAgentsPayloadKeys.CONFIG_PATH to path.toString(),
    ConfigAgentsPayloadKeys.ERROR to reason,
  )

@Inject
class ConfigAgentsCommand(
  show: ConfigAgentsShowCommand,
  set: ConfigAgentsSetCommand,
  unset: ConfigAgentsUnsetCommand,
) : DocumentedNoOpCliCommand("agents", ROUTING_HELP) {
  init {
    subcommands(show, set, unset)
  }
}

@Inject
class ConfigAgentsShowCommand(
  private val runtimeConfigEditService: RuntimeConfigEditService,
  private val state: CliRunState,
) : DocumentedCliCommand(
    "show",
    "Show authored machine provider routing and the inherited source per runtime phase. " + ROUTING_HELP,
  ) {
  override fun run() {
    val snapshot = runtimeConfigEditService.readMachineConfigSnapshot()
    when (val document = snapshot.document) {
      is ConfigDocumentRead.Unreadable -> {
        state.completeText(
          "${document.path}: ${document.reason}\n",
          failurePayload(document.path, document.reason),
          exitCode = 1,
        )
      }

      else -> {
        val matrix = (document as? ConfigDocumentRead.Present)?.values?.executionMatrix
        val path = document.selectedPath()
        val tierAgents = matrix?.tierAgents.orEmpty()
        val phaseAgents = matrix?.phaseAgents.orEmpty()
        val phases = WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS.map { phaseId -> phasePreview(phaseId, matrix) }
        val text =
          buildString {
            append("machine config: ").append(path).append('\n')
            append("tier_agents:\n")
            if (tierAgents.isEmpty()) {
              append("  (none authored)\n")
            }
            tierAgents.forEach { (tier, agent) ->
              append("  ").append(tier.id).append(": ").append(agent.id).append('\n')
            }
            append("phase_agents:\n")
            if (phaseAgents.isEmpty()) {
              append("  (none authored)\n")
            }
            phaseAgents.forEach { (phaseId, agent) ->
              append("  ").append(phaseId).append(": ").append(agent.id).append('\n')
            }
            append("phases:\n")
            phases.forEach { line -> append("  ").append(line).append('\n') }
          }
        state.completeText(
          text,
          mapOf(
            SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
            ConfigAgentsPayloadKeys.CONFIG_PATH to path.toString(),
            ExecutionMatrixFieldKeys.TIER_AGENTS to tierAgents.mapKeys { it.key.id }.mapValues { it.value.id },
            ExecutionMatrixFieldKeys.PHASE_AGENTS to phaseAgents.mapValues { it.value.id },
            ConfigAgentsPayloadKeys.PHASES to phases,
          ),
        )
      }
    }
  }

  private fun phasePreview(
    phaseId: String,
    matrix: ExecutionMatrix?,
  ): String {
    val tier =
      matrix?.let {
        try {
          it.tierOf(phaseId)
        } catch (_: NoSuchElementException) {
          null
        }
      }
    val configuredPhase = matrix?.phaseAgents?.get(phaseId)
    val configuredTier = tier?.let { matrix.tierAgents[it] }
    val tierLabel = tier?.let { " (${it.id})" }.orEmpty()
    return when {
      configuredPhase != null -> "$phaseId$tierLabel: ${configuredPhase.id} (phase_agents)"
      configuredTier != null -> "$phaseId$tierLabel: ${configuredTier.id} (tier_agents)"
      else -> "$phaseId$tierLabel: inherited (invoking agent)"
    }
  }
}

class RoutingSelector(
  val key: String,
  val entryKey: String,
  val intent: ExecutionMatrixEditIntent,
)

private fun parseRoutingSelector(
  tier: String?,
  phase: String?,
): RoutingSelector {
  val tierValue = tier?.trim()?.takeIf(String::isNotEmpty)
  val phaseValue = phase?.trim()?.takeIf(String::isNotEmpty)
  if ((tierValue == null) == (phaseValue == null)) {
    usageError(IllegalArgumentException("Pass exactly one of --tier or --phase."))
  }
  if (tierValue != null) {
    val parsed =
      ExecutionTier.fromId(tierValue.lowercase())
        ?: usageError(IllegalArgumentException("Unknown tier '$tierValue'; use reasoning or implementation."))
    return RoutingSelector(
      ExecutionMatrixFieldKeys.TIER_AGENTS,
      parsed.id,
      ExecutionMatrixEditIntent(tierAgents = listOf(ExecutionMatrixTierAgentEdit(parsed))),
    )
  }
  val phaseId = phaseValue.orEmpty()
  if (phaseId !in WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS) {
    usageError(
      IllegalArgumentException(
        "Unknown phase '$phaseId'; runtime phases: ${WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS.joinToString()}.",
      ),
    )
  }
  return RoutingSelector(
    ExecutionMatrixFieldKeys.PHASE_AGENTS,
    phaseId,
    ExecutionMatrixEditIntent(phaseAgents = listOf(ExecutionMatrixPhaseAgentEdit(phaseId))),
  )
}

abstract class ConfigAgentsWriteCommand(
  name: String,
  help: String,
  private val runtimeConfigEditService: RuntimeConfigEditService,
  private val state: CliRunState,
) : DocumentedCliCommand(name, help) {
  protected val tier by option("--tier", help = "Tier to route: reasoning or implementation.")
  protected val phase by option("--phase", help = "Runtime phase id to route, e.g. plan, review, goal_assessment.")

  protected fun applyWrite(
    selector: RoutingSelector,
    requested: Map<String, Any?>,
  ) {
    val document = runtimeConfigEditService.readMachineConfigSnapshot().document
    if (document is ConfigDocumentRead.Unreadable) {
      state.completeText(
        "${document.path}: ${document.reason}\n",
        failurePayload(document.path, document.reason),
        exitCode = 1,
      )
      return
    }
    val observedPath = document.selectedPath()
    when (
      val result =
        runtimeConfigEditService.writeMachineConfigFromSnapshot(
          observedPath,
          listOf(MachineConfigEdit.SetExecutionMatrix(requested, selector.intent)),
          (document as? ConfigDocumentRead.Present)?.fingerprint,
        )
    ) {
      is ConfigWriteResult.Written -> {
        state.completeText(
          "Wrote ${selector.key}.${selector.entryKey} routing to ${result.path}.\n",
          mapOf(
            SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
            ConfigAgentsPayloadKeys.CONFIG_PATH to result.path.toString(),
          ),
        )
      }

      is ConfigWriteResult.Invalid -> {
        state.completeText(
          "${result.keyPath}: ${result.reason}\n",
          failurePayload(observedPath, "${result.keyPath}: ${result.reason}"),
          exitCode = 1,
        )
      }

      is ConfigWriteResult.ChangedOnDisk -> {
        state.completeText(
          "${result.path} changed on disk since it was read; nothing was written. Re-run the command to retry.\n",
          failurePayload(result.path, "config changed on disk"),
          exitCode = 1,
        )
      }

      is ConfigWriteResult.Unreadable -> {
        state.completeText(
          "${result.path}: ${result.reason}\n",
          failurePayload(result.path, result.reason),
          exitCode = 1,
        )
      }
    }
  }
}

@Inject
class ConfigAgentsSetCommand(
  runtimeConfigEditService: RuntimeConfigEditService,
  state: CliRunState,
) : ConfigAgentsWriteCommand(
    "set",
    "Persist a provider route for one tier or phase. " + ROUTING_HELP,
    runtimeConfigEditService,
    state,
  ) {
  private val agent by option(
    "--agent",
    help = "Provider id to route to (${SupportedAgent.entries.joinToString { it.id }}).",
  ).required()

  override fun run() {
    val selector = parseRoutingSelector(tier, phase)
    val provider =
      SupportedAgent.fromWireOrNull(agent.trim())
        ?: usageError(
          IllegalArgumentException(
            "Unknown provider '$agent'; supported ids: ${SupportedAgent.entries.joinToString { it.id }}.",
          ),
        )
    applyWrite(selector, mapOf(selector.key to mapOf(selector.entryKey to provider.id)))
  }
}

@Inject
class ConfigAgentsUnsetCommand(
  runtimeConfigEditService: RuntimeConfigEditService,
  state: CliRunState,
) : ConfigAgentsWriteCommand(
    "unset",
    "Remove an authored provider route for one tier or phase so it inherits again. " + ROUTING_HELP,
    runtimeConfigEditService,
    state,
  ) {
  override fun run() {
    val selector = parseRoutingSelector(tier, phase)
    applyWrite(selector, mapOf(selector.key to emptyMap<String, String>()))
  }
}
