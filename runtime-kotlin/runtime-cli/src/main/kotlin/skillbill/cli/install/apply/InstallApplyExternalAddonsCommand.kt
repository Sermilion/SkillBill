package skillbill.cli.install.apply

import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.application.install.ExternalAddonOverlayService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import java.nio.file.Path

@Inject
class InstallApplyExternalAddonsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val service: ExternalAddonOverlayService,
) : DocumentedCliCommand(
    "apply-external-addons",
    "Apply the external addon overlay onto installed platform packs (after reconcile, before staging).",
  ) {
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root containing platform-packs/. Defaults to the current working directory.",
  )
  private val platformPacksRoot by option(
    "--platform-packs",
    help = "Platform packs root. Defaults to <repo-root>/platform-packs.",
  )

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "apply-external-addons")) {
      return
    }
    val resolvedRepoRoot = resolveCliRepositoryRoot(repoRoot, inputs)
    val resolvedPlatformPacks =
      platformPacksRoot?.let(Path::of)?.toAbsolutePath()?.normalize()
        ?: resolvedRepoRoot.resolve("platform-packs")
    val result =
      try {
        service.applyOverlay(resolvedPlatformPacks, inputs.userHome, inputs.environment)
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.isShellContentContractFailure())
        state.completeText(
          "${error.message}\n",
          mapOf(SharedPayloadKeys.STATUS to "failed", "error" to error.message.orEmpty()),
          exitCode = 1,
        )
        return
      }
    if (result.appliedSources.isEmpty() && result.skippedSources.isEmpty()) {
      state.completeText(
        "no external addon sources\n",
        mapOf(SharedPayloadKeys.STATUS to CliPayloadStatus.OK, "touched" to false),
      )
      return
    }
    val applied =
      result.appliedSources.joinToString("") { source ->
        "applied\t${source.platform}\t${source.sourcePath}\n"
      }
    val skipped =
      result.skippedSources.joinToString("") { source ->
        "skipped\t${source.platform}\t${source.sourcePath}\t${source.reason}\n"
      }
    state.completeText(
      applied + skipped,
      mapOf(
        SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
        "touched" to result.touched,
        "applied" to result.appliedSources.map { it.platform },
        "skipped" to result.skippedSources.map { it.platform },
      ),
    )
  }
}
