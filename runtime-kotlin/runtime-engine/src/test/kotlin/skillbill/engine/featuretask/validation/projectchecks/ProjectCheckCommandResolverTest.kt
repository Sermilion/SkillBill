package skillbill.engine.featuretask.validation.projectchecks

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.contracts.config.projectchecks.ProjectCheckCategory
import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckDiscoveryResponse
import skillbill.engine.featuretask.validation.validationGateTestDeclaration
import skillbill.error.core.SkillBillRuntimeException
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.config.projectchecks.ProjectCheckDiscoveryLock
import skillbill.ports.config.projectchecks.ProjectCheckDiscoveryValidator
import skillbill.ports.db.DatabaseSchemaPort
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectCheckCommandResolverTest {
  @Test
  fun `a transient discovery launch failure retries and persists discovered commands`() {
    val database = FakeDatabaseSessionFactory(InMemoryWorkflowStates())
    val launcher =
      ScriptedDiscoveryLauncher(
        listOf(
          facts(AgentRunTermination.Exited(1)),
          facts(AgentRunTermination.Exited(0)),
        ),
      )
    val resolver = resolver(database, launcher)

    val snapshot = resolver.resolve(request())

    assertEquals(2, launcher.calls)
    assertEquals(discoveredCommands, snapshot.config.validationCommands)
    assertEquals(discoveredCommands, database.projectCheckConfigs.load("test-project")?.validationCommands)
  }

  @Test
  fun `a rejected discovery response retries and saves`() {
    val database = FakeDatabaseSessionFactory(InMemoryWorkflowStates())
    val launcher =
      ScriptedDiscoveryLauncher(
        listOf(
          facts(AgentRunTermination.Exited(0)),
          facts(AgentRunTermination.Exited(0)),
        ),
      )
    var validatorCalls = 0
    val resolver =
      resolver(
        database,
        launcher,
        ProjectCheckDiscoveryValidator { _, _, _, _ ->
          validatorCalls++
          if (validatorCalls == 1) {
            throw SkillBillRuntimeException("malformed discovery output")
          }
          discoveredResponse
        },
      )

    val snapshot = resolver.resolve(request())

    assertEquals(2, launcher.calls)
    assertEquals(discoveredCommands, snapshot.config.validationCommands)
    assertEquals(discoveredCommands, database.projectCheckConfigs.load("test-project")?.validationCommands)
  }

  @Test
  fun `a denied discovery launch is not retried`() {
    val database = FakeDatabaseSessionFactory(InMemoryWorkflowStates())
    val launcher =
      ScriptedDiscoveryLauncher(
        listOf(AgentRunLaunchDenied(SupportedAgent.CLAUDE, "spawn denied")),
      )
    val resolver = resolver(database, launcher)

    val snapshot = resolver.resolve(request())

    assertEquals(1, launcher.calls)
    assertNull(snapshot.config.validationCommands)
    assertNull(database.projectCheckConfigs.load("test-project")?.validationCommands)
  }

  private fun facts(termination: AgentRunTermination): AgentRunLaunchFacts =
    AgentRunLaunchFacts(
      agent = SupportedAgent.CLAUDE,
      termination = termination,
      stdout = "discovery output",
      stderr = "",
      stdoutByteSize = 0,
      stdoutSha256 = "",
    )

  private fun resolver(
    database: FakeDatabaseSessionFactory,
    launcher: AgentRunLauncher,
    validator: ProjectCheckDiscoveryValidator = ProjectCheckDiscoveryValidator { _, _, _, _ -> discoveredResponse },
  ): ProjectCheckCommandResolver =
    ProjectCheckCommandResolver(
      ProjectCheckConfigAccess(noopSchema(), database, FixedProjectStorePort(Path.of("."))),
      ProjectCheckCommandDiscovery(launcher, validator, NoopRuntimeDiagnostics),
      passThroughLock(),
      NoopRuntimeDiagnostics,
    )

  private fun noopSchema(): DatabaseSchemaPort =
    object : DatabaseSchemaPort {
      override fun ensureDatabaseReady() = Unit

      override fun migrateExistingDatabase(): Boolean = false

      override fun hasPendingMigrations(): Boolean = false
    }

  private fun passThroughLock(): ProjectCheckDiscoveryLock =
    object : ProjectCheckDiscoveryLock {
      override fun <T> withProjectOwnership(
        projectId: String,
        action: () -> T,
      ): T = action()
    }

  private fun request(): ProjectCheckCommandResolverRequest =
    ProjectCheckCommandResolverRequest(
      Path.of("."),
      setOf(ProjectCheckCategory.VALIDATION),
      ProjectCheckCommandDiscoveryRequest(
        packSlug = "kotlin",
        validationGate = validationGateTestDeclaration,
        issueKey = "SKILL-1",
        agentId = "claude",
        modelOverride = null,
        effortOverride = null,
        timeout = null,
      ),
    )

  private class ScriptedDiscoveryLauncher(
    private val outcomes: List<AgentRunLaunchOutcome>,
  ) : AgentRunLauncher {
    var calls: Int = 0
      private set

    override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
      val index = calls
      calls++
      return outcomes.getOrElse(index) {
        error("discovery launcher exhausted after ${outcomes.size} outcomes; call=$index")
      }
    }

    override fun resolveLaunchModel(request: AgentRunLaunchModelRequest): EffectiveLaunchModel =
      error("discovery tests do not resolve launch models")
  }

  private companion object {
    val discoveredCommands =
      ProjectCheckCategoryConfig(listOf(ProjectCheckCommand(listOf("./gradlew", "check"), ".")))
    val discoveredResponse =
      ProjectCheckDiscoveryResponse(
        "test-project",
        mapOf(ProjectCheckCategory.VALIDATION to discoveredCommands),
      )
  }
}
