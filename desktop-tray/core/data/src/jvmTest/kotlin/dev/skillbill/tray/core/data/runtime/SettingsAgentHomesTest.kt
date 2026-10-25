package dev.skillbill.tray.core.data.runtime

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.testing.FakeSettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsAgentHomesTest {
  @Test
  fun `a chosen directory becomes the claude config dir`() {
    val settings = FakeSettingsRepository(SettingsState.Ready(AppSettings(claudeConfigDir = "/Users/someone/.claude-work")))
    assertEquals(mapOf("CLAUDE_CONFIG_DIR" to "/Users/someone/.claude-work"), SettingsAgentHomes(settings, HOME).current())
  }

  @Test
  fun `the default claude directory is sent empty so the variable is removed`() {
    val settings = FakeSettingsRepository(SettingsState.Ready(AppSettings(claudeConfigDir = "/Users/someone/.claude")))
    assertEquals(mapOf("CLAUDE_CONFIG_DIR" to ""), SettingsAgentHomes(settings, HOME).current())
  }

  @Test
  fun `no choice or unreadable settings leave the app environment alone`() {
    val unset = FakeSettingsRepository(SettingsState.Ready(AppSettings()))
    val unreadable = FakeSettingsRepository(SettingsState.Failed(Problem(ProblemCode.SETTINGS_CORRUPT, "corrupt")))
    assertEquals(emptyMap(), SettingsAgentHomes(unset, HOME).current())
    assertEquals(emptyMap(), SettingsAgentHomes(unreadable, HOME).current())
  }

  @Test
  fun `a change applies to the next launch without a new session`() {
    val settings = FakeSettingsRepository(SettingsState.Ready(AppSettings()))
    val homes = SettingsAgentHomes(settings, HOME)
    settings.publish(SettingsState.Ready(AppSettings(claudeConfigDir = "/opt/claude")))
    assertEquals(mapOf("CLAUDE_CONFIG_DIR" to "/opt/claude"), homes.current())
  }

  private companion object {
    const val HOME = "/Users/someone"
  }
}
