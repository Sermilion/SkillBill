package skillbill.engine.featuretask.phaserun

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import java.nio.file.Files
import java.nio.file.Path
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

  @Test
  fun `registrations capture the primary checkout without changing checkout identity`() {
    val checkout = Files.createTempDirectory("registration-primary-root")
    val primaryRoot = Path.of("/primary-checkout")
    val repositories =
      object : RepositoryEnclosingRootPort by TestRepositoryEnclosingRoot {
        override fun primaryRepositoryRoot(repoRoot: Path): Path = primaryRoot
      }
    val operator = factory(emptyMap(), repositories)

    val phase = operator.phaseRegistration(checkout, "implement", "run-1", "SKILL-535")
    val workflow = operator.workflowRegistration(checkout, "SKILL-535", "goal-parent", goalScoped = true)

    assertEquals(TestRepositoryEnclosingRoot.repositoryIdentity(checkout), phase.repositoryIdentity)
    assertEquals(TestRepositoryEnclosingRoot.primaryRepositoryIdentity(checkout), workflow.repositoryIdentity)
    assertEquals(primaryRoot.toString(), phase.primaryRepositoryRoot)
    assertEquals(primaryRoot.toString(), workflow.primaryRepositoryRoot)
  }

  private fun factory(
    environment: Map<String, String>,
    repositories: RepositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
  ) = StandalonePhaseRegistrationFactory(
    repositories,
    CheckedOutBranchSource { "main" },
    Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), ZoneOffset.UTC),
    NoopFeatureTaskRuntimeWorkerSupervisor,
    NoopRuntimeDiagnostics,
    EnvironmentContext(environment = environment),
  )
}
