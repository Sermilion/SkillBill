package skillbill.infrastructure.launcher.work

import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DetachedWorkRunLauncherTest {
  @Test
  fun `a running command retains its launch lock and receives literal arguments`() {
    val home = Files.createTempDirectory("work-launch")
    val release = home.resolve("release")
    val executable =
      executable(
        home,
        """
        #!/bin/sh
        printf '%s\n' "${'$'}@"
        while [ ! -f release ]; do sleep 0.05; done
        printf 'done\n'
        """.trimIndent(),
      )
    try {
      val launcher = launcher(home)
      val request =
        WorkRunLaunchRequest(
          home,
          home.resolve("metrics.db"),
          listOf("phase", "monitor", "one two", "${'$'}(touch injected)", "--agent", "codex"),
          "work",
        )
      val started = assertIs<WorkRunLaunchResult.Started>(launcher.start(request))
      val refused = assertIs<WorkRunLaunchResult.Refused>(launcher.start(request))
      assertTrue(refused.reason.contains("already"))
      assertFalse(Files.exists(home.resolve("injected")))
      Files.createFile(release)
      await { Files.readString(started.logPath).endsWith("done\n") }
      assertEquals(
        listOf("--db", request.databasePath.toString()) + request.arguments + "done",
        Files.readAllLines(started.logPath),
      )
      Files.writeString(executable, "#!/bin/sh\nexit 0\n")
      await { launcher.start(request) is WorkRunLaunchResult.Started }
    } finally {
      if (!Files.exists(release)) Files.createFile(release)
    }
  }

  @Test
  fun `an immediate runner failure exposes its log and releases the launch lock`() {
    val home = Files.createTempDirectory("failed-work-launch")
    executable(home, "#!/bin/sh\nprintf 'invalid intake\\n'\nexit 7\n")
    val launcher = launcher(home)
    val request = WorkRunLaunchRequest(home, home.resolve("metrics.db"), listOf("phase", "monitor"), "work")
    repeat(2) {
      val result = assertIs<WorkRunLaunchResult.Refused>(launcher.start(request))
      assertTrue(result.reason.contains("exited before starting"), result.reason)
      assertTrue(result.reason.contains(".log"))
    }
  }

  private fun executable(
    home: Path,
    script: String,
  ): Path {
    val executable = home.resolve(".skill-bill/runtime/runtime-cli/bin/runtime-cli")
    Files.createDirectories(executable.parent)
    Files.writeString(executable, script + "\n")
    assertTrue(executable.toFile().setExecutable(true))
    return executable
  }

  private fun launcher(home: Path) =
    DetachedWorkRunLauncher(
      EnvironmentContext(environment = emptyMap(), userHome = home),
      NoopRuntimeDiagnostics,
    )

  private fun await(condition: () -> Boolean) {
    val deadline = Instant.now().plusSeconds(3)
    while (!condition()) {
      assertTrue(Instant.now().isBefore(deadline), "The runner did not settle within three seconds.")
      Thread.sleep(10)
    }
  }
}
