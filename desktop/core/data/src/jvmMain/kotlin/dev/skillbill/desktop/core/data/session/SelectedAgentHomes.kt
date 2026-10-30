package dev.skillbill.desktop.core.data.session

import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.ports.system.HostAgentHomesPort
import java.nio.file.Path

/**
 * The Claude config directory the session was opened with; a change opens a new session, so it never blocks to re-read
 * settings. An app opened from the Dock has none of the shell's variables, so this is how its runs reach the right account.
 * The default `~/.claude` is sent as an empty value: Claude keeps that account's `.claude.json` beside the directory, not in
 * it, so it only works with the variable unset.
 */
internal class SelectedAgentHomes(private val claudeConfigDir: String?, userHome: String) : HostAgentHomesPort {
  private val defaultDirectory = Path.of(userHome, ".claude").normalize()

  override fun current(): Map<String, String> {
    val directory = claudeConfigDir ?: return emptyMap()
    val value = if (Path.of(directory).normalize() == defaultDirectory) "" else directory
    return mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to value)
  }
}
