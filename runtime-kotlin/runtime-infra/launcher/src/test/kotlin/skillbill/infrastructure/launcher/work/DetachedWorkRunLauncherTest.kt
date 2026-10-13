package skillbill.infrastructure.launcher.work

import skillbill.install.model.AGENT_LAUNCHER_CLIS
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DetachedWorkRunLauncherTest {
  @Test
  fun `launches use the selected installed harness even when runtime cli is present`() {
    val home = Files.createTempDirectory("native-work-launch")
    val runtime = home.resolve(".skill-bill/runtime/runtime-cli/bin/runtime-cli")
    Files.createDirectories(runtime.parent)
    Files.writeString(runtime, "#!/bin/sh\ntouch runtime-was-started\n")
    runtime.toFile().setExecutable(true)
    for (agent in SupportedAgent.entries) {
      val harness = home.resolve(".local/bin/${AGENT_LAUNCHER_CLIS.getValue(agent).executables.first()}")
      Files.createDirectories(harness.parent)
      Files.writeString(harness, "#!/bin/sh\nprintf 'harness=${agent.id}\\n'\nprintf '%s\\n' \"${'$'}@\"\ncat\n")
      harness.toFile().setExecutable(true)
      val request =
        WorkRunLaunchRequest(
          home,
          home.resolve("metrics.db"),
          agent.id,
          "Use the installed skill-bill skill to resume existing work",
          agent.id,
        )
      val started = assertIs<WorkRunLaunchResult.Started>(launcher(home).start(request))
      await { Files.readString(started.logPath).contains(request.prompt) }
      assertTrue(Files.readString(started.logPath).startsWith("harness=${agent.id}\n"))
    }
    assertFalse(Files.exists(home.resolve("runtime-was-started")))
  }

  @Test
  fun `a missing harness refuses launch without falling back to runtime cli`() {
    val home = Files.createTempDirectory("missing-harness")
    val request = WorkRunLaunchRequest(home, home.resolve("metrics.db"), "claude", "Resume", "work")
    val refused = assertIs<WorkRunLaunchResult.Refused>(launcher(home).start(request))
    assertTrue(refused.reason.contains("claude harness"))
  }

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
        printf 'database=%s\n' "${'$'}SKILL_BILL_REVIEW_DB"
        cat
        printf '\n'
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
          "claude",
          "Resume /skill-bill phase:monitor one two ${'$'}(touch injected)",
          "work",
        )
      val started = assertIs<WorkRunLaunchResult.Started>(launcher.start(request))
      val refused = assertIs<WorkRunLaunchResult.Refused>(launcher.start(request))
      assertTrue(refused.reason.contains("already"))
      assertFalse(Files.exists(home.resolve("injected")))
      Files.createFile(release)
      await { Files.readString(started.logPath).endsWith("done\n") }
      val output = Files.readString(started.logPath)
      assertTrue(output.contains("--print\n"))
      assertTrue(output.contains("database=${request.databasePath}\n"))
      assertTrue(output.contains(request.prompt))
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
    val request = WorkRunLaunchRequest(home, home.resolve("metrics.db"), "claude", "Resume monitor", "work")
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
    val executable = home.resolve(".local/bin/claude")
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
