package dev.skillbill.desktop.core.data.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopTempRootTest {
  @Test
  fun `the shared tmp directory yields the state scratch path`(@TempDir state: Path) {
    val root = desktopTempRoot("/tmp", mapOf("SKILL_BILL_STATE_DIR" to state.toString()), "/unused")
    assertEquals(state.resolve("scratch").resolve("jvm"), root)
    assertTrue(Files.isDirectory(root))
  }

  @Test
  fun `an existing directory other than the shared tmp comes back unchanged`(@TempDir configured: Path, @TempDir state: Path) {
    assertEquals(configured, desktopTempRoot(configured.toString(), mapOf("SKILL_BILL_STATE_DIR" to state.toString()), "/unused"))
  }

  @Test
  fun `a blank state directory variable falls back to the home path`(@TempDir home: Path) {
    assertEquals(home.resolve(".skill-bill").resolve("scratch").resolve("jvm"), desktopTempRoot("/tmp", mapOf("SKILL_BILL_STATE_DIR" to "  "), home.toString()))
  }

  @Test
  fun `a state path blocked by a file returns the configured path instead of throwing`(@TempDir parent: Path) {
    val blocked = Files.createFile(parent.resolve("blocked"))
    assertEquals(Path.of("/tmp"), desktopTempRoot("/tmp", mapOf("SKILL_BILL_STATE_DIR" to blocked.toString()), "/unused"))
  }
}
