package skillbill.infrastructure.skills.install.opencode

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenCodeRootsTest {
  @Test
  fun `explicit config file and configured skills directory resolve independently`(
    @TempDir root: Path,
  ) {
    val home = root.resolve("home")
    val xdg = root.resolve("xdg")
    val configured = root.resolve("extra/opencode")
    val working = root.resolve("work/project")
    val roots =
      opencodeConfigRoots(
        home,
        mapOf(
          "XDG_CONFIG_HOME" to xdg.toString(),
          "OPENCODE_CONFIG_DIR" to configured.toString(),
          "OPENCODE_CONFIG" to "settings/opencode.jsonc",
          "PWD" to working.toString(),
        ),
      )

    assertEquals(configured.resolve("skills"), roots.skillDirectory)
    assertEquals(working.resolve("settings/opencode.jsonc"), roots.explicitConfigFile)
    assertEquals(xdg.resolve("opencode/config.json"), roots.knownConfigSources.first())
  }
}
