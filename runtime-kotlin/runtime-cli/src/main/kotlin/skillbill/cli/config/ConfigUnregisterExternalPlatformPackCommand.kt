package skillbill.cli.config

import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.install.ExternalPlatformPackResolutionService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.install.model.ExternalPlatformPackSource
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.policy.platformpack.model.PlatformPackSourceKind

@Inject
class ConfigUnregisterExternalPlatformPackCommand(
  private val service: ExternalPlatformPackResolutionService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "unregister-external-platform-pack",
    "Remove one external platform pack registration by canonical pack root path. " +
      "Does not delete the author's pack directory. Config path precedence: SKILL_BILL_CONFIG_PATH, " +
      "then ~/.config/skill-bill/config.json, then ~/.skill-bill/config.json.",
  ) {
  private val packPath by option("--path", help = "Registered pack root path to remove.").required()

  override fun run() {
    val resolvedPath = service.canonicalPackRoot(inputs.userHome, packPath)
    val sources =
      try {
        service.unregisterSource(
          inputs.userHome,
          ExternalPlatformPackSource(resolvedPath.toFileLocation()),
          inputs.environment,
        )
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.isShellContentContractFailure())
        state.completeText(
          "${error.message}\n",
          externalPlatformPackFailurePayload(error, sourceKind = PlatformPackSourceKind.EXTERNAL),
          exitCode = 1,
        )
        return
      }
    state.completeText(
      "External platform pack registrations: ${sources.size}.\n",
      mapOf(SharedPayloadKeys.STATUS to CliPayloadStatus.OK, "count" to sources.size),
    )
  }
}
