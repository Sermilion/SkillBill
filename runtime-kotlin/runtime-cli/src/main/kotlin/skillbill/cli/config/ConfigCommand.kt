package skillbill.cli.config

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.application.config.ConfigResolutionService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.DocumentedNoOpCliCommand
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.cli.model.CliRunInputs
import skillbill.config.model.SpecType
import skillbill.config.model.parseSpecType
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure

@Inject
class ConfigCommand(
  resolveSpecTypeCommand: ConfigResolveSpecTypeCommand,
  resolveExternalAddonsCommand: ConfigResolveExternalAddonsCommand,
  resolveExternalAgentAddonsCommand: ConfigResolveExternalAgentAddonsCommand,
  resolveExternalPlatformPacksCommand: ConfigResolveExternalPlatformPacksCommand,
  registerExternalPlatformPackCommand: ConfigRegisterExternalPlatformPackCommand,
  unregisterExternalPlatformPackCommand: ConfigUnregisterExternalPlatformPackCommand,
) : DocumentedNoOpCliCommand(
    "config",
    "Inspect resolved repo-local configuration (.skill-bill/config.yaml).",
  ) {
  init {
    subcommands(
      resolveSpecTypeCommand,
      resolveExternalAddonsCommand,
      resolveExternalAgentAddonsCommand,
      resolveExternalPlatformPacksCommand,
      registerExternalPlatformPackCommand,
      unregisterExternalPlatformPackCommand,
    )
  }
}

@Inject
class ConfigResolveSpecTypeCommand(
  private val configResolutionService: ConfigResolutionService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "resolve-spec-type",
    "Resolve the effective spec-source mode (arg > config spec_type > local).",
  ) {
  private val arg by option(
    "--arg",
    help = "The service override value (local|linear). Blank or 'default' defers to config.",
  ).default("")
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root whose .skill-bill/config.yaml is read.",
  )

  override fun run() {
    val explicit = resolveExplicit() ?: return
    val resolved =
      try {
        configResolutionService.resolveSpecType(resolveCliRepositoryRoot(repoRoot, inputs), explicit.value)
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.isShellContentContractFailure())
        state.completeText("${error.message}\n", failurePayload(error.message), exitCode = 1)
        return
      }
    state.completeText(
      "${resolved.id}\n",
      mapOf(SharedPayloadKeys.STATUS to CliPayloadStatus.OK, "spec_type" to resolved.id),
    )
  }

  private fun resolveExplicit(): ExplicitArg? {
    val normalized = arg.trim().lowercase()
    if (normalized.isEmpty() || normalized == "default") return ExplicitArg(null)
    return parseSpecType(normalized)?.let { parsed -> ExplicitArg(parsed) }
      ?: run {
        state.completeText(
          "Unrecognized service value '$arg'. Supported: ${SpecType.supportedIds.joinToString()}, default.\n",
          failurePayload("Unrecognized service value '$arg'."),
          exitCode = 1,
        )
        null
      }
  }

  private fun failurePayload(message: String?): Map<String, Any?> =
    mapOf(SharedPayloadKeys.STATUS to "failed", "error" to message.orEmpty())

  private data class ExplicitArg(
    val value: SpecType?,
  )
}
