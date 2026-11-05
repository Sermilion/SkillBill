package skillbill.infrastructure.host.jvm

import org.junit.jupiter.api.io.TempDir
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeTempRootTest {
  @TempDir
  lateinit var workspace: Path

  private val home: Path get() = workspace.resolve("home")
  private val state: Path get() = workspace.resolve("state")
  private val stateScratch: Path get() = state.resolve("scratch").resolve("jvm")
  private val environment: Map<String, String> get() = mapOf(STATE_DIR_ENVIRONMENT_KEY to state.toString())

  @Test
  fun `the shared root resolves to the state scratch directory`() {
    assertEquals(stateScratch, resolveRuntimeTempRoot("/tmp", environment, home))
    assertTrue(Files.isDirectory(stateScratch))
  }

  @Test
  fun `a symlink to the shared root resolves to the state scratch directory`() {
    val link = Files.createSymbolicLink(workspace.resolve("tmp-link"), Path.of("/tmp"))

    assertEquals(stateScratch, resolveRuntimeTempRoot(link.toString(), environment, home))
  }

  @Test
  fun `an existing private directory is returned unchanged`() {
    val configured = Files.createDirectory(workspace.resolve("run-temp"))

    assertEquals(configured, resolveRuntimeTempRoot(configured.toString(), environment, home))
  }

  @Test
  fun `a configured path that is not a directory resolves to the state scratch directory`() {
    val missing = workspace.resolve("missing-temp-root")
    val regularFile = Files.writeString(workspace.resolve("temp-root-file"), "not a directory")

    assertEquals(stateScratch, resolveRuntimeTempRoot(missing.toString(), environment, home))
    assertEquals(stateScratch, resolveRuntimeTempRoot(regularFile.toString(), environment, home))
  }

  @Test
  fun `a failed create returns the configured path`() {
    Files.writeString(state, "not a directory")
    val missing = workspace.resolve("missing-temp-root")

    assertEquals(missing, resolveRuntimeTempRoot(missing.toString(), environment, home))
  }

  @Test
  fun `a failed create with the shared root configured returns the shared root`() {
    Files.writeString(state, "not a directory")

    assertEquals(Path.of("/tmp"), resolveRuntimeTempRoot("/tmp", environment, home))
  }

  @Test
  fun `a blank configured value with a failed create returns the shared root`() {
    Files.writeString(state, "not a directory")

    assertEquals(Path.of("/tmp"), resolveRuntimeTempRoot(" ", environment, home))
  }
}
