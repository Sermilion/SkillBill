package skillbill.infrastructure.launcher.work

import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.system.HostAgentHomesPort
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
import kotlin.test.assertTrue

class DetachedWorkRunLauncherTest {
  @Test
  fun `a missing harness refuses launch without falling back to runtime cli`() {
    val home = Files.createTempDirectory("missing-harness")
    val request = harnessRequest(home, "Resume")
    val refused = assertIs<WorkRunLaunchResult.Refused>(launcher(home).start(request))
    assertTrue(refused.reason.contains("claude harness"))
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

  private fun recordedAgentHome(home: Path): String {
    val written = home.resolve("agent-home")
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

  private fun harnessRequest(
    home: Path,
    prompt: String,
  ) = WorkRunLaunchRequest(home, home.resolve("metrics.db"), "work", WorkRunLaunchKind.AgentHarness("claude", prompt))

  private fun launcher(
    home: Path,
    agentHomes: HostAgentHomesPort = HostAgentHomesPort { emptyMap() },
    environment: Map<String, String> = TRAY_ENVIRONMENT,
  ) = DetachedWorkRunLauncher(
    EnvironmentContext(environment = environment, userHome = home),
    NoopRuntimeDiagnostics,
    FixedProjectStorePort(home.resolve("project-store")),
    agentHomes,
  )

  private companion object {
    val TRAY_ENVIRONMENT = mapOf("PATH" to "/usr/bin:/bin")
    val RECORD_TIMEOUT: Duration = Duration.ofSeconds(10)
    const val POLL_MILLIS = 20L
    const val RECORD_AGENT_HOME =
      "#!/bin/sh\nprintf '%s' \"\$CLAUDE_CONFIG_DIR\" > agent-home.tmp && mv agent-home.tmp agent-home"
  }
}
