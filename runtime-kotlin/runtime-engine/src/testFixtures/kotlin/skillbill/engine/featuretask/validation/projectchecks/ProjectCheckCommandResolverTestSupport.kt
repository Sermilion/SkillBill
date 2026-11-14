package skillbill.engine.featuretask.validation.projectchecks

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckConfig
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.config.projectchecks.ProjectCheckDiscoveryLock
import skillbill.ports.config.projectchecks.ProjectCheckDiscoveryValidator
import skillbill.ports.db.DatabaseSchemaPort
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import java.nio.file.Path

fun committedProjectCheckConfigForTests(
  buildArgv: List<String>? = listOf("echo", "build"),
  validationArgv: List<String>? = listOf("echo", "cache"),
  projectId: String = "test-project",
  knownEmptyBuild: Boolean = false,
): ProjectCheckConfig {
  fun commands(argv: List<String>?) = argv?.let { ProjectCheckCategoryConfig(listOf(ProjectCheckCommand(it, "."))) }
  return ProjectCheckConfig(
    projectId,
    if (knownEmptyBuild) ProjectCheckCategoryConfig(emptyList()) else commands(buildArgv),
    null,
    null,
    null,
    commands(validationArgv),
  )
}

fun projectCheckCommandResolverForTests(
  database: FakeDatabaseSessionFactory = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
  committed: ProjectCheckConfig? = committedProjectCheckConfigForTests(),
  discoveryValidator: ProjectCheckDiscoveryValidator =
    ProjectCheckDiscoveryValidator { _, _, _, _ ->
      error("project check discovery is unavailable in this test")
    },
): ProjectCheckCommandResolver {
  committed?.let(database.projectCheckConfigs::save)
  val schema =
    object : DatabaseSchemaPort {
      override fun ensureDatabaseReady() {}

      override fun migrateExistingDatabase(): Boolean = false

      override fun hasPendingMigrations(): Boolean = false
    }
  val lock =
    object : ProjectCheckDiscoveryLock {
      override fun <T> withProjectOwnership(
        projectId: String,
        action: () -> T,
      ): T = action()
    }
  return ProjectCheckCommandResolver(
    ProjectCheckConfigAccess(schema, database, FixedProjectStorePort(Path.of("."))),
    ProjectCheckCommandDiscovery(unavailableDiscoveryLauncher(), discoveryValidator, NoopRuntimeDiagnostics),
    lock,
    NoopRuntimeDiagnostics,
  )
}

private fun unavailableDiscoveryLauncher(): AgentRunLauncher =
  object : AgentRunLauncher {
    override fun launch(request: AgentRunLaunchRequest) =
      error("project check discovery must not launch an agent in this test")

    override fun resolveLaunchModel(request: AgentRunLaunchModelRequest): EffectiveLaunchModel =
      error("project check discovery must not resolve launch models in this test")
  }
