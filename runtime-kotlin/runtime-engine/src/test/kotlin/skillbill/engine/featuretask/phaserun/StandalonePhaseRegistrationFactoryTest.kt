package skillbill.engine.featuretask.phaserun

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class StandalonePhaseRegistrationFactoryTest {
  @Test
  fun `registrations record only present agent home keys from the launching environment`() {
    val root = Files.createTempDirectory("registration-agent-environment")
    val operator =
      factory(
        mapOf(
          "CLAUDE_CONFIG_DIR" to "/home/dev/.claude-work",
          "CODEX_HOME" to "  ",
          "PATH" to "/usr/bin",
          "HOME" to "/home/dev",
          "ANTHROPIC_API_KEY" to "secret",
        ),
      )
    val expected = mapOf("CLAUDE_CONFIG_DIR" to "/home/dev/.claude-work")
    assertEquals(expected, operator.phaseRegistration(root, "monitor", "run-1", null).agentEnvironment)
    assertEquals(expected, operator.workflowRegistration(root, "SKILL-1", "goal-parent").agentEnvironment)

    val launchd = factory(mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/home/dev", "SHELL" to "/bin/zsh"))
    assertEquals(emptyMap(), launchd.phaseRegistration(root, "monitor", "run-2", null).agentEnvironment)
    assertEquals(emptyMap(), launchd.workflowRegistration(root, "SKILL-1", "goal-parent").agentEnvironment)
  }

  private fun factory(environment: Map<String, String>) =
    StandalonePhaseRegistrationFactory(
      TestRepositoryEnclosingRoot,
      CheckedOutBranchSource { "main" },
      Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), ZoneOffset.UTC),
      NoopFeatureTaskRuntimeWorkerSupervisor,
      NoopRuntimeDiagnostics,
      EnvironmentContext(environment = environment),
    )
}
