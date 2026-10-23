package skillbill.infrastructure.launcher.work

import skillbill.install.model.AGENT_LAUNCHER_CLIS
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DetachedWorkRunLauncherTest {
  @Test
  fun `cancellation after an app restart stops the saved harness and its child instead of allowing another attempt`() {
    val home = Files.createTempDirectory("cancel-native-harness")
    executable(
      home,
      """
      #!/bin/sh
      cat
      trap 'wait; exit 0' TERM
      sleep 30 &
      printf '%s' "${'$'}!" > child-pid
      wait
      touch unexpected-retry
      """.trimIndent(),
    )
    val request = harnessRequest(home, "Resume monitor")
    val first = launcher(home)
    assertIs<WorkRunLaunchResult.Started>(first.start(request))
    await { Files.exists(home.resolve("child-pid")) }
    val child = ProcessHandle.of(Files.readString(home.resolve("child-pid")).toLong()).orElseThrow()
    val reopened = launcher(home)
    assertIs<WorkRunLaunchResult.Refused>(reopened.start(request))
    assertIs<WorkRunStopResult.Stopped>(reopened.stop(WorkRunStopRequest(request.databasePath, request.ownershipKey)))
    await { !child.isAlive }
    assertFalse(Files.exists(home.resolve("unexpected-retry")))
    assertIs<WorkRunStopResult.NotRunning>(
      reopened.stop(WorkRunStopRequest(request.databasePath, request.ownershipKey)),
    )
  }

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
      val prompt = "Use the installed skill-bill skill to resume existing work"
      val request =
        WorkRunLaunchRequest(
          home,
          home.resolve("metrics.db"),
          agent.id,
          WorkRunLaunchKind.AgentHarness(agent.id, prompt),
        )
      val started = assertIs<WorkRunLaunchResult.Started>(launcher(home).start(request))
      await { Files.readString(started.logPath).contains(prompt) }
      assertTrue(Files.readString(started.logPath).startsWith("harness=${agent.id}\n"))
    }
    assertFalse(Files.exists(home.resolve("runtime-was-started")))
  }

  @Test
  fun `a missing harness refuses launch without falling back to runtime cli`() {
    val home = Files.createTempDirectory("missing-harness")
    val request = harnessRequest(home, "Resume")
    val refused = assertIs<WorkRunLaunchResult.Refused>(launcher(home).start(request))
    assertTrue(refused.reason.contains("claude harness"))
  }

  @Test
  fun `a runtime command starts the installed skill-bill directly with no prompt and the recorded agent home`() {
    val home = Files.createTempDirectory("runtime-work-launch")
    executable(home, "#!/bin/sh\ntouch harness-was-started\n")
    val runtime = home.resolve(".local/bin/skill-bill")
    Files.writeString(
      runtime,
      """
      #!/bin/sh
      printf '%s\n' "${'$'}@"
      printf 'database=%s\n' "${'$'}SKILL_BILL_REVIEW_DB"
      printf 'claude=%s\n' "${'$'}CLAUDE_CONFIG_DIR"
      printf 'stdin=%s\n' "${'$'}(cat)"
      printf 'done\n'
      """.trimIndent() + "\n",
    )
    assertTrue(runtime.toFile().setExecutable(true))
    val arguments = listOf("goal", "SKILL-9", "--agent", "claude", "--repo-root", home.toString(), "--no-live-output")
    val request =
      WorkRunLaunchRequest(
        home,
        home.resolve("metrics.db"),
        "work",
        WorkRunLaunchKind.RuntimeCommand(arguments),
        mapOf("CLAUDE_CONFIG_DIR" to "/operator/.claude-work"),
      )
    val started = assertIs<WorkRunLaunchResult.Started>(launcher(home).start(request))
    await { Files.readString(started.logPath).endsWith("done\n") }
    val output = Files.readString(started.logPath)
    assertTrue(output.startsWith(arguments.joinToString("\n") + "\n"), output)
    assertTrue(output.contains("database=${request.databasePath}\n"), output)
    assertTrue(output.contains("claude=/operator/.claude-work\n"), output)
    assertTrue(output.contains("stdin=\n"), output)
    assertFalse(Files.exists(home.resolve("harness-was-started")))
  }

  @Test
  fun `a runtime command finds the agent harness installed beside it when the tray PATH lacks that directory`() {
    val home = Files.createTempDirectory("runtime-work-path")
    executable(home, "#!/bin/sh\nprintf 'harness-ran\\n'\n")
    val runtime = home.resolve(".local/bin/skill-bill")
    Files.writeString(runtime, "#!/bin/sh\nclaude\nprintf 'done\\n'\n")
    assertTrue(runtime.toFile().setExecutable(true))
    val request =
      WorkRunLaunchRequest(
        home,
        home.resolve("metrics.db"),
        "work",
        WorkRunLaunchKind.RuntimeCommand(listOf("goal", "SKILL-9", "--agent", "claude")),
      )
    val started = assertIs<WorkRunLaunchResult.Started>(launcher(home).start(request))
    await { Files.readString(started.logPath).endsWith("done\n") }
    assertEquals("harness-ran\ndone\n", Files.readString(started.logPath))
  }

  @Test
  fun `a missing runtime refuses a runtime command without falling back to the agent harness`() {
    val home = Files.createTempDirectory("missing-runtime")
    executable(home, "#!/bin/sh\ntouch harness-was-started\n")
    val request =
      WorkRunLaunchRequest(
        home,
        home.resolve("metrics.db"),
        "work",
        WorkRunLaunchKind.RuntimeCommand(listOf("goal", "SKILL-9", "--agent", "claude")),
      )
    val refused = assertIs<WorkRunLaunchResult.Refused>(launcher(home).start(request))
    assertTrue(refused.reason.contains("skill-bill runtime is not installed"), refused.reason)
    assertFalse(Files.exists(home.resolve("harness-was-started")))
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
      val prompt = "Resume /skill-bill phase:monitor one two ${'$'}(touch injected)"
      val request = harnessRequest(home, prompt)
      val started = assertIs<WorkRunLaunchResult.Started>(launcher.start(request))
      val refused = assertIs<WorkRunLaunchResult.Refused>(launcher.start(request))
      assertTrue(refused.reason.contains("already"))
      assertFalse(Files.exists(home.resolve("injected")))
      Files.createFile(release)
      await { Files.readString(started.logPath).endsWith("done\n") }
      val output = Files.readString(started.logPath)
      assertTrue(output.contains("--print\n"))
      assertTrue(output.contains("database=${request.databasePath}\n"))
      assertTrue(output.contains(prompt))
      Files.writeString(executable, "#!/bin/sh\nexit 0\n")
      await { launcher.start(request) is WorkRunLaunchResult.Started }
    } finally {
      if (!Files.exists(release)) Files.createFile(release)
    }
  }

  @Test
  fun `the harness runs with the recorded agent home over the tray environment and ignores other recorded keys`() {
    val home = Files.createTempDirectory("recorded-agent-environment")
    executable(
      home,
      """
      #!/bin/sh
      printf 'claude=%s\n' "${'$'}CLAUDE_CONFIG_DIR"
      printf 'codex=%s\n' "${'$'}CODEX_HOME"
      printf 'token=%s\n' "${'$'}SKILLBILL_RECORDED_SECRET"
      cat
      printf 'done\n'
      """.trimIndent(),
    )
    val launcher =
      DetachedWorkRunLauncher(
        EnvironmentContext(
          environment =
            TRAY_ENVIRONMENT +
              mapOf(
                "CLAUDE_CONFIG_DIR" to "/tray/.claude",
                "CODEX_HOME" to "/tray/.codex",
              ),
          userHome = home,
        ),
        NoopRuntimeDiagnostics,
        FixedProjectStorePort(home.resolve("project-store")),
      )
    val request =
      harnessRequest(home, "Resume monitor").copy(
        agentEnvironment =
          mapOf("CLAUDE_CONFIG_DIR" to "/operator/.claude-work", "SKILLBILL_RECORDED_SECRET" to "recorded-token"),
      )
    val started = assertIs<WorkRunLaunchResult.Started>(launcher.start(request))
    await { Files.readString(started.logPath).endsWith("done\n") }
    val output = Files.readString(started.logPath)
    assertTrue(output.contains("claude=/operator/.claude-work\n"), output)
    assertTrue(output.contains("codex=/tray/.codex\n"), output)
    assertTrue(output.contains("token=\n"), output)
  }

  @Test
  fun `an immediate runner failure exposes its log and releases the launch lock`() {
    val home = Files.createTempDirectory("failed-work-launch")
    executable(home, "#!/bin/sh\nprintf 'invalid intake\\n'\nexit 7\n")
    val launcher =
      DetachedWorkRunLauncher(
        EnvironmentContext(environment = TRAY_ENVIRONMENT, userHome = home),
        NoopRuntimeDiagnostics,
        Duration.ofSeconds(10),
        FixedProjectStorePort(home.resolve("project-store")),
      )
    val request = harnessRequest(home, "Resume monitor")
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

  private fun harnessRequest(
    home: Path,
    prompt: String,
  ) = WorkRunLaunchRequest(home, home.resolve("metrics.db"), "work", WorkRunLaunchKind.AgentHarness("claude", prompt))

  private fun launcher(home: Path) =
    DetachedWorkRunLauncher(
      EnvironmentContext(environment = TRAY_ENVIRONMENT, userHome = home),
      NoopRuntimeDiagnostics,
      FixedProjectStorePort(home.resolve("project-store")),
    )

  private fun await(condition: () -> Boolean) {
    val deadline = Instant.now().plusSeconds(3)
    while (!condition()) {
      assertTrue(Instant.now().isBefore(deadline), "The runner did not settle within three seconds.")
      Thread.sleep(10)
    }
  }

  private companion object {
    val TRAY_ENVIRONMENT = mapOf("PATH" to "/usr/bin:/bin")
  }
}
