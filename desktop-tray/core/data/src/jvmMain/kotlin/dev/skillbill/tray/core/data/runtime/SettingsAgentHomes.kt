package dev.skillbill.tray.core.data.runtime

import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.ports.system.HostAgentHomesPort
import java.nio.file.Path

/**
 * The Claude config directory chosen in settings, read at each launch so a change applies to the next run without reopening
 * the database. An app opened from the Dock has none of the shell's variables, so this is how its runs reach the right account.
 * The default `~/.claude` is sent as an empty value: Claude keeps that account's `.claude.json` beside the directory, not in
 * it, so it only works with the variable unset.
 */
internal class SettingsAgentHomes(private val settings: SettingsRepository, userHome: String) : HostAgentHomesPort {
  private val defaultDirectory = Path.of(userHome, ".claude").normalize()

  override fun current(): Map<String, String> {
    val state = runBlocking { settings.settings.first { it !is SettingsState.Loading } }
    val directory = (state as? SettingsState.Ready)?.settings?.claudeConfigDir ?: return emptyMap()
    val value = if (Path.of(directory).normalize() == defaultDirectory) "" else directory
    return mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to value)
  }
}
