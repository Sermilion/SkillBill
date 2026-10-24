package skillbill.cli.config

import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.ports.config.ProjectConfigFilePort
import skillbill.ports.editor.FileEditorLauncherPort

object ConfigEditPayloadKeys {
  const val CONFIG_PATH: String = "config_path"
  const val OPENED: String = "opened"
}

@Inject
class ConfigEditCommand(
  private val projectConfigFile: ProjectConfigFilePort,
  private val editor: FileEditorLauncherPort,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "edit",
    "Open the project store config.yaml in \$VISUAL, \$EDITOR or the OS default application.",
  ) {
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root selecting the project whose store config.yaml is opened.",
  )

  override fun run() {
    val configPath = projectConfigFile.ensureConfigFile(resolveCliRepositoryRoot(repoRoot, inputs))
    val opened = editor.open(configPath, inputs.environment)
    state.completeText(
      "$configPath\n",
      mapOf(
        SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
        ConfigEditPayloadKeys.CONFIG_PATH to configPath.toString(),
        ConfigEditPayloadKeys.OPENED to opened,
      ),
    )
  }
}
