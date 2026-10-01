package skillbill.di.core

import skillbill.infrastructure.sqlite.SqliteTestDatabasePaths
import skillbill.model.EnvironmentContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeDatabasePathCompositionTest {
  @Test
  fun `cli style explicit override and mcp style environment override resolve the same database path`() {
    val home = Files.createTempDirectory("skillbill-db-composition-explicit")
    val explicitDb = home.resolve("metrics.db")
    val expected = explicitDb.toAbsolutePath().normalize()

    val cliStyle =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment =
            EnvironmentContext(
              dbPathOverride = explicitDb.toString(),
              environment = emptyMap(),
              userHome = home,
            ),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )
    val mcpStyle =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment =
            EnvironmentContext(
              environment = mapOf(SqliteTestDatabasePaths.DB_ENVIRONMENT_KEY to explicitDb.toString()),
              userHome = home,
            ),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )

    assertEquals(expected, resolvedDoctorPath(cliStyle))
    assertEquals(expected, resolvedDoctorPath(mcpStyle))
  }

  @Test
  fun `cli style and mcp style default contexts resolve the same default database path`() {
    val home = Files.createTempDirectory("skillbill-db-composition-default")
    val expected = home.resolve(".skill-bill/review-metrics.db").toAbsolutePath().normalize()

    val cliStyle =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment = EnvironmentContext(environment = emptyMap(), userHome = home),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )
    val mcpStyle =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment = EnvironmentContext(environment = emptyMap(), userHome = home),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )

    assertEquals(expected, resolvedDoctorPath(cliStyle))
    assertEquals(expected, resolvedDoctorPath(mcpStyle))
  }

  private fun resolvedDoctorPath(component: RuntimeComponent): Path =
    Path.of(component.systemService.doctor().dbPath).toAbsolutePath().normalize()
}
