package skillbill.cli.config

import com.github.ajalt.clikt.parameters.options.option
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
import skillbill.scaffold.policy.platformpack.model.PlatformPackSourceKind

@Inject
class ConfigResolveExternalPlatformPacksCommand(
  private val service: ExternalPlatformPackResolutionService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "resolve-external-platform-packs",
    "Resolve the effective platform pack catalog from bundled packs and machine-global " +
      "external_platform_pack_sources. Config path precedence: SKILL_BILL_CONFIG_PATH, then " +
      "~/.config/skill-bill/config.json, then ~/.skill-bill/config.json.",
  ) {
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root whose platform-packs/ directory supplies bundled packs.",
  )

  override fun run() {
    val resolvedRepoRoot = resolveCliRepositoryRoot(repoRoot, inputs)
    val catalog =
      try {
        service.resolveEffectiveCatalog(resolvedRepoRoot, inputs.userHome, inputs.environment)
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.isShellContentContractFailure())
        state.completeText(
          "${error.message}\n",
          externalPlatformPackFailurePayload(error, sourceKind = PlatformPackSourceKind.EXTERNAL),
          exitCode = 1,
        )
        return
      }
    val lines =
      catalog.entries.map { entry ->
        val shadowed = entry.shadowedBundledSlug ?: "-"
        val loaded = entry.loaded
        "${loaded.manifest.slug}\t${loaded.sourceKind.wireValue}\t$shadowed\t${loaded.canonicalRoot}\n"
      }
    state.completeText(
      lines.joinToString(""),
      mapOf(
        SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
        ExternalPlatformPackResolutionPayloadKeys.PACKS to
          catalog.entries.map { entry ->
            mapOf(
              ExternalPlatformPackResolutionPayloadKeys.SLUG to entry.loaded.manifest.slug,
              ExternalPlatformPackResolutionPayloadKeys.SOURCE_KIND to entry.loaded.sourceKind.wireValue,
              ExternalPlatformPackResolutionPayloadKeys.SHADOWED_BUNDLED_SLUG to entry.shadowedBundledSlug,
              ExternalPlatformPackResolutionPayloadKeys.PATH to entry.loaded.canonicalRoot,
            )
          },
      ),
    )
  }
}
