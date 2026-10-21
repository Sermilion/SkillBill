package skillbill.installer

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class InstallerShellBuildIdentityTest {
  @Test
  fun `staged runtime and desktop from different builds fail before any live install changes`() {
    val repoRoot = Files.createTempDirectory("skillbill-identity-repo")
    val home = Files.createTempDirectory("skillbill-identity-home")
    val binDir = Files.createTempDirectory("skillbill-identity-bin")
    val logPath = Files.createTempFile("skillbill-identity-runtime", ".log")
    seedPrebuiltRepo(repoRoot)
    InstallerDesktopFixtures.writeBuildIdentity(
      repoRoot.resolve("runtime-kotlin/runtime-cli/build/install/runtime-cli"),
      "commit=aaaaaaaaaaaaaaaa\ndirty=false\nrelease_tag=\ninstall_run_id=\n",
    )
    val liveRuntime = home.resolve(".skill-bill/runtime")
    listOf("runtime-cli", "runtime-mcp").forEach { name ->
      Files.createDirectories(liveRuntime.resolve(name))
      Files.writeString(liveRuntime.resolve("$name/marker"), "live $name\n")
    }
    val liveDesktop = home.resolve("desktop-apps")
    InstallerDesktopFixtures.seedDistribution(liveDesktop, "commit=live\ndirty=false\nrelease_tag=\ninstall_run_id=\n")
    val before = snapshot(liveRuntime) + snapshot(liveDesktop)

    val process =
      ProcessBuilder("bash", repoRoot.resolve("install.sh").toString(), "--from-source")
        .directory(repoRoot.toFile())
        .redirectErrorStream(true)
        .apply {
          environment()["HOME"] = home.toString()
          environment()["SKILL_BILL_BIN_DIR"] = binDir.toString()
          environment()["SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD"] = "1"
          environment()["SKILL_BILL_SKIP_PREINSTALL_UNINSTALL"] = "1"
          environment()["SKILL_BILL_TEST_RUNTIME_LOG"] = logPath.toString()
          environment().remove("SKILL_BILL_GOAL_CONTINUATION")
          environment().installDesktopUnder(home)
        }
        .start()
    process.outputStream.bufferedWriter().use { writer -> writer.write("1\nclaude\noff\n") }
    val output = process.inputStream.bufferedReader().readText()

    assertNotEquals(0, process.waitFor(), output)
    assertContains(output, "Runtime:     commit aaaaaaaaaaaa")
    assertContains(output, "Desktop app: commit fixture")
    assertEquals(before, snapshot(liveRuntime) + snapshot(liveDesktop), output)
  }

  private fun snapshot(root: Path): Map<String, String> =
    Files.walk(root).use { paths ->
      paths.filter(Files::isRegularFile).toList().associate { path ->
        root.relativize(path).toString() to path.readText()
      }
    }
}
