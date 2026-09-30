package skillbill.cli.install.core

import com.github.ajalt.clikt.core.CliktCommand
import me.tatarka.inject.annotations.Inject
import skillbill.cli.install.apply.InstallApplyExternalAddonsCommand
import skillbill.cli.install.mcp.InstallRegisterMcpCommand
import skillbill.cli.install.mcp.InstallUnregisterMcpCommand
import skillbill.cli.install.nativeagent.InstallAgentPathCommand
import skillbill.cli.install.nativeagent.InstallClaudeAgentsPathCommand
import skillbill.cli.install.nativeagent.InstallClaudeRootsCommand
import skillbill.cli.install.nativeagent.InstallCleanupAgentTargetCommand
import skillbill.cli.install.nativeagent.InstallCodexAgentsPathCommand
import skillbill.cli.install.nativeagent.InstallCodexRootsCommand
import skillbill.cli.install.nativeagent.InstallCursorAgentsPathCommand
import skillbill.cli.install.nativeagent.InstallDetectAgentsCommand
import skillbill.cli.install.nativeagent.InstallJunieAgentsPathCommand
import skillbill.cli.install.nativeagent.InstallLinkSkillCommand
import skillbill.cli.install.nativeagent.NativeAgentClaudeCliCommands
import skillbill.cli.install.nativeagent.NativeAgentCodexCliCommands
import skillbill.cli.install.nativeagent.NativeAgentCursorCliCommands
import skillbill.cli.install.nativeagent.NativeAgentJunieCliCommands

@Inject
class InstallPlanCliSubcommands(
  plan: InstallPlanCommand,
  apply: InstallApplyCommand,
  applyExternalAddons: InstallApplyExternalAddonsCommand,
  reconcile: InstallReconcileCommand,
  replayLastSelection: InstallReplayLastSelectionCommand,
) {
  val commands: List<CliktCommand> = listOf(plan, apply, applyExternalAddons, reconcile, replayLastSelection)
}

@Inject
class InstallAgentDiscoveryCliSubcommands(
  agentPath: InstallAgentPathCommand,
  detectAgents: InstallDetectAgentsCommand,
  claudeRoots: InstallClaudeRootsCommand,
  codexRoots: InstallCodexRootsCommand,
) {
  val commands: List<CliktCommand> = listOf(agentPath, detectAgents, claudeRoots, codexRoots)
}

@Inject
class InstallAgentPathsCliSubcommands(
  linkSkill: InstallLinkSkillCommand,
  codexAgentsPath: InstallCodexAgentsPathCommand,
  claudeAgentsPath: InstallClaudeAgentsPathCommand,
  junieAgentsPath: InstallJunieAgentsPathCommand,
  cursorAgentsPath: InstallCursorAgentsPathCommand,
  cleanupAgentTarget: InstallCleanupAgentTargetCommand,
) {
  val commands: List<CliktCommand> =
    listOf(
      linkSkill,
      codexAgentsPath,
      claudeAgentsPath,
      junieAgentsPath,
      cursorAgentsPath,
      cleanupAgentTarget,
    )
}

@Inject
class InstallNativeAgentCliSubcommands(
  claude: NativeAgentClaudeCliCommands,
  codex: NativeAgentCodexCliCommands,
  junie: NativeAgentJunieCliCommands,
  cursor: NativeAgentCursorCliCommands,
) {
  val commands: List<CliktCommand> = claude.commands + codex.commands + junie.commands + cursor.commands
}

@Inject
class InstallMcpCliSubcommands(
  registerMcp: InstallRegisterMcpCommand,
  unregisterMcp: InstallUnregisterMcpCommand,
) {
  val commands: List<CliktCommand> = listOf(registerMcp, unregisterMcp)
}
