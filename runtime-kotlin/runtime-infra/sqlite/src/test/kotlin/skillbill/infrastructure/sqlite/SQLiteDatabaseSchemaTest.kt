package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.schema.DatabaseSchema
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SQLiteDatabaseSchemaTest {
  @Test
  fun `fresh installation creates a database readable by desktop consumers before any workflow`() {
    val tempDir = Files.createTempDirectory("skillbill-schema-missing")
    val dbPath = tempDir.resolve(".skill-bill/review-metrics.db")

    assertFalse(Files.exists(dbPath))
    val schema = schemaFor(tempDir, dbPath)
    schema.ensureDatabaseReady()
    schema.ensureDatabaseReady()

    DatabaseRuntime.openExistingReadDbAt(dbPath, SqliteTestDiagnostics).use { database ->
      database.connection.createStatement().use { statement ->
        statement.executeQuery("SELECT 1 FROM feature_task_workflows LIMIT 1").use { rows ->
          assertFalse(rows.next())
        }
      }
    }
  }

  @Test
  fun `existing database behind the runtime schema becomes readable by desktop consumers`() {
    val tempDir = Files.createTempDirectory("skillbill-schema-behind")
    val dbPath = tempDir.resolve("metrics.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      DatabaseMigrations.apply(
        connection,
        SqliteTestDiagnostics,
        migrationSet = DatabaseMigrations.migrations.filter { it.version < DESKTOP_READ_IDENTITY_VERSION },
      )
    }
    assertFailsWith<SkillBillRuntimeException> {
      DatabaseRuntime.openExistingReadDbAt(dbPath, SqliteTestDiagnostics).close()
    }

    schemaFor(tempDir, dbPath).ensureDatabaseReady()

    DatabaseRuntime.openExistingReadDbAt(dbPath, SqliteTestDiagnostics).close()
  }

  @Test
  fun `upgrade refuses a newer database without downgrading its version or ledger`() {
    establishTemporarySchemaReadiness("skillbill-schema-newer").use { fixture ->
      val newerVersion = DesktopReadSchema.VERSION + 1
      fixture.connection.createStatement().use { statement ->
        statement.execute("PRAGMA user_version = $newerVersion")
      }
      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          schemaFor(fixture.tempDir, fixture.dbPath).ensureDatabaseReady()
        }
      assertEquals(DatabaseFailureCode.INCOMPATIBLE_SCHEMA, failure.code)
      fixture.connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA user_version").use { rows ->
          rows.next()
          assertEquals(newerVersion, rows.getInt(1))
        }
        statement.execute("PRAGMA user_version = ${DesktopReadSchema.VERSION}")
        statement.execute("INSERT INTO schema_migrations (name, version) VALUES ('future-schema', $newerVersion)")
      }
      assertFailsWith<SkillBillRuntimeException> {
        schemaFor(fixture.tempDir, fixture.dbPath).ensureDatabaseReady()
      }
      fixture.connection.createStatement().use { statement ->
        statement.executeQuery("SELECT MAX(version) FROM schema_migrations").use { rows ->
          rows.next()
          assertEquals(newerVersion, rows.getInt(1))
        }
      }
    }
  }

  @Test
  fun `pending ledger migration runs even when write readiness cached the current version`() {
    establishTemporarySchemaReadiness("skillbill-schema-cached-ledger").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      database.transaction { }
      val workerGenerationMigration =
        DatabaseMigrations.migrations.single {
          it.name == "retain-latest-feature-task-worker-generation"
        }.version
      fixture.connection.createStatement().use {
        it.execute("DROP TABLE feature_task_runtime_worker_generations")
        it.execute("DELETE FROM schema_migrations WHERE version = $workerGenerationMigration")
      }
      schemaFor(fixture.tempDir, fixture.dbPath).ensureDatabaseReady()
      DatabaseRuntime.openExistingReadDbAt(fixture.dbPath, SqliteTestDiagnostics).close()
    }
  }

  @Test
  fun `upgrade refuses an unrelated database without installing runtime tables`() {
    val tempDir = Files.createTempDirectory("skillbill-schema-foreign")
    val dbPath = tempDir.resolve("other.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { it.execute("CREATE TABLE unrelated (value TEXT)") }
      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          schemaFor(tempDir, dbPath).ensureDatabaseReady()
        }
      assertEquals(DatabaseFailureCode.INCOMPATIBLE_SCHEMA, failure.code)
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
          assertTrue(rows.next())
          assertEquals("unrelated", rows.getString(1))
          assertFalse(rows.next())
        }
      }
    }
  }

  private fun schemaFor(
    tempDir: Path,
    dbPath: Path,
  ): SQLiteDatabaseSchema =
    SQLiteDatabaseSchema(
      sqliteDatabaseSessionFactory(
        userHome = tempDir,
        dbPathOverride = dbPath.toString(),
        environment = mapOf("HOME" to tempDir.toString()),
      ),
      SqliteTestDiagnostics,
    )

  private companion object {
    const val DESKTOP_READ_IDENTITY_VERSION = 52
  }
}
