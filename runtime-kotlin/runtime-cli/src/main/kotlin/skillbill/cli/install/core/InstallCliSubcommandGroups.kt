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
import skillbill.cli.install.nativeagent.InstallLinkSkillCommand
import skillbill.cli.install.nativeagent.NativeAgentClaudeCliCommands
import skillbill.cli.install.nativeagent.NativeAgentCodexCliCommands
import skillbill.cli.install.nativeagent.NativeAgentCursorCliCommands

@Inject
class InstallPlanCliSubcommands(
  plan: InstallPlanCommand,
  apply: InstallApplyCommand,
  applyExternalAddons: InstallApplyExternalAddonsCommand,
  reconcile: InstallReconcileCommand,
  replayLastSelection: InstallReplayLastSelectionCommand,
  migrateLegacyMachineConfig: InstallMigrateLegacyMachineConfigCommand,
  migrateData: InstallMigrateDataCommand,
  clearState: InstallClearStateCommand,
) {
  val commands: List<CliktCommand> =
    listOf(
      plan,
      apply,
      applyExternalAddons,
      reconcile,
      replayLastSelection,
      migrateLegacyMachineConfig,
      migrateData,
      clearState,
    )
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
  cursorAgentsPath: InstallCursorAgentsPathCommand,
  cleanupAgentTarget: InstallCleanupAgentTargetCommand,
) {
  val commands: List<CliktCommand> =
    listOf(
      linkSkill,
      codexAgentsPath,
      claudeAgentsPath,
      cursorAgentsPath,
      cleanupAgentTarget,
    )
}

@Inject
class InstallNativeAgentCliSubcommands(
  claude: NativeAgentClaudeCliCommands,
  codex: NativeAgentCodexCliCommands,
  cursor: NativeAgentCursorCliCommands,
) {
  val commands: List<CliktCommand> = claude.commands + codex.commands + cursor.commands
}

@Inject
class InstallMcpCliSubcommands(
  registerMcp: InstallRegisterMcpCommand,
  unregisterMcp: InstallUnregisterMcpCommand,
) {
  val commands: List<CliktCommand> = listOf(registerMcp, unregisterMcp)
}
