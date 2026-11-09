package skillbill.infrastructure.launcher.work

import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.system.HostAgentHomesPort
import skillbill.ports.work.model.WorkRunExit
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DetachedWorkRunLauncherTest {
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
  fun `a runner that fails after startup hands back its log lines despite undecodable output`() {
    val home = Files.createTempDirectory("failed-run-log")
    executable(home, FAIL_WHEN_RELEASED, "skill-bill")
    val started =
      assertIs<WorkRunLaunchResult.Started>(launcherWithoutStartupGrace(home).start(runtimeRequest(home)))
    Files.createFile(home.resolve("release"))
    val finished = assertNotNull(started.exit).toCompletableFuture().get()
    assertEquals(3, finished.code)
    assertTrue(
      "Phase 'monitor' blocked at 'monitor': no origin." in finished.failureLogLines,
      "${finished.failureLogLines}",
    )
  }

  @Test
  fun `a runner that exits cleanly hands back no log lines`() {
    val home = Files.createTempDirectory("clean-run-log")
    executable(home, "#!/bin/sh\necho noise\nuntil [ -e release ]; do :; done", "skill-bill")
    val started =
      assertIs<WorkRunLaunchResult.Started>(launcherWithoutStartupGrace(home).start(runtimeRequest(home)))
    Files.createFile(home.resolve("release"))
    assertEquals(WorkRunExit(0, emptyList()), assertNotNull(started.exit).toCompletableFuture().get())
  }

  @Test
  fun `a runtime observed exited with 4 during startup awaits confirmation instead of failing`() {
    val home = Files.createTempDirectory("early-parked")
    executable(home, "#!/bin/sh\nexit 4", "skill-bill")

    val result =
      DetachedWorkRunLauncher(
        EnvironmentContext(environment = TRAY_ENVIRONMENT, userHome = home),
        NoopRuntimeDiagnostics,
        Duration.ZERO,
        HostAgentHomesPort { emptyMap() },
        awaitStartupExit = { process, _ ->
          process.waitFor()
          true
        },
      ).start(runtimeRequest(home))

    assertIs<WorkRunLaunchResult.AwaitingConfirmation>(result)
  }

  @Test
  fun `a runtime that exits 4 after startup finishes with exit 4 and is not a failure`() {
    val home = Files.createTempDirectory("late-parked")
    executable(home, "#!/bin/sh\nuntil [ -e release ]; do :; done\nexit 4", "skill-bill")
    val started =
      assertIs<WorkRunLaunchResult.Started>(launcherWithoutStartupGrace(home).start(runtimeRequest(home)))
    Files.createFile(home.resolve("release"))
    assertEquals(4, assertNotNull(started.exit).toCompletableFuture().get().code)
  }

  @Test
  fun `the operation launch id reaches the runtime command`() {
    val home = Files.createTempDirectory("launch-id")
    executable(home, "#!/bin/sh\nprintf '%s' \"\$SKILL_BILL_OPERATION_LAUNCH_ID\" > launch-id\nexit 0", "skill-bill")
    launcher(home).start(runtimeRequest(home).copy(operationLaunchId = "opl-9"))
    assertEquals("opl-9", recordedFile(home, "launch-id"))
  }

  @Test
  fun `new phase and operation iterations replace stopped owners but refuse an already running iteration`() {
    for (arguments in listOf(listOf("phase", "validation"), listOf("operation", "verify", "requirements"))) {
      val home = Files.createTempDirectory("new-runtime-iteration")
      executable(home, "#!/bin/sh\nuntil [ -e release ]; do :; done", "skill-bill")
      val runtime = launcherWithoutStartupGrace(home)
      val request = runtimeRequest(home).copy(kind = WorkRunLaunchKind.RuntimeCommand(arguments))
      val first = assertIs<WorkRunLaunchResult.Started>(runtime.start(request))
      try {
        assertIs<WorkRunLaunchResult.Refused>(runtime.start(request))
      } finally {
        Files.createFile(home.resolve("release"))
        assertEquals(0, assertNotNull(first.exit).toCompletableFuture().get().code)
      }
      Files.delete(home.resolve("release"))
      val next = assertIs<WorkRunLaunchResult.Started>(runtime.start(request))
      try {
        assertIs<WorkRunLaunchResult.Refused>(runtime.start(request))
      } finally {
        Files.createFile(home.resolve("release"))
        assertEquals(0, assertNotNull(next.exit).toCompletableFuture().get().code)
      }
    }
  }

  @Test
  fun `the host agent home reaches a runtime command when the run recorded none`() {
    val home = Files.createTempDirectory("host-agent-home")
    executable(home, RECORD_AGENT_HOME, "skill-bill")
    val hostHomes = HostAgentHomesPort { mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "/host/claude-work") }
    assertIs<WorkRunLaunchResult.Started>(launcher(home, hostHomes).start(runtimeRequest(home)))
    assertEquals("/host/claude-work", recordedAgentHome(home))
  }

  @Test
  fun `the host agent home wins over a recorded one`() {
    val home = Files.createTempDirectory("host-over-recorded")
    executable(home, RECORD_AGENT_HOME, "skill-bill")
    val hostHomes = HostAgentHomesPort { mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "/host/claude-work") }
    val recorded = mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "/recorded/claude")
    val request = runtimeRequest(home).copy(agentEnvironment = recorded)
    assertIs<WorkRunLaunchResult.Started>(launcher(home, hostHomes).start(request))
    assertEquals("/host/claude-work", recordedAgentHome(home))
  }

  @Test
  fun `a recorded agent home applies when the host chose none`() {
    val home = Files.createTempDirectory("recorded-agent-home")
    executable(home, RECORD_AGENT_HOME, "skill-bill")
    val recorded = mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "/recorded/claude")
    val request = runtimeRequest(home).copy(agentEnvironment = recorded)
    assertIs<WorkRunLaunchResult.Started>(launcher(home).start(request))
    assertEquals("/recorded/claude", recordedAgentHome(home))
  }

  @Test
  fun `an empty host agent home removes the variable so the agent uses its default home`() {
    val home = Files.createTempDirectory("default-agent-home")
    executable(home, RECORD_AGENT_HOME, "skill-bill")
    val hostHomes = HostAgentHomesPort { mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "") }
    val environment = TRAY_ENVIRONMENT + (AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "/tray/claude")
    val recorded = mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "/recorded/claude")
    val request = runtimeRequest(home).copy(agentEnvironment = recorded)
    assertIs<WorkRunLaunchResult.Started>(launcher(home, hostHomes, environment).start(request))
    assertEquals("", recordedAgentHome(home))
  }

  private fun recordedAgentHome(home: Path): String = recordedFile(home, "agent-home")

  private fun recordedFile(
    home: Path,
    name: String,
  ): String {
    val written = home.resolve(name)
    val deadline = System.nanoTime() + RECORD_TIMEOUT.toNanos()
    while (!Files.exists(written) && System.nanoTime() < deadline) {
      Thread.sleep(POLL_MILLIS)
    }
    return Files.readString(written)
  }

  private fun runtimeRequest(home: Path) =
    WorkRunLaunchRequest(
      home,
      home.resolve("metrics.db"),
      "work",
      WorkRunLaunchKind.RuntimeCommand(listOf("goal", "SKILL-9", "--agent", "claude")),
    )

  private fun executable(
    home: Path,
    script: String,
    name: String = "claude",
  ): Path {
    val executable = home.resolve(".local/bin/$name")
    Files.createDirectories(executable.parent)
    Files.writeString(executable, script + "\n")
    assertTrue(executable.toFile().setExecutable(true))
    return executable
  }

  private fun launcher(
    home: Path,
    agentHomes: HostAgentHomesPort = HostAgentHomesPort { emptyMap() },
    environment: Map<String, String> = TRAY_ENVIRONMENT,
  ) = DetachedWorkRunLauncher(
    EnvironmentContext(environment = environment, userHome = home),
    NoopRuntimeDiagnostics,
    agentHomes,
  )

  private fun launcherWithoutStartupGrace(home: Path) =
    DetachedWorkRunLauncher(
      EnvironmentContext(environment = TRAY_ENVIRONMENT, userHome = home),
      NoopRuntimeDiagnostics,
      Duration.ZERO,
      HostAgentHomesPort { emptyMap() },
    )

  private companion object {
    val TRAY_ENVIRONMENT = mapOf("PATH" to "/usr/bin:/bin")
    val RECORD_TIMEOUT: Duration = Duration.ofSeconds(10)
    const val POLL_MILLIS = 20L
    const val FAIL_WHEN_RELEASED =
      "#!/bin/sh\nprintf '\\377\\n'\necho \"Phase 'monitor' blocked at 'monitor': no origin.\"\n" +
        "until [ -e release ]; do :; done\nexit 3"
    const val RECORD_AGENT_HOME =
      "#!/bin/sh\nprintf '%s' \"\$CLAUDE_CONFIG_DIR\" > agent-home.tmp && mv agent-home.tmp agent-home"
  }
}
