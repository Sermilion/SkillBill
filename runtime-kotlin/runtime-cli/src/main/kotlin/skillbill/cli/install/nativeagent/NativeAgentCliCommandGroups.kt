package skillbill.cli.install.nativeagent

import com.github.ajalt.clikt.core.CliktCommand
import me.tatarka.inject.annotations.Inject

@Inject
class NativeAgentClaudeCliCommands(
  link: InstallLinkClaudeAgentsCommand,
  unlink: InstallUnlinkClaudeAgentsCommand,
) {
  val commands: List<CliktCommand> = listOf(link, unlink)
}

@Inject
class NativeAgentCodexCliCommands(
  link: InstallLinkCodexAgentsCommand,
  unlink: InstallUnlinkCodexAgentsCommand,
) {
  val commands: List<CliktCommand> = listOf(link, unlink)
}

@Inject
class NativeAgentCursorCliCommands(
  link: InstallLinkCursorAgentsCommand,
  unlink: InstallUnlinkCursorAgentsCommand,
) {
  val commands: List<CliktCommand> = listOf(link, unlink)
}

@Inject
class NativeAgentOpenCodeCliCommands(
  link: InstallLinkOpenCodeAgentsCommand,
  unlink: InstallUnlinkOpenCodeAgentsCommand,
) {
  val commands: List<CliktCommand> = listOf(link, unlink)
}

@Inject
class NativeAgentDevinCliCommands(
  link: InstallLinkDevinAgentsCommand,
  unlink: InstallUnlinkDevinAgentsCommand,
) {
  val commands: List<CliktCommand> = listOf(link, unlink)
}
