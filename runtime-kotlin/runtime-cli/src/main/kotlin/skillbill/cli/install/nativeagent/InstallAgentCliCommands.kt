package skillbill.cli.install.nativeagent

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.InstallAgentService
import skillbill.cli.install.apply.refuseInstallMutationDuringGoalContinuation
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.model.CliRunInputs
import java.nio.file.Path

@Inject
class InstallCleanupAgentTargetCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("cleanup-agent-target", "Remove Skill Bill symlinks and managed dirs from one agent path.") {
  private val targetDir by option("--target-dir", help = "Agent install directory.").required()
  private val skillNames by option("--skill-name", help = "Current skill name to remove.").multiple()
  private val legacyNames by option("--legacy-name", help = "Legacy skill name to remove.").multiple()
  private val marker by option("--marker", help = "Managed install marker file.").default(".skill-bill-install")

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "cleanup-agent-target")) {
      return
    }
    val cleanup =
      installAgentService.cleanupAgentTarget(
        targetDir = Path.of(targetDir),
        skillNames = skillNames,
        legacyNames = legacyNames,
        managedInstallMarker = marker,
        home = inputs.userHome,
      )
    state.completeText(
      (
        cleanup.removed.map { path -> "removed\t$path" } +
          cleanup.skipped.map { path -> "skipped\t$path" }
      ).joinToString("\n"),
      mapOf("removed" to cleanup.removed.map(Path::toString), "skipped" to cleanup.skipped.map(Path::toString)),
    )
  }
}

@Inject
class InstallClaudeRootsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("claude-roots", "Print every resolved Claude config root, one per line.") {
  override fun run() {
    val roots = installAgentService.claudeRoots(inputs.userHome, inputs.environment)
    state.completeText(
      roots.joinToString("\n") { root -> root.toString() },
      mapOf("roots" to roots.map(Path::toString)),
    )
  }
}

@Inject
class InstallCodexRootsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("codex-roots", "Print every resolved Codex config root, one per line.") {
  override fun run() {
    val roots = installAgentService.codexRoots(inputs.userHome, inputs.environment)
    state.completeText(
      roots.joinToString("\n") { root -> root.toString() },
      mapOf("roots" to roots.map(Path::toString)),
    )
  }
}

@Inject
class InstallCodexAgentsPathCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("codex-agents-path", "Print the Codex native subagent TOML directory.") {
  override fun run() {
    state.completeText(installAgentService.codexAgentsPath(inputs.userHome, inputs.environment).toString(), emptyMap())
  }
}

@Inject
class InstallClaudeAgentsPathCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("claude-agents-path", "Print the Claude native subagent markdown directory.") {
  override fun run() {
    state.completeText(installAgentService.claudeAgentsPath(inputs.userHome).toString(), emptyMap())
  }
}

@Inject
class InstallCursorAgentsPathCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("cursor-agents-path", "Print the Cursor native subagent markdown directory.") {
  override fun run() {
    state.completeText(installAgentService.cursorAgentsPath(inputs.userHome).toString(), emptyMap())
  }
}
