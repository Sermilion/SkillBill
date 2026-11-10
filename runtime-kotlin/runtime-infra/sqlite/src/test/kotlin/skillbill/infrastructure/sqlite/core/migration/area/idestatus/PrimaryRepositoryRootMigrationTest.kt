package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrimaryRepositoryRootMigrationTest {
  @Test
  fun `partial column addition rolls back and readiness can be retried repeatedly`() {
    val database = Files.createTempDirectory("primary-root-migration").resolve("status.db")
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("CREATE TABLE standalone_phase_status (execution_id TEXT, current_step TEXT)")
        statement.execute("INSERT INTO standalone_phase_status VALUES ('execution-1', 'review')")
      }
      connection.autoCommit = false

      assertFailsWith<SQLException> { PrimaryRepositoryRootMigration.apply(connection) }
      connection.rollback()

      assertFalse(hasPrimaryRootColumn(connection, "standalone_phase_status"))
      assertEquals("review", scalarString(connection, "SELECT current_step FROM standalone_phase_status"))

      connection.createStatement().use { statement ->
        statement.execute("CREATE TABLE ide_status_workflow_execution (execution_id TEXT, lifecycle_state TEXT)")
        statement.execute("INSERT INTO ide_status_workflow_execution VALUES ('workflow-1', 'completed')")
        statement.execute("ALTER TABLE standalone_phase_status ADD COLUMN primary_repository_root TEXT")
      }
      PrimaryRepositoryRootMigration.apply(connection)
      connection.commit()

      assertTrue(hasPrimaryRootColumn(connection, "standalone_phase_status"))
      assertTrue(hasPrimaryRootColumn(connection, "ide_status_workflow_execution"))
      assertEquals("review", scalarString(connection, "SELECT current_step FROM standalone_phase_status"))
      assertEquals("completed", scalarString(connection, "SELECT lifecycle_state FROM ide_status_workflow_execution"))
      assertEquals(null, scalarString(connection, "SELECT primary_repository_root FROM standalone_phase_status"))

      PrimaryRepositoryRootMigration.apply(connection)
      assertEquals(null, scalarString(connection, "SELECT primary_repository_root FROM ide_status_workflow_execution"))
    }
  }

  private fun hasPrimaryRootColumn(
    connection: Connection,
    table: String,
  ): Boolean =
    connection.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name = ?").use { statement ->
      statement.setString(1, table)
      statement.setString(2, PRIMARY_REPOSITORY_ROOT_COLUMN)
      statement.executeQuery().use { it.next() }
    }

  private fun scalarString(
    connection: Connection,
    query: String,
  ): String? =
    connection.createStatement().use { statement ->
      statement.executeQuery(query).use { rows ->
        rows.next()
        rows.getString(1)
      }
    }
}
