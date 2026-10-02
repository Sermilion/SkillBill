package skillbill.cli.config

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.install.ExternalPlatformPackResolutionService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.install.model.ExternalPlatformPackSource
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.policy.platformpack.externalPlatformPackTelemetryPayload
import skillbill.scaffold.policy.platformpack.model.PlatformPackSourceKind

internal fun externalPlatformPackFailurePayload(
  error: Throwable,
  slug: String? = null,
  sourceKind: PlatformPackSourceKind? = null,
): Map<String, Any?> =
  externalPlatformPackTelemetryPayload(error, slug, sourceKind) +
    mapOf(SharedPayloadKeys.STATUS to "failed")

@Inject
class ConfigRegisterExternalPlatformPackCommand(
  private val service: ExternalPlatformPackResolutionService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "register-external-platform-pack",
    "Register an existing conforming platform pack root in external_platform_pack_sources. " +
      "Config path precedence: SKILL_BILL_CONFIG_PATH, then ~/.config/skill-bill/config.json, " +
      "then ~/.skill-bill/config.json.",
  ) {
  private val packPath by option("--path", help = "Pack root directory containing platform.yaml.").required()
  private val dryRun by option("--dry-run", help = "Report the planned config change without writing.").flag()
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root used to resolve composition dependencies when validating the pack.",
  )

  override fun run() {
    val resolvedPath = service.canonicalPackRoot(inputs.userHome, packPath)
    val resolvedRepoRoot = resolveCliRepositoryRoot(repoRoot, inputs)
    val slug =
      try {
        service.prepareExternalRegistration(
          resolvedRepoRoot,
          inputs.userHome,
          resolvedPath,
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
    if (dryRun) {
      state.completeText(
        "Would register ${resolvedPath.toAbsolutePath().normalize()} for platform pack '$slug'.\n",
        mapOf(SharedPayloadKeys.STATUS to CliPayloadStatus.OK, "dry_run" to true, "slug" to slug),
      )
      return
    }
    val sources =
      try {
        service.registerSource(
          inputs.userHome,
          ExternalPlatformPackSource(resolvedPath.toFileLocation()),
          inputs.environment,
        )
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.isShellContentContractFailure())
        state.completeText(
          "${error.message}\n",
          externalPlatformPackFailurePayload(error, slug, PlatformPackSourceKind.EXTERNAL),
          exitCode = 1,
        )
        return
      }
    state.completeText(
      "Registered ${sources.size} external platform pack source(s).\n",
      mapOf(SharedPayloadKeys.STATUS to CliPayloadStatus.OK, "slug" to slug, "count" to sources.size),
    )
  }
}
