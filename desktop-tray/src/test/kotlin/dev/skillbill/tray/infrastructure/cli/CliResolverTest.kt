package dev.skillbill.tray.infrastructure.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.nio.file.Files

class CliResolverTest {
  @Test
  fun `login path wins over the configured directory and explicit override wins over all`() {
    val root = Files.createTempDirectory("skillbill-cli-resolver")
    val processPath = Files.createDirectories(root.resolve("process"))
    val loginPath = Files.createDirectories(root.resolve("login"))
    val configured = Files.createDirectories(root.resolve("configured"))
    val override = Files.createDirectories(root.resolve("override"))
    listOf(loginPath, configured, override).forEach { Files.createFile(it.resolve("skill-bill")).toFile().setExecutable(true) }
    val resolver = CliResolver(environment = mapOf("PATH" to processPath.toString(), "SKILL_BILL_BIN_DIR" to configured.toString()),
      home = root.toString(), loginPathProvider = { loginPath.toString() })
    assertEquals(loginPath.resolve("skill-bill").toString(), resolver.resolve(null)?.single())
    assertEquals(override.resolve("skill-bill").toString(), resolver.resolve(override.resolve("skill-bill").toString())?.single())
    assertNull(resolver.resolve(root.resolve("missing").toString()))
  }

  @Test
  fun `Windows resolution includes batch launchers`() {
    val root = Files.createTempDirectory("skillbill-cli-windows")
    val bin = Files.createDirectories(root.resolve("bin"))
    val batch = Files.createFile(bin.resolve("skill-bill.cmd"))
    val resolver = CliResolver(environment = mapOf("PATH" to bin.toString()), home = root.toString(), loginPathProvider = { null }, windows = true)
    assertEquals(batch.toString(), resolver.resolve(null)?.single())
  }
}
