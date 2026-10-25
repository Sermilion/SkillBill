package dev.skillbill.tray.core.data.runtime

import dev.skillbill.tray.core.common.HostEnvironment
import dev.skillbill.tray.core.data.session.DatabaseSelection
import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.testing.FakeSettingsRepository
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeAgentHomeAdapterTest {
  @Test
  fun `the default directory and every claude profile with a marker are listed in the runtime's order`() {
    val home = Files.createTempDirectory("agent-homes").toRealPath()
    Files.createDirectories(home.resolve(".claude-work"))
    Files.writeString(home.resolve(".claude-work/.claude.json"), "{}")
    Files.createDirectories(home.resolve(".claude-empty"))
    // A non-empty map: an empty one makes the runtime fall back to the host environment.
    val host = HostEnvironment(mapOf("SKILL_BILL_TEST" to "1"), home.toString(), home.toString())
    val selection = DatabaseSelection().also { it.choose(home.resolve("metrics.db").toString()) }
    val settings = FakeSettingsRepository(SettingsState.Ready(AppSettings()))
    val runtime = object : RuntimeSessionProviders {}.runtimeComponent(selection, host, settings)

    assertEquals(
      listOf(home.resolve(".claude").toString(), home.resolve(".claude-work").toString()),
      RuntimeAgentHomeAdapter(runtime, host).claudeConfigDirectories(),
    )
  }
}
