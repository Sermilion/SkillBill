package skillbill.infrastructure.sqlite

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigrations
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.core.schema.DatabaseSchema
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SQLiteDatabaseSchemaTest {
  @Test
  fun `missing database is left absent`() {
    val tempDir = Files.createTempDirectory("skillbill-schema-missing")
    val dbPath = tempDir.resolve("metrics.db")

    assertFalse(schemaFor(tempDir, dbPath).migrateExistingDatabase())
    assertFalse(Files.exists(dbPath))
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

    assertTrue(schemaFor(tempDir, dbPath).migrateExistingDatabase())

    DatabaseRuntime.openExistingReadDbAt(dbPath, SqliteTestDiagnostics).close()
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
