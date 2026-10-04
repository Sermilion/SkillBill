package skillbill.cli.config

import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigRequest

@Inject
class ConfigResolveExternalAgentAddonsCommand(
  private val config: ExternalAgentAddonSourceConfigPort,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "resolve-external-agent-addons",
    "Resolve external agent add-on sources from the machine-global config.json.",
  ) {
  override fun run() {
    val sources =
      try {
        config.readExternalAgentAddonSources(
          ExternalAgentAddonSourceConfigRequest(inputs.userHome, inputs.environment),
        ).sources
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.isShellContentContractFailure())
        state.completeText(
          "${error.message}\n",
          mapOf(SharedPayloadKeys.STATUS to "failed", "error" to error.message.orEmpty()),
          exitCode = 1,
        )
        return
      }
    val text = sources.joinToString("") { source -> "${source.path}\n" }
    state.completeText(
      text,
      mapOf(
        SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
        "sources" to sources.map { source -> mapOf("path" to source.path.toString()) },
      ),
    )
  }
}
