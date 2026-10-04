package skillbill.cli.install.core

import com.github.ajalt.clikt.core.subcommands
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.DocumentedNoOpCliCommand

@Inject
class InstallTopLevelCommands(
  plan: InstallPlanCliSubcommands,
  discovery: InstallAgentDiscoveryCliSubcommands,
  agentPaths: InstallAgentPathsCliSubcommands,
  nativeAgents: InstallNativeAgentCliSubcommands,
  mcp: InstallMcpCliSubcommands,
) {
  val command: DocumentedNoOpCliCommand =
    object : DocumentedNoOpCliCommand(
      "install",
      "Install-side primitives (agent paths, symlinks, native subagents, MCP registration).",
    ) {}
      .subcommands(plan.commands + discovery.commands + agentPaths.commands + nativeAgents.commands + mcp.commands)
}
