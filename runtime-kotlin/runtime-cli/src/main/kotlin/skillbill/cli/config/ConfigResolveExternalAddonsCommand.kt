package skillbill.cli.config

import me.tatarka.inject.annotations.Inject
import skillbill.application.install.ExternalAddonOverlayService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure

@Inject
class ConfigResolveExternalAddonsCommand(
  private val service: ExternalAddonOverlayService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "resolve-external-addons",
    "Resolve external addon sources from the machine-global ~/.skill-bill/config.json.",
  ) {
  override fun run() {
    val sources =
      try {
        service.resolveSources(inputs.userHome, inputs.environment)
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.isShellContentContractFailure())
        state.completeText(
          "${error.message}\n",
          mapOf(SharedPayloadKeys.STATUS to "failed", "error" to error.message.orEmpty()),
          exitCode = 1,
        )
        return
      }
    val text = sources.joinToString("") { source -> "${source.platform}\t${source.path}\n" }
    state.completeText(
      text,
      mapOf(
        SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
        "sources" to
          sources.map { source ->
            mapOf("platform" to source.platform, "path" to source.path.toString())
          },
      ),
    )
  }
}
