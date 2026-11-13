package skillbill.infrastructure.launcher.work

import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
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
    executable(home, "#!/bin/sh\ntouch legacy-was-started\n", "skill-bill")
    val request =
      WorkRunLaunchRequest(
        home,
        home.resolve("metrics.db"),
        "work",
        WorkRunLaunchKind.RuntimeCommand(listOf("goal", "SKILL-9", "--agent", "claude")),
      )
    val refused = assertIs<WorkRunLaunchResult.Refused>(launcher(home).start(request))
    assertTrue(refused.reason.contains("sona runtime is not installed"), refused.reason)
    assertFalse(Files.exists(home.resolve("legacy-was-started")))
  }

  @Test
  fun `a runner that fails after startup hands back its log lines despite undecodable output`() {
    val home = Files.createTempDirectory("failed-run-log")
    executable(home, FAIL_WHEN_RELEASED, "sona")
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
    executable(home, "#!/bin/sh\necho noise\nuntil [ -e release ]; do :; done", "sona")
    val started =
      assertIs<WorkRunLaunchResult.Started>(launcherWithoutStartupGrace(home).start(runtimeRequest(home)))
    Files.createFile(home.resolve("release"))
    assertEquals(WorkRunExit(0, emptyList()), assertNotNull(started.exit).toCompletableFuture().get())
  }

  @Test
  fun `a runtime observed exited with 4 during startup awaits confirmation instead of failing`() {
    val home = Files.createTempDirectory("early-parked")
    executable(home, "#!/bin/sh\nexit 4", "sona")

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
    executable(home, "#!/bin/sh\nuntil [ -e release ]; do :; done\nexit 4", "sona")
    val started =
      assertIs<WorkRunLaunchResult.Started>(launcherWithoutStartupGrace(home).start(runtimeRequest(home)))
    Files.createFile(home.resolve("release"))
    assertEquals(4, assertNotNull(started.exit).toCompletableFuture().get().code)
  }

  @Test
  fun `the installed sona command receives runtime arguments database and operation launch id`() {
    val home = Files.createTempDirectory("launch-id")
    executable(
      home,
      "#!/bin/sh\nprintf '%s\\n' \"\$@\" > arguments\n" +
        "printf '%s' \"\$${RuntimeDatabaseEnvironmentKeys.DATABASE_PATH}\" > database-path\n" +
        "printf '%s' \"\$SKILL_BILL_OPERATION_LAUNCH_ID\" > launch-id\nexit 0",
      "sona",
    )
    executable(home, "#!/bin/sh\ntouch legacy-was-started", "skill-bill")
    val database = home.resolve("selected database.db")
    val request = runtimeRequest(home).copy(databasePath = database, operationLaunchId = "opl-9")
    val started = assertIs<WorkRunLaunchResult.Started>(launcherWithoutStartupGrace(home).start(request))
    assertEquals(WorkRunExit(0, emptyList()), assertNotNull(started.exit).toCompletableFuture().get())
    assertEquals(listOf("goal", "SKILL-9", "--agent", "claude"), Files.readAllLines(home.resolve("arguments")))
    assertEquals(database.toString(), Files.readString(home.resolve("database-path")))
    assertEquals("opl-9", Files.readString(home.resolve("launch-id")))
    assertFalse(Files.exists(home.resolve("legacy-was-started")))
  }

  @Test
  fun `new phase and operation iterations replace stopped owners but refuse an already running iteration`() {
    for (arguments in listOf(listOf("phase", "validation"), listOf("operation", "verify", "requirements"))) {
      val home = Files.createTempDirectory("new-runtime-iteration")
      executable(home, "#!/bin/sh\nuntil [ -e release ]; do :; done", "sona")
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
    executable(home, RECORD_AGENT_HOME, "sona")
    val hostHomes = HostAgentHomesPort { mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "/host/claude-work") }
    val started =
      assertIs<WorkRunLaunchResult.Started>(launcherWithoutStartupGrace(home, hostHomes).start(runtimeRequest(home)))
    assertEquals(WorkRunExit(0, emptyList()), assertNotNull(started.exit).toCompletableFuture().get())
    assertEquals("/host/claude-work", recordedAgentHome(home))
  }

  @Test
  fun `the host agent home wins over a recorded one`() {
    val home = Files.createTempDirectory("host-over-recorded")
    executable(home, RECORD_AGENT_HOME, "sona")
    val hostHomes = HostAgentHomesPort { mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "/host/claude-work") }
    val recorded = mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "/recorded/claude")
    val request = runtimeRequest(home).copy(agentEnvironment = recorded)
    val started = assertIs<WorkRunLaunchResult.Started>(launcherWithoutStartupGrace(home, hostHomes).start(request))
    assertEquals(WorkRunExit(0, emptyList()), assertNotNull(started.exit).toCompletableFuture().get())
    assertEquals("/host/claude-work", recordedAgentHome(home))
  }

  @Test
  fun `a recorded agent home applies when the host chose none`() {
    val home = Files.createTempDirectory("recorded-agent-home")
    executable(home, RECORD_AGENT_HOME, "sona")
    val recorded = mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "/recorded/claude")
    val request = runtimeRequest(home).copy(agentEnvironment = recorded)
    val started = assertIs<WorkRunLaunchResult.Started>(launcherWithoutStartupGrace(home).start(request))
    assertEquals(WorkRunExit(0, emptyList()), assertNotNull(started.exit).toCompletableFuture().get())
    assertEquals("/recorded/claude", recordedAgentHome(home))
  }

  @Test
  fun `an empty host agent home removes the variable so the agent uses its default home`() {
    val home = Files.createTempDirectory("default-agent-home")
    executable(home, RECORD_AGENT_HOME, "sona")
    val hostHomes = HostAgentHomesPort { mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "") }
    val environment = TRAY_ENVIRONMENT + (AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "/tray/claude")
    val recorded = mapOf(AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR to "/recorded/claude")
    val request = runtimeRequest(home).copy(agentEnvironment = recorded)
    val started =
      assertIs<WorkRunLaunchResult.Started>(launcherWithoutStartupGrace(home, hostHomes, environment).start(request))
    assertEquals(WorkRunExit(0, emptyList()), assertNotNull(started.exit).toCompletableFuture().get())
    assertEquals("", recordedAgentHome(home))
  }

  private fun recordedAgentHome(home: Path): String = Files.readString(home.resolve("agent-home"))

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

  private fun launcher(home: Path) =
    DetachedWorkRunLauncher(
      EnvironmentContext(environment = TRAY_ENVIRONMENT, userHome = home),
      NoopRuntimeDiagnostics,
      HostAgentHomesPort { emptyMap() },
    )

  private fun launcherWithoutStartupGrace(
    home: Path,
    agentHomes: HostAgentHomesPort = HostAgentHomesPort { emptyMap() },
    environment: Map<String, String> = TRAY_ENVIRONMENT,
  ) = DetachedWorkRunLauncher(
    EnvironmentContext(environment = environment, userHome = home),
    NoopRuntimeDiagnostics,
    Duration.ZERO,
    agentHomes,
    awaitStartupExit = { _, _ -> false },
  )

  private companion object {
    val TRAY_ENVIRONMENT = mapOf("PATH" to "/usr/bin:/bin")
    const val FAIL_WHEN_RELEASED =
      "#!/bin/sh\nprintf '\\377\\n'\necho \"Phase 'monitor' blocked at 'monitor': no origin.\"\n" +
        "until [ -e release ]; do :; done\nexit 3"
    const val RECORD_AGENT_HOME =
      "#!/bin/sh\nprintf '%s' \"\$CLAUDE_CONFIG_DIR\" > agent-home.tmp && mv agent-home.tmp agent-home"
  }
}
