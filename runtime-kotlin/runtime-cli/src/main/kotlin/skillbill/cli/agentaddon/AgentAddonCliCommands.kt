package skillbill.cli.agentaddon

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.cli.kernel.agent.ConfiguredAgentAddonSelectionResolver
import skillbill.cli.kernel.agent.parseAgentAddonSelection
import skillbill.cli.kernel.agent.toCliEntryMaps
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.DocumentedNoOpCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.agentaddon.AGENT_ADDON_SELECTION_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.ports.agentaddon.AgentAddonSelectionPort

@Inject
class AgentAddonCommand(
  resolveSelection: AgentAddonResolveSelectionCommand,
  verifySelection: AgentAddonVerifySelectionCommand,
) : DocumentedNoOpCliCommand("agent-addon", "Resolve and verify explicit agent add-on selections.") {
  init {
    subcommands(resolveSelection, verifySelection)
  }
}

@Inject
class AgentAddonResolveSelectionCommand(
  private val resolver: ConfiguredAgentAddonSelectionResolver,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("resolve-selection", "Resolve ordered agent-addon:<slug> tokens without side effects.") {
  private val tokens by option("--token", help = "Ordered agent-addon:<slug> token.").multiple()
  private val receivingAgents by option("--receiving-agent", help = "Agent that will receive the selection.").multiple()
  private val repoRoot by option("--repo-root", help = "Repository root. Defaults to the invocation repository root.")
  private val format by formatOption()

  override fun run() {
    val slugs =
      tokens.map { token ->
        if (!token.startsWith(PREFIX) || token.length == PREFIX.length) {
          throw UsageError("Malformed agent add-on token '$token'; expected agent-addon:<slug>.")
        }
        token.removePrefix(PREFIX)
      }
    complete {
      val selection =
        resolver.resolveInitial(
          repoRoot = resolveCliRepositoryRoot(repoRoot, inputs),
          requestedSlugs = slugs,
          receivingAgentIds = receivingAgents,
        )
      linkedMapOf(
        SharedPayloadKeys.CONTRACT_VERSION to AGENT_ADDON_SELECTION_CONTRACT_VERSION,
        FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ENTRIES to selection.toCliEntryMaps(),
      )
    }
  }

  private fun complete(block: () -> Map<String, Any?>) {
    try {
      state.complete(block(), format)
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      state.complete(
        mapOf(SharedPayloadKeys.STATUS to "failed", "error" to error.message.orEmpty()),
        format,
        exitCode = 1,
      )
    }
  }
}

@Inject
class AgentAddonVerifySelectionCommand(
  private val resolver: AgentAddonSelectionPort,
  private val state: CliRunState,
) : DocumentedCliCommand(
    "verify-selection",
    "Verify persisted identities and render the guarded prompt section.",
  ) {
  private val selectionJson by option(
    "--selection-json",
    help = "Strict resolved selection JSON.",
  ).default(EMPTY_SELECTION)
  private val receivingAgents by option("--receiving-agent").multiple()
  private val format by formatOption()

  override fun run() {
    try {
      val hydrated =
        resolver.verifyPersisted(
          parseAgentAddonSelection(selectionJson),
          AgentAddonConsumer.SKILL_BILL,
          receivingAgents,
        )
      state.complete(
        linkedMapOf(
          SharedPayloadKeys.CONTRACT_VERSION to AGENT_ADDON_SELECTION_CONTRACT_VERSION,
          FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ENTRIES to hydrated.toCliEntryMaps(),
          "prompt_section" to AgentAddonPromptFormatter.format(hydrated),
        ),
        format,
      )
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      state.complete(
        mapOf(SharedPayloadKeys.STATUS to "failed", "error" to error.message.orEmpty()),
        format,
        exitCode = 1,
      )
    }
  }
}

private const val PREFIX = "agent-addon:"
private const val EMPTY_SELECTION = "{\"contract_version\":\"$AGENT_ADDON_SELECTION_CONTRACT_VERSION\",\"entries\":[]}"
